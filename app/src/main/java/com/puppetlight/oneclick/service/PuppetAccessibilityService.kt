package com.puppetlight.oneclick.service

import android.accessibilityservice.AccessibilityService
import android.content.ComponentName
import android.content.Context
import android.graphics.Point
import android.os.Build
import android.provider.Settings
import android.view.accessibility.AccessibilityEvent
import com.puppetlight.oneclick.engine.AccessibilityCapturer
import com.puppetlight.oneclick.engine.ProjectionCapturer
import com.puppetlight.oneclick.engine.ProjectionHolder
import com.puppetlight.oneclick.engine.RunController
import com.puppetlight.oneclick.engine.Screen
import com.puppetlight.oneclick.engine.ScreenCapturer
import com.puppetlight.oneclick.record.RecordController

/**
 * 无障碍服务：无障碍模式下提供控件读取、手势、截图，并把事件转给录制模式。
 * 执行流程由 [RunController] 管理。
 */
class PuppetAccessibilityService : AccessibilityService() {

    @Volatile
    var currentPackage: String? = null
        private set

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        event ?: return
        if (event.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
            event.packageName?.toString()?.let { if (it != packageName) currentPackage = it }
        }
        RecordController.onA11yEvent(event)
    }

    override fun onInterrupt() {}

    override fun onDestroy() {
        if (instance === this) {
            RunController.stop()
            RecordController.stop(save = false)
            instance = null
        }
        super.onDestroy()
    }

    fun capturer(): ScreenCapturer? {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) return AccessibilityCapturer(this)
        if (ProjectionHolder.projection == null) return null
        val size = screenSize()
        return ProjectionCapturer(this, size.x, size.y, resources.displayMetrics.densityDpi)
    }

    fun screenSize(): Point = Screen.realSize(this)

    companion object {
        @Volatile
        var instance: PuppetAccessibilityService? = null
            private set

        fun isEnabled(context: Context): Boolean {
            val expected = ComponentName(context, PuppetAccessibilityService::class.java)
            val enabled = Settings.Secure.getString(
                context.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,
            ) ?: return false
            return enabled.split(':').any { ComponentName.unflattenFromString(it) == expected }
        }
    }
}
