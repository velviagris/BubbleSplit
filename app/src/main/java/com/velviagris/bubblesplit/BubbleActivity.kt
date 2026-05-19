package com.velviagris.bubblesplit

import android.app.ActivityOptions
import android.content.Intent
import android.content.res.Configuration
import android.graphics.Rect
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.graphics.drawable.toBitmap
import kotlinx.coroutines.flow.MutableStateFlow
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.lifecycleScope
import com.velviagris.bubblesplit.model.AppItem
import com.velviagris.bubblesplit.util.AppUtils
import kotlinx.coroutines.launch

class BubbleActivity : ComponentActivity() {

    // 监听自动拉起目标 / Use StateFlow to observe the auto-launch target.
    private val autoLaunchTargetFlow = MutableStateFlow<String?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        checkAndConsumeAutoLaunch()

        setContent {
            MaterialTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.surface
                ) {
                    val config = LocalConfiguration.current
                    val isLandscape = config.orientation == Configuration.ORIENTATION_LANDSCAPE

                    // 有目标包名时立即分屏 / Launch split screen as soon as a target package appears.
                    val targetToLaunch by autoLaunchTargetFlow.collectAsState()
                    LaunchedEffect(targetToLaunch) {
                        if (targetToLaunch != null) {
                            launchAppInHalfScreen(targetToLaunch!!, isLandscape)
                            // 分屏后重置状态 / Reset state after split-screen launch.
                            autoLaunchTargetFlow.value = null
                        }
                    }

                    AppSelectionContent(isLandscape)
                }
            }
        }
    }

    // 气泡后台再次打开 / Called when the background bubble is opened again.
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        checkAndConsumeAutoLaunch()
    }

    override fun onResume() {
        super.onResume()
        checkAndConsumeAutoLaunch()
    }

    // 核心检查逻辑 / Main auto-launch check.
    private fun checkAndConsumeAutoLaunch() {
        // 读取一次性目标，过期或已消费则为空 / Consume the one-shot target; expired or consumed targets return null.
        val target = AppUtils.consumeAutoLaunchTarget()
        if (target != null) {
            autoLaunchTargetFlow.value = target
        }
    }

    @Composable
    private fun AppSelectionContent(isLandscape: Boolean) {
        var filteredAppList by remember { mutableStateOf<List<AppItem>>(emptyList()) }
        var isLoading by remember { mutableStateOf(true) }

        val context = LocalContext.current
        val lifecycleOwner = androidx.lifecycle.compose.LocalLifecycleOwner.current

        // 刷新触发器 / Refresh trigger for app list reloads.
        var refreshTrigger by remember { mutableStateOf(0) }

        // 监听气泡展开 / Increment the trigger whenever the bubble resumes.
        DisposableEffect(lifecycleOwner) {
            val observer = LifecycleEventObserver { _, event ->
                if (event == Lifecycle.Event.ON_RESUME) {
                    refreshTrigger++
                }
            }
            lifecycleOwner.lifecycle.addObserver(observer)
            onDispose {
                lifecycleOwner.lifecycle.removeObserver(observer)
            }
        }

        // 回到前台时刷新应用列表 / Reload selected apps whenever the bubble returns to foreground.
        LaunchedEffect(refreshTrigger) {
            isLoading = true
            val selectedPackages = AppUtils.getSelectedApps(context)
            filteredAppList = AppUtils.loadSelectedAppsOnly(context, selectedPackages)
            isLoading = false
        }

        if (isLoading) {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator()
            }
        } else if (filteredAppList.isEmpty()) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(32.dp),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = stringResource(R.string.bubble_empty_state),
                    textAlign = TextAlign.Center,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        } else {
            LazyVerticalGrid(
                columns = GridCells.Adaptive(minSize = 80.dp),
                contentPadding = PaddingValues(16.dp),
                modifier = Modifier.fillMaxSize()
            ) {
                items(filteredAppList) { app ->
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        modifier = Modifier
                            .clickable { launchAppInHalfScreen(app.packageName, isLandscape) }
                            .padding(8.dp)
                    ) {
                        Image(
                            bitmap = app.icon.toBitmap(120, 120).asImageBitmap(),
                            contentDescription = app.name,
                            modifier = Modifier.size(48.dp)
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = app.name,
                            style = MaterialTheme.typography.labelMedium,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
            }
        }
    }

    private fun launchAppInHalfScreen(packageName: String, isLandscape: Boolean) {
        // 使用生命周期协程 / Launch work in the Activity lifecycle scope.
        lifecycleScope.launch {

            // 前台冲突时退出 / Abort if the target app is already foreground.
            if (AppUtils.hasUsageStatsPermission(this@BubbleActivity)) {
                if (AppUtils.isAppInForeground(this@BubbleActivity, packageName)) {
                    Toast.makeText(this@BubbleActivity, getString(R.string.toast_cannot_split), Toast.LENGTH_SHORT).show()
                    moveTaskToBack(true)
                    return@launch
                }
            }

            val launchIntent = packageManager.getLaunchIntentForPackage(packageName) ?: return@launch

            launchIntent.addFlags(
                Intent.FLAG_ACTIVITY_NEW_TASK or
                        Intent.FLAG_ACTIVITY_LAUNCH_ADJACENT
            )

            val displayMetrics = resources.displayMetrics
            val screenWidth = displayMetrics.widthPixels
            val screenHeight = displayMetrics.heightPixels

            val bounds = if (isLandscape) {
                Rect(0, 0, screenWidth / 2, screenHeight)
            } else {
                Rect(0, 0, screenWidth, screenHeight / 2)
            }

            val options = ActivityOptions.makeBasic().setLaunchBounds(bounds)

            try {
                startActivity(launchIntent, options.toBundle())
                moveTaskToBack(true)
            } catch (e: Exception) {
                e.printStackTrace()
                startActivity(launchIntent)
                moveTaskToBack(true)
            }
        }
    }
}
