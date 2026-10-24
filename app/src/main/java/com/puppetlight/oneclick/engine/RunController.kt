package com.puppetlight.oneclick.engine

import android.content.Context
import android.widget.Toast
import com.puppetlight.oneclick.script.Script
import com.puppetlight.oneclick.service.KeepAliveService
import com.puppetlight.oneclick.service.ProjectionService
import com.puppetlight.oneclick.service.RunOverlay
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 一次「一键制作」的运行管理：引擎、悬浮窗、生命周期。无障碍模式和 Shizuku 模式共用。
 * 所有方法在主线程调用。
 */
object RunController {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var job: Job? = null

    @Volatile
    var engine: ScriptEngine? = null
        private set

    @Volatile
    var overlay: RunOverlay? = null
        private set

    val isRunning: Boolean get() = job?.isActive == true

    /**
     * @param windowContext 用来显示悬浮窗的 Context：无障碍服务（无需悬浮窗权限）或 Application（需要悬浮窗权限）
     */
    fun start(windowContext: Context, device: Device, script: Script, vars: Map<String, String>): Boolean {
        if (isRunning) return false
        val app = windowContext.applicationContext
        RunState.clearLog()
        RunState.beginRun(app)
        RunState.update { RunState.Snapshot(status = RunState.Status.RUNNING) }
        overlay?.dismiss()
        val ov = RunOverlay(windowContext)
        try {
            ov.show()
        } catch (ex: Exception) {
            Toast.makeText(app, "无法显示悬浮窗，请开启悬浮窗权限：${ex.message}", Toast.LENGTH_LONG).show()
            RunState.endRun(app)
            return false
        }
        overlay = ov
        val e = ScriptEngine(windowContext, device, script, vars)
        engine = e
        job = scope.launch {
            try {
                if (!e.run()) RunState.update { it.copy(status = RunState.Status.STOPPED, detail = "已停止") }
            } catch (ex: kotlinx.coroutines.CancellationException) {
                RunState.update { it.copy(status = RunState.Status.STOPPED, detail = "已停止", error = null) }
                RunState.log("已停止")
            } catch (ex: Exception) {
                RunState.update { it.copy(status = RunState.Status.STOPPED, error = "异常：${ex.message}") }
                RunState.log("异常终止：$ex")
            } finally {
                withContext(NonCancellable) {
                    RunState.endRun(app)
                    ProjectionService.stop(app)
                    KeepAliveService.stop(app)
                    engine = null
                    // 悬浮窗保留，显示结果/停止原因，由用户点「关闭」
                }
            }
        }
        return true
    }

    fun stop() {
        engine?.decide(ScriptEngine.Decision.STOP)
        job?.cancel()
    }

    fun closeOverlay() {
        if (isRunning) return
        overlay?.dismiss()
        overlay = null
    }
}
