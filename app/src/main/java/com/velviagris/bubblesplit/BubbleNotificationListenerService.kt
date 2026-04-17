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

class BubbleNotificationListenerService : NotificationListenerService() {

    companion object {
        // 记录上次触发气泡强行弹出的时间 (毫秒)
        private var lastAlertTime = 0L
        // 冷却勿扰时间：设定为 10 分钟 (用户移除了气泡后，10分钟内同类更新只会在通知栏静默叠加，不会强行霸屏弹出)
        private const val COOLDOWN_TIME_MS = 10 * 60 * 1000L
    }

    override fun onNotificationPosted(sbn: StatusBarNotification) {
        val pkg = sbn.packageName
        if (pkg == packageName) return

        // 【核心优化 1】：拦截“幽灵”通知，解决电脑端处理后手机瞎弹气泡的问题
        val notification = sbn.notification
        // 忽略常驻通知 (isOngoing，比如微信的“正在运行”)
        // 忽略系统自动生成的群组折叠摘要 (FLAG_GROUP_SUMMARY)
        if (sbn.isOngoing || (notification.flags and Notification.FLAG_GROUP_SUMMARY != 0)) {
            return
        }

        val selectedApps = AppUtils.getSelectedApps(this)
        if (selectedApps.contains(pkg)) {
            val appName = AppUtils.getAppName(this, pkg)
            AppUtils.setAutoLaunchTarget(pkg, 10000L)

            // 【核心优化 2】：智能冷却判定
            val currentTime = System.currentTimeMillis()
            // 判断距离上一次弹窗是否已经过了冷却时间
            val shouldAlert = (currentTime - lastAlertTime) > COOLDOWN_TIME_MS

            // 如果允许弹窗，则重置冷却计时的起点
            if (shouldAlert) {
                lastAlertTime = currentTime
            }

            updateMainBubble(appName, shouldAlert)
        }
    }

    // 接收 shouldAlert 参数，决定本次是强行弹出还是静默更新
    private fun updateMainBubble(appName: String, shouldAlert: Boolean) {
        val channelId = AppUtils.BUBBLE_CHANNEL_ID
        val shortcutId = "bubble_split_shortcut"

        val icon = IconCompat.createWithResource(this, R.drawable.ic_launcher_foreground)
        val chatPartner = Person.Builder()
            .setName(getString(R.string.notif_partner_name))
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
            .setShortLabel(getString(R.string.notif_partner_name))
            .setPerson(chatPartner)
            .build()
        ShortcutManagerCompat.pushDynamicShortcut(this, shortcut)

        val msgText = getString(R.string.notif_dynamic_msg)
        val style = NotificationCompat.MessagingStyle(chatPartner)
            .addMessage("$msgText $appName", System.currentTimeMillis(), chatPartner)

        val contentIntent = PendingIntent.getActivity(
            this, 1, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val builder = NotificationCompat.Builder(this, channelId)
            .setContentIntent(contentIntent)
            .setSmallIcon(R.drawable.ic_notification)
            .setStyle(style)
            .setBubbleMetadata(bubbleData)
            .setShortcutId(shortcutId)
            .addPerson(chatPartner)
            .setCategory(NotificationCompat.CATEGORY_MESSAGE)
            // 【点睛之笔】：配合冷却机制
            // 如果 shouldAlert 为 true (不在冷却期)，这里设为 false，气泡会像平时一样直接弹在屏幕侧边
            // 如果 shouldAlert 为 false (在 10 分钟冷却期内)，这里设为 true。此时它只会在下拉通知栏里悄悄更新文字内容，绝对不弹气泡打扰你，除非你自己拉下通知栏点击它！
            .setOnlyAlertOnce(!shouldAlert)

        try {
            NotificationManagerCompat.from(this).notify(1001, builder.build())
        } catch (e: SecurityException) {
            e.printStackTrace()
        }
    }
}