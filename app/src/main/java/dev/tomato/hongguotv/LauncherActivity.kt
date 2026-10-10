package dev.tomato.hongguotv

import android.accessibilityservice.AccessibilityServiceInfo
import android.app.Activity
import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.provider.Settings
import android.view.Gravity
import android.view.accessibility.AccessibilityManager
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView

class LauncherActivity : Activity() {
    override fun onResume() {
        super.onResume()
        // Re-checked on every resume so returning from Settings after enabling goes straight to 红果.
        if (serviceEnabled()) {
            val launch = packageManager.getLeanbackLaunchIntentForPackage(TARGET_PACKAGE)
                ?: packageManager.getLaunchIntentForPackage(TARGET_PACKAGE)
            if (launch != null) {
                startActivity(launch)
                finish()
                return
            }
            showMessage("没有找到红果短剧（$TARGET_PACKAGE），请先安装。", withSettingsButton = false)
            return
        }
        showMessage(
            "遥控器支持还没有开启\n\n" +
                "设置 → 系统 → 无障碍 → ${getString(R.string.service_label)} → 开启\n\n" +
                "或者在电脑上运行：\n" +
                "adb shell settings put secure enabled_accessibility_services " +
                "$packageName/${RemoteService::class.java.name}\n" +
                "adb shell settings put secure accessibility_enabled 1\n\n" +
                "开启后：上下键切换视频；首页按左右键或返回键选择底部标签，确认键打开；确认键调出光标，光标下确认键点击，长按确认键收起光标。",
            withSettingsButton = true,
        )
    }

    private fun serviceEnabled(): Boolean =
        getSystemService(AccessibilityManager::class.java)
            .getEnabledAccessibilityServiceList(AccessibilityServiceInfo.FEEDBACK_ALL_MASK)
            .any { it.resolveInfo.serviceInfo.let { s -> s.packageName == packageName && s.name == RemoteService::class.java.name } }

    private fun showMessage(text: String, withSettingsButton: Boolean) {
        val pad = (48 * resources.displayMetrics.density).toInt()
        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(pad, pad, pad, pad)
            setBackgroundColor(Color.BLACK)
        }
        layout.addView(TextView(this).apply {
            this.text = text
            textSize = 22f
            setTextColor(Color.WHITE)
        })
        if (withSettingsButton) {
            layout.addView(Button(this).apply {
                this.text = "打开无障碍设置"
                setOnClickListener { startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) }
                requestFocus()
            })
        }
        setContentView(layout)
    }
}
