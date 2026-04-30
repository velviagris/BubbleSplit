package com.velviagris.bubblesplit

import android.app.AppOpsManager
import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.drawable.Drawable
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import androidx.core.graphics.drawable.toBitmap
import android.os.Process

// app data model
data class AppItem(
    val name: String,
    val packageName: String,
    val icon: Drawable
)

object AppUtils {
    // Globally unique silent bubble channel ID 全局唯一的静音气泡通道 ID
    const val BUBBLE_CHANNEL_ID = "bubble_popup_silent_v1"
    private const val PREFS_NAME = "bubble_prefs"
    private const val KEY_SELECTED_APPS = "selected_apps"
    private const val KEY_TAKE_OVER_NOTIFICATIONS = "take_over_notifications"

    // Timeliness-based bubble status management 基于时效性的气泡状态管理
    private var pendingTargetPkg: String? = null
    private var targetExpiryTime: Long = 0L

    // Read the saved application package name collection 读取已保存的应用包名集合
    fun getSelectedApps(context: Context): Set<String> {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        return prefs.getStringSet(KEY_SELECTED_APPS, emptySet()) ?: emptySet()
    }

    // Save the collection of application package names selected by the user 保存用户选中的应用包名集合
    fun saveSelectedApps(context: Context, packages: Set<String>) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit().putStringSet(KEY_SELECTED_APPS, packages).apply()
    }

    /*
    * Asynchronously obtain all applications in the device that can be launched through the Launcher
    * 异步获取设备内所有可通过 Launcher 启动的应用
    * */
    suspend fun loadInstalledApps(context: Context): List<AppItem> = withContext(Dispatchers.IO) {
        val pm = context.packageManager
        val intent = Intent(Intent.ACTION_MAIN, null).apply {
            addCategory(Intent.CATEGORY_LAUNCHER)
        }
        val resolveInfos = pm.queryIntentActivities(intent, PackageManager.MATCH_ALL)

        resolveInfos.map { info ->
            AppItem(
                name = info.loadLabel(pm).toString(),
                packageName = info.activityInfo.packageName,
                icon = info.loadIcon(pm)
            )
        }.sortedBy { it.name }
    }

    /*
    * 根据包名获取应用名称
    * Get application name based on package name
    * */
    fun getAppName(context: Context, packageName: String): String {
        val pm = context.packageManager
        return try {
            val info = pm.getApplicationInfo(packageName, 0)
            pm.getApplicationLabel(info).toString()
        } catch (e: Exception) {
            packageName
        }
    }

    /*
    * Get the application icon based on the package name and convert it to a Bitmap
    * 根据包名获取应用图标并转换为 Bitmap
    * */
    fun getAppIconBitmap(context: Context, packageName: String): android.graphics.Bitmap? {
        val pm = context.packageManager
        return try {
            val drawable = pm.getApplicationIcon(packageName)
            drawable.toBitmap(150, 150)
        } catch (e: Exception) {
            null
        }
    }

    fun setAutoLaunchTarget(pkg: String, validDurationMs: Long = 10000L) {
        pendingTargetPkg = pkg
        // Record expiration time (current time + 10 seconds) 记录过期时间（当前时间 + 10秒）
        targetExpiryTime = System.currentTimeMillis() + validDurationMs
    }

    fun consumeAutoLaunchTarget(): String? {
        val current = System.currentTimeMillis()
        val target = pendingTargetPkg
        // No matter whether it is expired or not, it will be cleared immediately after being read once (it will be destroyed after reading) 无论是否过期，只要读取一次就立刻清空（阅后即焚）
        pendingTargetPkg = null

        // If there is a target package name and the current time is still within the validity period, the package name will be returned. 检查：如果有目标包名，并且当前时间还在有效期内，才返回包名
        return if (target != null && current <= targetExpiryTime) {
            target
        } else {
            null
        }
    }

    /*
    * Load selected apps
    * 极速按需加载选中的应用
    * */
    suspend fun loadSelectedAppsOnly(context: Context, packageNames: Set<String>): List<AppItem> = withContext(Dispatchers.IO) {
        val pm = context.packageManager
        val result = mutableListOf<AppItem>()

        for (pkg in packageNames) {
            try {
                // Directly and accurately obtain the information of the specified package name, ignoring hundreds of other useless applications 直接精准获取指定包名的信息，忽略其余几百个无用应用
                val info = pm.getApplicationInfo(pkg, 0)
                result.add(
                    AppItem(
                        name = pm.getApplicationLabel(info).toString(),
                        packageName = pkg,
                        icon = pm.getApplicationIcon(info)
                    )
                )
            } catch (e: PackageManager.NameNotFoundException) {
                // If the user uninstalls the App from the system but it still exists in our records, an exception will be thrown and we will ignore it directly. 如果用户在系统里卸载了这个 App，但我们的记录里还有，就会抛出异常，我们直接忽略它
                e.printStackTrace()
            }
        }
        // Return in alphabetical order 按首字母排序返回
        result.sortedBy { it.name }
    }

    // Check if you have the "View app usage" permission 检查是否拥有“查看应用使用情况”的权限
    fun hasUsageStatsPermission(context: Context): Boolean {
        val appOps = context.getSystemService(Context.APP_OPS_SERVICE) as AppOpsManager
        val mode = appOps.checkOpNoThrow(
            AppOpsManager.OPSTR_GET_USAGE_STATS,
            Process.myUid(),
            context.packageName
        )
        return mode == AppOpsManager.MODE_ALLOWED
    }

    // Check whether the target application is the real foreground application currently (or before being covered by the bubble) 检查目标应用是否是当前（或被气泡覆盖前）的真实前台应用
    fun isAppInForeground(context: Context, targetPackage: String): Boolean {
        val usageStatsManager = context.getSystemService(Context.USAGE_STATS_SERVICE) as UsageStatsManager
        val endTime = System.currentTimeMillis()

        // Extend detection time
        // 将时间窗口大幅拉长到 2 小时 (1000L * 60 * 60 * 2)
        // 这样即使你在微信聊天页停留发呆了 1 个小时再点气泡，系统也能精准翻出 1 小时前微信进入前台的记录
        val startTime = endTime - 1000L * 60 * 60 * 2

        val usageEvents = usageStatsManager.queryEvents(startTime, endTime)
        val event = UsageEvents.Event()

        var currentApp: String? = null
        var previousApp: String? = null

        // 精准追踪应用切换“轨迹栈”
        while (usageEvents.hasNextEvent()) {
            usageEvents.getNextEvent(event)
            // ACTIVITY_RESUMED 代表有页面进入了前台
            if (event.eventType == UsageEvents.Event.ACTIVITY_RESUMED) {
                // 只有当“包名真的变了”时，才更新轨迹。
                // 这一步完美折叠了微信内部（主页 -> 聊天页 -> 朋友圈）的多次自我跳转，始终把它视为同一个 App
                if (currentApp != event.packageName) {
                    previousApp = currentApp
                    currentApp = event.packageName
                }
            }
        }

        val realForegroundApp = if (currentApp == context.packageName) previousApp else currentApp

        return realForegroundApp == targetPackage
    }

    // 读取“是否接管通知”状态，默认返回 false (不接管)
    fun isTakeOverNotifications(context: Context): Boolean {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        return prefs.getBoolean(KEY_TAKE_OVER_NOTIFICATIONS, false)
    }

    // 保存“是否接管通知”状态
    fun setTakeOverNotifications(context: Context, takeOver: Boolean) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit().putBoolean(KEY_TAKE_OVER_NOTIFICATIONS, takeOver).apply()
    }
}