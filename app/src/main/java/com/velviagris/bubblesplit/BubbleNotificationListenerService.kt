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

class BubbleNotificationListenerService : NotificationListenerService() {

    companion object {
        // 记录上次触发气泡强行弹出的时间 (毫秒)
        private var lastAlertTime = 0L
        // 冷却勿扰时间：设定为 10 分钟 (用户移除了气泡后，10分钟内同类更新只会在通知栏静默叠加，不会强行霸屏弹出)
        private const val COOLDOWN_TIME_MS = 10 * 60 * 1000L
    }

    override fun onNotificationPosted(sbn: StatusBarNotification) {
        // 【核心修复】：工作分区/双开应用隔离
        // 检查通知的所属用户，如果和当前运行 BubbleSplit 的用户不一致，直接忽略！
        if (sbn.user != Process.myUserHandle()) {
            return
        }

        val pkg = sbn.packageName
        if (pkg == packageName) return

        val notification = sbn.notification
        // 忽略常驻通知或折叠摘要
        if (sbn.isOngoing || (notification.flags and Notification.FLAG_GROUP_SUMMARY != 0)) {
            return
        }

        val selectedApps = AppUtils.getSelectedApps(this)
        if (selectedApps.contains(pkg)) {
            val appName = AppUtils.getAppName(this, pkg)
            AppUtils.setAutoLaunchTarget(pkg, 10000L)

            val currentTime = System.currentTimeMillis()
            val shouldAlert = (currentTime - lastAlertTime) > COOLDOWN_TIME_MS
            if (shouldAlert) {
                lastAlertTime = currentTime
            }

            // ==================== 新增：提取并拦截 ====================
            // 1. 提取原通知的内容和标题
            val extras = notification.extras
            val title = extras.getString(Notification.EXTRA_TITLE) ?: appName
            val text = extras.getCharSequence(Notification.EXTRA_TEXT)?.toString() ?: ""

            // 2. 提取原通知的点击跳转 Intent (这非常关键，它是应用原生的跳转逻辑)
            val originalContentIntent = notification.contentIntent

            // 3. 杀掉原装通知 (这就是“接管”的精髓)
            cancelNotification(sbn.key)

            // 4. 将提取到的所有素材，传给我们的克隆气泡
            updateMainBubble(appName, title, text, originalContentIntent, shouldAlert)
        }
    }

    // 增加接收提取到的原通知信息
    private fun updateMainBubble(
        appName: String,
        title: String,
        text: String,
        originalContentIntent: PendingIntent?,
        shouldAlert: Boolean
    ) {
        val channelId = AppUtils.BUBBLE_CHANNEL_ID
        val shortcutId = "bubble_split_shortcut"

        val icon = IconCompat.createWithResource(this, R.drawable.ic_launcher_foreground)
        val chatPartner = Person.Builder()
            .setName(appName) // 把发送人改成真实的应用名
            .setIcon(icon)
            .setImportant(true)
            .build()

        // 气泡的跳转逻辑依然是我们自己的分屏控制台
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
            .setPerson(chatPartner)
            .build()
        ShortcutManagerCompat.pushDynamicShortcut(this, shortcut)

        // ==================== 新增：完美克隆原通知的视觉 ====================
        val style = NotificationCompat.MessagingStyle(chatPartner)
            // 把提取出来的真实聊天内容放进去
            .addMessage("$title: $text", System.currentTimeMillis(), chatPartner)

        //  fallback: 如果原通知没有 contentIntent，才退回到打开我们的主页
        val fallbackIntent = PendingIntent.getActivity(
            this, 1, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val finalContentIntent = originalContentIntent ?: fallbackIntent

        val builder = NotificationCompat.Builder(this, channelId)
            .setContentTitle(title) // 真实标题
            .setContentText(text)   // 真实内容
            // 【核心】：主通知体点击时，触发原装应用的跳转逻辑！
            .setContentIntent(finalContentIntent)
            .setSmallIcon(R.drawable.ic_notification)
            .setStyle(style)
            .setBubbleMetadata(bubbleData) // 依然保留气泡 metadata
            .setShortcutId(shortcutId)
            .addPerson(chatPartner)
            .setCategory(NotificationCompat.CATEGORY_MESSAGE)
            .setOnlyAlertOnce(!shouldAlert)
            .setAutoCancel(true) // 点击后自动消失

        try {
            NotificationManagerCompat.from(this).notify(1001, builder.build())
        } catch (e: SecurityException) {
            e.printStackTrace()
        }
    }
}