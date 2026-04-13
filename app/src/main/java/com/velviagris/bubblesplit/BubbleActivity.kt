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
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.compose.LocalLifecycleOwner

class BubbleActivity : ComponentActivity() {

    // Use StateFlow to monitor the pull-up action 用 StateFlow 监听拉起动作
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

                    // If there is a target package name, split the screen immediately. 监听如果有目标包名，立刻分屏
                    val targetToLaunch by autoLaunchTargetFlow.collectAsState()
                    LaunchedEffect(targetToLaunch) {
                        if (targetToLaunch != null) {
                            launchAppInHalfScreen(targetToLaunch!!, isLandscape)
                            // Reset the bubble status immediately after split screen is completed 分屏完毕立刻重置状态
                            autoLaunchTargetFlow.value = null
                        }
                    }

                    AppSelectionContent(isLandscape)
                }
            }
        }
    }

    // Triggered when the bubble has been in the background and is clicked again to open 当气泡一直在后台，被再次点击打开时触发
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        checkAndConsumeAutoLaunch()
    }

    override fun onResume() {
        super.onResume()
        checkAndConsumeAutoLaunch()
    }

    // Main check logic 核心检查逻辑
    private fun checkAndConsumeAutoLaunch() {
        // Reading from memory: If 10 seconds have passed or it has been read, null will be returned here.
        // 去内存中读取：如果过了 10 秒或者已经被读取过了，这里就会返回 null
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

        // Add a refresh trigger 增加一个刷新触发器
        var refreshTrigger by remember { mutableStateOf(0) }

        // Listen for bubble expansion (ON_RESUME) and let the trigger +1 whenever it expands 监听气泡展开 (ON_RESUME)，只要展开就让触发器 +1
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

        // Whenever the trigger changes (i.e. every time the bubble comes back to the foreground), the latest data is re-read and filtered
        // 只要触发器变化（也就是每次气泡回到前台），就重新读取并过滤最新数据
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
        // Detect permissions and block foreground apps 检测权限并拦截前台应用
        if (AppUtils.hasUsageStatsPermission(this)) {
            if (AppUtils.isAppInForeground(this, packageName)) {
                Toast.makeText(this, getString(R.string.toast_cannot_split), Toast.LENGTH_SHORT).show()
                // Still perform the action of closing the bubble without blocking the user's view 依然执行收起气泡的动作，不挡用户视线
                moveTaskToBack(true)
                return
            }
        }

        val launchIntent = packageManager.getLaunchIntentForPackage(packageName) ?: return

        launchIntent.addFlags(
            Intent.FLAG_ACTIVITY_NEW_TASK or
                    Intent.FLAG_ACTIVITY_LAUNCH_ADJACENT or
                    Intent.FLAG_ACTIVITY_MULTIPLE_TASK
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
            // Force the system to collapse the current bubble into a floating icon 强制系统将当前气泡折叠为悬浮图标
            moveTaskToBack(true)
        } catch (e: Exception) {
            e.printStackTrace()
            startActivity(launchIntent)
            // Don’t forget to collapse the bubble when downgrading starts 降级启动时也别忘了折叠气泡
            moveTaskToBack(true)
        }
    }
}