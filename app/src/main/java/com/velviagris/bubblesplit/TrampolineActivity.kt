package com.velviagris.bubblesplit

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import com.velviagris.bubblesplit.util.AppUtils
import com.velviagris.bubblesplit.util.ShizukuUtils

class TrampolineActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val targetPackage = intent.getStringExtra("EXTRA_TARGET_PACKAGE")
        if (targetPackage != null) {
            val launchMode = AppUtils.getLaunchMode(this)
            var success = false

            if (launchMode == "freeform") {
                var widthRatio = AppUtils.getFreeformWidthRatio(this)
                var heightRatio = AppUtils.getFreeformHeightRatio(this)

                if (resources.configuration.orientation == android.content.res.Configuration.ORIENTATION_LANDSCAPE) {
                    val temp = widthRatio
                    widthRatio = heightRatio
                    heightRatio = temp
                }

                val displayMetrics = resources.displayMetrics
                val screenWidth = displayMetrics.widthPixels
                val screenHeight = displayMetrics.heightPixels

                val width = (screenWidth * widthRatio / 100).coerceIn(100, screenWidth)
                val height = (screenHeight * heightRatio / 100).coerceIn(100, screenHeight)
                val left = (screenWidth - width) / 2
                val top = (screenHeight - height) / 2
                val right = left + width
                val bottom = top + height

                success = ShizukuUtils.launchInFreeform(this, targetPackage, left, top, right, bottom)
            }

            if (!success) {
                // 回退到分屏模式启动 / Fallback to split screen mode.
                val launchIntent = packageManager.getLaunchIntentForPackage(targetPackage)?.apply {
                    addFlags(Intent.FLAG_ACTIVITY_LAUNCH_ADJACENT or Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                if (launchIntent != null) {
                    val config = resources.configuration
                    val isLandscape = config.orientation == android.content.res.Configuration.ORIENTATION_LANDSCAPE
                    val displayMetrics = resources.displayMetrics
                    val screenWidth = displayMetrics.widthPixels
                    val screenHeight = displayMetrics.heightPixels

                    val bounds = if (isLandscape) {
                        android.graphics.Rect(0, 0, screenWidth / 2, screenHeight)
                    } else {
                        android.graphics.Rect(0, 0, screenWidth, screenHeight / 2)
                    }

                    val options = android.app.ActivityOptions.makeBasic().setLaunchBounds(bounds)

                    try {
                        startActivity(launchIntent, options.toBundle())
                    } catch (e: Exception) {
                        e.printStackTrace()
                        try {
                            startActivity(launchIntent)
                        } catch (ex: Exception) {
                            ex.printStackTrace()
                        }
                    }
                }
            }
        }
        finish()
    }
}
