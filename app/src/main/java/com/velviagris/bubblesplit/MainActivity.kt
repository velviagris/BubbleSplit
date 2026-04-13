package com.velviagris.bubblesplit

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.Person
import androidx.core.content.pm.ShortcutInfoCompat
import androidx.core.content.pm.ShortcutManagerCompat
import androidx.core.graphics.drawable.IconCompat
import com.velviagris.bubblesplit.ui.theme.BubbleSplitTheme

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        enableEdgeToEdge()
        createNotificationChannel()

        setContent {
            BubbleSplitTheme {
                var currentTab by remember { mutableStateOf("settings") }
                var showSelector by remember { mutableStateOf(false) }

                BackHandler(enabled = showSelector) {
                    showSelector = false
                }

                Scaffold(
                    bottomBar = {
                        if (!showSelector) {
                            NavigationBar(
                                containerColor = MaterialTheme.colorScheme.surface,
                                tonalElevation = 0.dp
                            ) {
                                NavigationBarItem(
                                    icon = { Icon(Icons.Default.Settings, contentDescription = stringResource(R.string.tab_settings)) },
                                    label = { Text(stringResource(R.string.tab_settings)) },
                                    selected = currentTab == "settings",
                                    onClick = { currentTab = "settings" }
                                )
                                NavigationBarItem(
                                    icon = { Icon(Icons.Default.Info, contentDescription = stringResource(R.string.tab_about)) },
                                    label = { Text(stringResource(R.string.tab_about)) },
                                    selected = currentTab == "about",
                                    onClick = { currentTab = "about" }
                                )
                            }
                        }
                    }
                ) { innerPadding ->
                    Box(modifier = Modifier
                        .fillMaxSize()
                        .padding(innerPadding)) {

                        if (showSelector) {
                            AppSelectorScreen(onBack = { showSelector = false })
                        } else {
                            if (currentTab == "settings") {
                                SettingsScreen(
                                    onNavigateToSelector = { showSelector = true },
                                    onSendNotification = { sendBubbleNotification() }
                                )
                            } else {
                                AboutScreen()
                            }
                        }
                    }
                }
            }
        }
    }

    private fun createNotificationChannel() {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.deleteNotificationChannel("bubble_popup_channel")
        val channel = NotificationChannel(
            AppUtils.BUBBLE_CHANNEL_ID,
            getString(R.string.notif_channel_name),
            NotificationManager.IMPORTANCE_HIGH
        ).apply {
            setAllowBubbles(true)
            setSound(null, null)
            enableVibration(false)
        }
        nm.createNotificationChannel(channel)
    }

    private fun sendBubbleNotification() {
        val shortcutId = "bubble_split_shortcut"
        val target = Intent(this, BubbleActivity::class.java)
        val bubbleIntent = PendingIntent.getActivity(
            this, 0, target,
            PendingIntent.FLAG_MUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val icon = IconCompat.createWithResource(this, R.drawable.ic_launcher_foreground)
        val chatPartner = Person.Builder()
            .setName(getString(R.string.notif_partner_name))
            .setIcon(icon)
            .setImportant(true)
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

        val bubbleData = NotificationCompat.BubbleMetadata.Builder(bubbleIntent, icon)
            .setDesiredHeight(600)
            .setAutoExpandBubble(false)
            .build()

        val contentIntent = PendingIntent.getActivity(
            this, 1, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val style = NotificationCompat.MessagingStyle(chatPartner)
            .addMessage(getString(R.string.notif_main_msg), System.currentTimeMillis(), chatPartner)

        val builder = NotificationCompat.Builder(this, AppUtils.BUBBLE_CHANNEL_ID)
            .setContentIntent(contentIntent)
            .setSmallIcon(R.drawable.ic_notification)
            .setStyle(style)
            .setBubbleMetadata(bubbleData)
            .setShortcutId(shortcutId)
            .addPerson(chatPartner)
            .setCategory(NotificationCompat.CATEGORY_MESSAGE)

        try {
            NotificationManagerCompat.from(this).notify(1001, builder.build())
        } catch (e: SecurityException) {
            e.printStackTrace()
        }
    }
}