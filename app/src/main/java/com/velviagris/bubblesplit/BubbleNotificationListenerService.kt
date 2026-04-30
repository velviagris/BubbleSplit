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
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.cancel
import androidx.core.graphics.drawable.toBitmap

class BubbleNotificationListenerService : NotificationListenerService() {

    // 【新增】：为 Service 创建专属的协程作用域
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    // 【新增】：服务销毁时清理协程，防止内存泄漏
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

                val isTakeOver = AppUtils.isTakeOverNotifications(this@BubbleNotificationListenerService)
                if (isTakeOver) {
                    cancelNotification(sbn.key)
                }

                AppUtils.setAutoLaunchTarget(pkg, 10000L)

                val nm = getSystemService(NOTIFICATION_SERVICE) as android.app.NotificationManager
                val activeNotif = nm.activeNotifications.find { it.id == 1001 }
                val isShowing = activeNotif != null

                // 【核心神迹】：判断当前气泡是否正悬浮在屏幕上
                // Android 底层规定 FLAG_BUBBLE 的值为 0x00001000 (即 4096)
                val isCurrentlyBubbling = activeNotif?.let {
                    (it.notification.flags and Notification.FLAG_BUBBLE) != 0
                } ?: false

                if (isShowing && !isCurrentlyBubbling) {
                    // 情况 A：气泡被用户拖到 X 关闭了（但通知还在下拉栏）
                    // 此时绝对不去调用 notify，彻底防止气泡死灰复燃糊脸！
                    return@launch
                }

                // 情况 B：要么是完全没通知 (首次弹出)，要么是气泡正悬浮在屏幕上 (更新气泡)
                val appName = AppUtils.getAppName(this@BubbleNotificationListenerService, pkg)
                val extras = notification.extras
                val title = extras.getString(Notification.EXTRA_TITLE) ?: appName
                val text = extras.getCharSequence(Notification.EXTRA_TEXT)?.toString() ?: ""
                val originalContentIntent = notification.contentIntent

                // 把 isShowing 传过去，用来控制是否需要发声
                updateMainBubble(pkg, appName, title, text, originalContentIntent, isShowing)
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
            // 容错：如果获取失败，退回到我们的默认图标
            androidx.core.content.ContextCompat.getDrawable(this, R.drawable.ic_launcher_foreground)!!
        }

        // 将 Drawable 转换成 144x144 的 Bitmap (固定尺寸防止某些矢量图转换崩溃)
        val iconBitmap = appIconDrawable.toBitmap(144, 144)
        // 使用 Bitmap 创建 IconCompat，供气泡和通知使用
        val icon = IconCompat.createWithBitmap(iconBitmap)

        val chatPartner = Person.Builder()
            .setName(appName)
            .setIcon(icon)
            .setImportant(true)
            .build()

        val targetIntent = Intent(this, BubbleActivity::class.java)
        val bubbleIntent = PendingIntent.getActivity(
            this, 0, targetIntent,
            PendingIntent.FLAG_MUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val bubbleData = NotificationCompat.BubbleMetadata.Builder(bubbleIntent, icon)
            .setDesiredHeight(600)
            .setAutoExpandBubble(false)
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

        val fallbackIntent = PendingIntent.getActivity(
            this, 1, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val finalContentIntent = originalContentIntent ?: fallbackIntent

        val builder = NotificationCompat.Builder(this, channelId)
            .setContentTitle(title)
            .setContentText(text)
            .setContentIntent(finalContentIntent)
            .setSmallIcon(R.drawable.ic_notification)
            .setStyle(style)
            .setBubbleMetadata(bubbleData)
            .setShortcutId(shortcutId)
            .addPerson(chatPartner)
            .setCategory(NotificationCompat.CATEGORY_MESSAGE)
            .setOnlyAlertOnce(isUpdate)
            .setAutoCancel(true)

        try {
            NotificationManagerCompat.from(this).notify(1001, builder.build())
        } catch (e: SecurityException) {
            e.printStackTrace()
        }
    }
}