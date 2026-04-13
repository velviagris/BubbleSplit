package com.velviagris.bubblesplit

import android.app.PendingIntent
import android.content.Intent
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import androidx.compose.ui.res.stringResource
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.Person
import androidx.core.content.pm.ShortcutInfoCompat
import androidx.core.content.pm.ShortcutManagerCompat
import androidx.core.graphics.drawable.IconCompat

class BubbleNotificationListenerService : NotificationListenerService() {

    override fun onNotificationPosted(sbn: StatusBarNotification) {
        val pkg = sbn.packageName
        if (pkg == packageName) return

        val selectedApps = AppUtils.getSelectedApps(this)
        if (selectedApps.contains(pkg)) {
            val appName = AppUtils.getAppName(this, pkg)

            // Write the target into memory and set a validity period of 10 seconds 将目标写入内存，设置 10 秒有效期
            AppUtils.setAutoLaunchTarget(pkg, 10000L)

            // Display bubble and send notification 发送气泡并弹出文字提示
            updateMainBubble(appName)
        }
    }

    private fun updateMainBubble(appName: String) {
        val channelId = AppUtils.BUBBLE_CHANNEL_ID
        val shortcutId = "bubble_split_shortcut"

        val icon = IconCompat.createWithResource(this, R.drawable.ic_launcher_foreground)
        val chatPartner = Person.Builder()
            .setName(getString(R.string.notif_partner_name))
            .setIcon(icon)
            .setImportant(true)
            .build()

        // Intent becomes completely pure and no longer takes any parameters, bypassing system cache bugs Intent 彻底变纯净，不再带任何参数，绕开系统缓存 Bug
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

        // Pop up prompt text
        val msgText = getString(R.string.notif_dynamic_msg);
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
            // Make sure a new text prompt pops up every time a notification comes 确保每次通知来时都能弹出新的文字提示
            .setOnlyAlertOnce(false)

        try {
            NotificationManagerCompat.from(this).notify(1001, builder.build())
        } catch (e: SecurityException) {
            e.printStackTrace()
        }
    }
}