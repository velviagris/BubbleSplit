package com.velviagris.bubblesplit.service

import android.app.Notification
import android.app.PendingIntent
import android.content.Intent
import android.service.notification.NotificationListenerService
import android.service.notification.NotificationListenerService.RankingMap
import android.service.notification.StatusBarNotification
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.Person
import androidx.core.content.pm.ShortcutInfoCompat
import androidx.core.content.pm.ShortcutManagerCompat
import androidx.core.graphics.drawable.IconCompat
import android.os.Process
import androidx.core.graphics.drawable.toBitmap
import com.velviagris.bubblesplit.BubbleActivity
import com.velviagris.bubblesplit.MainActivity
import com.velviagris.bubblesplit.R
import com.velviagris.bubblesplit.util.AppUtils
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.cancel

class BubbleNotificationListenerService : NotificationListenerService() {

    companion object {
        // 记录最近一次气泡通知时间 / Track the latest bubble notification time.
        private var lastMessageTime = 0L
        private const val COOLDOWN_TIME_MS = 10 * 60 * 1000L // 10分钟 / 10 minutes.
        private const val MAIN_BUBBLE_NOTIFICATION_ID = 1001
    }

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    override fun onDestroy() {
        super.onDestroy()
        serviceScope.cancel()
    }

    override fun onNotificationPosted(sbn: StatusBarNotification) {
        if (sbn.user != Process.myUserHandle()) {
            return
        }

        val pkg = sbn.packageName
        if (pkg == packageName) return

        val notification = sbn.notification
        if (sbn.isOngoing || (notification.flags and Notification.FLAG_GROUP_SUMMARY != 0)) {
            return
        }

        val selectedApps = AppUtils.getSelectedApps(this)
        if (selectedApps.contains(pkg)) {
            serviceScope.launch {

                if (AppUtils.isAppInForeground(this@BubbleNotificationListenerService, pkg)) {
                    return@launch
                }

                val currentTime = System.currentTimeMillis()
                val isFreshStart = (currentTime - lastMessageTime) > COOLDOWN_TIME_MS
                val isUpdate = !isFreshStart

                // 接管原通知前先判断勿扰状态 / Check snooze before taking over the original notification.
                val isTakeOver = AppUtils.isTakeOverNotifications(this@BubbleNotificationListenerService)
                val isBubbleSnoozeEnabled = AppUtils.isBubbleSnoozeEnabled(this@BubbleNotificationListenerService)
                if (isTakeOver && isBubbleSnoozeEnabled && (isUpdate || AppUtils.isBubbleSnoozed(this@BubbleNotificationListenerService))) {
                    lastMessageTime = currentTime
                    return@launch
                }

                if (isTakeOver) {
                    cancelNotification(sbn.key)
                }

                AppUtils.setAutoLaunchTarget(pkg, 10000L)
                lastMessageTime = currentTime

                // 非勿扰路径继续发布 BubbleSplit 通知 / Outside snooze, keep publishing the BubbleSplit notification.

                val appName = AppUtils.getAppName(this@BubbleNotificationListenerService, pkg)
                val extras = notification.extras
                val title = extras.getString(Notification.EXTRA_TITLE) ?: appName
                val text = extras.getCharSequence(Notification.EXTRA_TEXT)?.toString() ?: ""

                // 提取原应用跳转意图 / Reuse the source app content intent when available.
                val originalContentIntent = notification.contentIntent

                updateMainBubble(pkg, appName, title, text, originalContentIntent, isUpdate)
            }
        }
    }

    override fun onNotificationRemoved(
        sbn: StatusBarNotification,
        rankingMap: RankingMap,
        reason: Int
    ) {
        super.onNotificationRemoved(sbn, rankingMap, reason)

        if (sbn.packageName != packageName || sbn.id != MAIN_BUBBLE_NOTIFICATION_ID) {
            return
        }

        val isUserDismissal = reason == REASON_CANCEL ||
                reason == REASON_CANCEL_ALL ||
                reason == REASON_USER_STOPPED

        if (isUserDismissal && AppUtils.isBubbleSnoozeEnabled(this)) {
            AppUtils.snoozeBubbles(this, COOLDOWN_TIME_MS)
        }
    }

    private fun updateMainBubble(
        pkg: String,
        appName: String,
        title: String,
        text: String,
        originalContentIntent: PendingIntent?,
        isUpdate: Boolean
    ) {
        val channelId = AppUtils.BUBBLE_CHANNEL_ID
        val shortcutId = "bubble_split_shortcut"

        val appIconDrawable = try {
            packageManager.getApplicationIcon(pkg)
        } catch (e: Exception) {
            androidx.core.content.ContextCompat.getDrawable(this, R.drawable.ic_launcher_foreground)!!
        }
        val iconBitmap = appIconDrawable.toBitmap(144, 144)
        val icon = IconCompat.createWithBitmap(iconBitmap)

        val chatPartner = Person.Builder()
            .setName(appName)
            .setIcon(icon)
            .setImportant(true)
            .build()

        // 气泡行为意图 / Bubble action intent: open BubbleActivity as the split-screen console.
        val targetIntent = Intent(this, BubbleActivity::class.java)
        val bubbleIntent = PendingIntent.getActivity(
            this, 0, targetIntent,
            PendingIntent.FLAG_MUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val bubbleData = NotificationCompat.BubbleMetadata.Builder(bubbleIntent, icon)
            .setDesiredHeight(600)
            .setAutoExpandBubble(false) // 默认不强行弹脸 / Let Android decide when to expand.
            .build()

        val shortcutIntent = Intent(this, MainActivity::class.java).apply { action = Intent.ACTION_MAIN }
        val shortcut = ShortcutInfoCompat.Builder(this, shortcutId)
            .setCategories(setOf("android.shortcut.conversation"))
            .setIntent(shortcutIntent)
            .setLongLived(true)
            .setShortLabel(appName)
            .setIcon(icon)
            .setPerson(chatPartner)
            .build()
        ShortcutManagerCompat.pushDynamicShortcut(this, shortcut)

        val style = NotificationCompat.MessagingStyle(chatPartner)
            .addMessage("$title: $text", System.currentTimeMillis(), chatPartner)

        // 通知主体意图 / Notification body intent: prefer the source app behavior.
        val fallbackIntent = PendingIntent.getActivity(
            this, 1, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val finalContentIntent = originalContentIntent ?: fallbackIntent

        val builder = NotificationCompat.Builder(this, channelId)
            .setContentTitle(title)
            .setContentText(text)
            .setContentIntent(finalContentIntent) // 点击通知主体 / Tap the notification body.
            .setSmallIcon(R.drawable.ic_notification)
            .setStyle(style)
            .setBubbleMetadata(bubbleData)        // 绑定气泡入口 / Bind the bubble entry point.
            .setShortcutId(shortcutId)
            .addPerson(chatPartner)
            .setCategory(NotificationCompat.CATEGORY_MESSAGE)
            .setOnlyAlertOnce(isUpdate) // 更新时静默 / Quietly update repeated messages.
            .setAutoCancel(true)        // 点击后清除通知 / Clear after tapping the notification.

        try {
            NotificationManagerCompat.from(this).notify(MAIN_BUBBLE_NOTIFICATION_ID, builder.build())
        } catch (e: SecurityException) {
            e.printStackTrace()
        }
    }
}
