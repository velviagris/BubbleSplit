package com.velviagris.bubblesplit

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.velviagris.bubblesplit.ui.theme.BubbleSplitTheme

@Composable
fun SettingsScreen(onNavigateToSelector: () -> Unit, onSendNotification: () -> Unit) {
    var selectedCount by remember { mutableStateOf(0) }
    var hasListenerPermission by remember { mutableStateOf(false) }
    var hasUsagePermission by remember { mutableStateOf(false) }

    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val isPreview = androidx.compose.ui.platform.LocalInspectionMode.current

    if (!isPreview) {
        DisposableEffect(lifecycleOwner) {
            val observer = LifecycleEventObserver { _, event ->
                if (event == Lifecycle.Event.ON_RESUME) {
                    selectedCount = AppUtils.getSelectedApps(context).size
                    val enabledListeners = NotificationManagerCompat.getEnabledListenerPackages(context)
                    hasListenerPermission = enabledListeners.contains(context.packageName)
                    hasUsagePermission = AppUtils.hasUsageStatsPermission(context)
                }
            }
            lifecycleOwner.lifecycle.addObserver(observer)
            onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
        }
    }

    val permissionLauncher = if (!isPreview) {
        rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { isGranted ->
            if (isGranted) onSendNotification()
        }
    } else null

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 16.dp, bottom = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = stringResource(R.string.app_name),
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.Bold
            )
        }

        SettingCard(
            title = stringResource(R.string.setting_permission_title),
            subtitle = if (hasListenerPermission) stringResource(R.string.setting_permission_granted) else stringResource(R.string.setting_permission_denied),
            subtitleColor = if (hasListenerPermission) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error,
            onClick = {
                val intent = Intent(android.provider.Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)
                context.startActivity(intent)
            }
        )

        SettingCard(
            title = stringResource(R.string.setting_bubble_title),
            subtitle = stringResource(R.string.setting_bubble_desc),
            onClick = {
                if (ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED) {
                    onSendNotification()
                } else {
                    permissionLauncher?.launch(Manifest.permission.POST_NOTIFICATIONS)
                }
            }
        )

        SettingCard(
            title = stringResource(R.string.setting_prevent_dupsplit),
            subtitle = if (hasUsagePermission) stringResource(R.string.setting_usage_granted) else stringResource(R.string.setting_usage_denied),
            subtitleColor = if (hasUsagePermission) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error,
            onClick = {
                val intent = Intent(android.provider.Settings.ACTION_USAGE_ACCESS_SETTINGS)
                context.startActivity(intent)
            }
        )

        SettingCard(
            title = stringResource(R.string.setting_apps_title),
            subtitle = if (selectedCount > 0) stringResource(id = R.string.setting_apps_count, selectedCount) else stringResource(R.string.setting_apps_empty),
            onClick = onNavigateToSelector
        )
    }
}

@Composable
fun SettingCard(title: String, subtitle: String, subtitleColor: Color = MaterialTheme.colorScheme.onSurfaceVariant, onClick: () -> Unit) {
    Card(
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)),
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .clickable { onClick() }
    ) {
        ListItem(
            colors = ListItemDefaults.colors(containerColor = Color.Transparent),
            headlineContent = { Text(title, fontWeight = FontWeight.SemiBold) },
            supportingContent = { Text(subtitle, color = subtitleColor) },
            trailingContent = {
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
                    contentDescription = "Enter",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        )
    }
}

@Preview(showBackground = true, name = "SettingsScreen Preview")
@Composable
fun PreviewSettingsScreen() {
    BubbleSplitTheme {
        Surface {
            SettingsScreen(onNavigateToSelector = {}, onSendNotification = {})
        }
    }
}