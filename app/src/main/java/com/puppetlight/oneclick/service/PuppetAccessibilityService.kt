package com.puppetlight.oneclick.service

import android.accessibilityservice.AccessibilityService
import android.content.ComponentName
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Point
import android.os.Build
import android.provider.Settings
import android.util.DisplayMetrics
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.widget.Toast
import com.puppetlight.oneclick.engine.AccessibilityCapturer
import com.puppetlight.oneclick.engine.ProjectionCapturer
import com.puppetlight.oneclick.engine.ProjectionHolder
import com.puppetlight.oneclick.engine.RunState
import com.puppetlight.oneclick.engine.ScreenCapturer
import com.puppetlight.oneclick.engine.ScriptEngine
import com.puppetlight.oneclick.record.Recorder
import com.puppetlight.oneclick.record.RecordOverlay
import com.puppetlight.oneclick.script.Script
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 无障碍服务：负责执行脚本（操作 NV MAX / Motion Ninja）、显示悬浮窗、录制模式。
 */
class PuppetAccessibilityService : AccessibilityService() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var runJob: Job? = null
    var engine: ScriptEngine? = null
        private set
    var overlay: RunOverlay? = null
        private set
    private var recorder: Recorder? = null
    private var recordOverlay: RecordOverlay? = null

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
        recorder?.onEvent(event)
    }

    override fun onInterrupt() {}

    override fun onDestroy() {
        stopRun()
        stopRecording(save = false)
        scope.cancel()
        if (instance === this) instance = null
        super.onDestroy()
    }

    // ------------------------------------------------------------ 运行

    val isRunning: Boolean get() = runJob?.isActive == true

    fun startRun(script: Script, vars: Map<String, String>): Boolean {
        if (isRunning) return false
        stopRecording(save = false)
        RunState.clearLog()
        RunState.beginRun(this)
        RunState.update { RunState.Snapshot(status = RunState.Status.RUNNING) }
        overlay?.dismiss()
        val ov = RunOverlay(this)
        try {
            ov.show()
        } catch (ex: Exception) {
            Toast.makeText(this, "无法显示悬浮窗，请开启悬浮窗权限：${ex.message}", Toast.LENGTH_LONG).show()
            RunState.endRun(this)
            return false
        }
        overlay = ov
        val e = ScriptEngine(this, script, vars)
        engine = e
        runJob = scope.launch {
            var success = false
            try {
                success = e.run()
                if (!success) {
                    RunState.update { it.copy(status = RunState.Status.STOPPED, detail = "已停止") }
                }
            } catch (ex: kotlinx.coroutines.CancellationException) {
                RunState.update { it.copy(status = RunState.Status.STOPPED, detail = "已停止", error = null) }
                RunState.log("已停止")
            } catch (ex: Exception) {
                RunState.update { it.copy(status = RunState.Status.STOPPED, error = "异常：${ex.message}") }
                RunState.log("异常终止：$ex")
            } finally {
                withContext(NonCancellable) {
                    RunState.endRun(this@PuppetAccessibilityService)
                    ProjectionService.stop(this@PuppetAccessibilityService)
                    KeepAliveService.stop(this@PuppetAccessibilityService)
                    engine = null
                    // 悬浮窗保留，显示结果/停止原因，由用户点「关闭」
                }
            }
        }
        return true
    }

    fun stopRun() {
        engine?.decide(ScriptEngine.Decision.STOP)
        runJob?.cancel()
    }

    fun closeOverlay() {
        if (isRunning) return
        overlay?.dismiss()
        overlay = null
    }

    fun bringOverlayToFront() {
        scope.launch(Dispatchers.Main) { overlay?.bringToFront() }
    }

    // ------------------------------------------------------------ 截图

    fun capturer(): ScreenCapturer? {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) return AccessibilityCapturer(this)
        if (ProjectionHolder.projection == null) return null
        val size = screenSize()
        return ProjectionCapturer(this, size.x, size.y, resources.displayMetrics.densityDpi)
    }

    /** 截图时暂时隐藏悬浮窗，避免把悬浮窗截进去。 */
    suspend fun captureWithoutOverlay(c: ScreenCapturer): Bitmap? {
        withContext(Dispatchers.Main) { overlay?.setHidden(true) }
        try {
            delay(250)
            return c.capture()
        } finally {
            withContext(Dispatchers.Main) { overlay?.setHidden(false) }
        }
    }

    fun screenSize(): Point {
        val wm = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val b = wm.maximumWindowMetrics.bounds
            Point(b.width(), b.height())
        } else {
            val m = DisplayMetrics()
            @Suppress("DEPRECATION") wm.defaultDisplay.getRealMetrics(m)
            Point(m.widthPixels, m.heightPixels)
        }
    }

    // ------------------------------------------------------------ 录制

    val isRecording: Boolean get() = recorder != null

    fun startRecording(packageVars: Map<String, String>) {
        if (isRunning || recorder != null) return
        val r = Recorder(this, packageVars)
        recorder = r
        recordOverlay = RecordOverlay(this, r).also { it.show() }
    }

    /** 结束录制；save=true 时保存为脚本文件并返回文件名。 */
    fun stopRecording(save: Boolean): String? {
        val r = recorder ?: return null
        recorder = null
        recordOverlay?.dismiss()
        recordOverlay = null
        if (!save) return null
        return try {
            val id = r.save()
            Toast.makeText(this, "录制已保存：$id（在「脚本管理」中查看）", Toast.LENGTH_LONG).show()
            id
        } catch (e: Exception) {
            Toast.makeText(this, "保存失败：${e.message}", Toast.LENGTH_LONG).show()
            null
        }
    }

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
