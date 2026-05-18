package com.velviagris.bubblesplit

import android.app.Notification
import android.app.PendingIntent
import android.content.Intent
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.Person
import androidx.core.content.pm.ShortcutInfoCompat
import androidx.core.content.pm.ShortcutManagerCompat
import androidx.core.graphics.drawable.IconCompat
import android.os.Process
import androidx.core.graphics.drawable.toBitmap
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.cancel

class BubbleNotificationListenerService : NotificationListenerService() {

    companion object {
        // 只需记录对话时间，无需任何复杂的拦截屏蔽
        private var lastMessageTime = 0L
        private const val COOLDOWN_TIME_MS = 10 * 60 * 1000L // 10分钟
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

                // 核心：接管原通知（秒杀系统的原生通知）
                val isTakeOver = AppUtils.isTakeOverNotifications(this@BubbleNotificationListenerService)
                if (isTakeOver) {
                    cancelNotification(sbn.key)
                }

                AppUtils.setAutoLaunchTarget(pkg, 10000L)

                val currentTime = System.currentTimeMillis()
                val isFreshStart = (currentTime - lastMessageTime) > COOLDOWN_TIME_MS
                lastMessageTime = currentTime

                // 去除所有死气泡拦截！只要有消息，永远构建并发送咱们的 1001 通知。
                // 如果是冷却期内的消息 (isUpdate = true)，它会静默更新到通知栏。
                val isUpdate = !isFreshStart

                val appName = AppUtils.getAppName(this@BubbleNotificationListenerService, pkg)
                val extras = notification.extras
                val title = extras.getString(Notification.EXTRA_TITLE) ?: appName
                val text = extras.getCharSequence(Notification.EXTRA_TEXT)?.toString() ?: ""

                // 提取原应用的跳转意图
                val originalContentIntent = notification.contentIntent

                updateMainBubble(pkg, appName, title, text, originalContentIntent, isUpdate)
            }
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

        // ==================== 意图分流 1：气泡行为 ====================
        // 绑给气泡的 Intent：拉起我们自己的 BubbleActivity (分屏控制台)
        val targetIntent = Intent(this, BubbleActivity::class.java)
        val bubbleIntent = PendingIntent.getActivity(
            this, 0, targetIntent,
            PendingIntent.FLAG_MUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val bubbleData = NotificationCompat.BubbleMetadata.Builder(bubbleIntent, icon)
            .setDesiredHeight(600)
            .setAutoExpandBubble(false) // 默认不强行弹脸，遵循系统习惯
            .build()
        // ==============================================================

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

        // ==================== 意图分流 2：主体行为 ====================
        // 绑给通知主体的 Intent：优先使用原应用的跳转逻辑
        val fallbackIntent = PendingIntent.getActivity(
            this, 1, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val finalContentIntent = originalContentIntent ?: fallbackIntent
        // ==============================================================

        val builder = NotificationCompat.Builder(this, channelId)
            .setContentTitle(title)
            .setContentText(text)
            .setContentIntent(finalContentIntent) // 【核心绑定】：点击文字卡片主体 -> 全屏进入微信
            .setSmallIcon(R.drawable.ic_notification)
            .setStyle(style)
            .setBubbleMetadata(bubbleData)        // 【核心绑定】：点击右下角气泡图标 -> 展开分屏控制台
            .setShortcutId(shortcutId)
            .addPerson(chatPartner)
            .setCategory(NotificationCompat.CATEGORY_MESSAGE)
            .setOnlyAlertOnce(isUpdate) // 短时间内收到多条消息，只在通知栏安静更新文本，不反复叮咚
            .setAutoCancel(true)        // 点击通知主体后，自动清除通知卡片

        try {
            NotificationManagerCompat.from(this).notify(1001, builder.build())
        } catch (e: SecurityException) {
            e.printStackTrace()
        }
    }
}