package com.puppetlight.oneclick.engine

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** 运行状态（悬浮窗、主界面共同观察）。 */
object RunState {

    enum class Status { IDLE, RUNNING, PAUSED, ERROR, DONE, STOPPED }

    data class Snapshot(
        val status: Status = Status.IDLE,
        val index: Int = 0,
        val total: Int = 0,
        val phase: String = "",
        val desc: String = "",
        val detail: String = "",
        val error: String? = null,
    )

    private val _state = MutableStateFlow(Snapshot())
    val state: StateFlow<Snapshot> = _state

    private val logLines = ArrayList<String>()
    @Volatile private var logFile: File? = null
    private val fmt = SimpleDateFormat("HH:mm:ss", Locale.US)

    fun update(f: (Snapshot) -> Snapshot) {
        synchronized(this) { _state.value = f(_state.value) }
    }

    fun log(msg: String) {
        synchronized(logLines) {
            logLines += "${fmt.format(Date())}  $msg"
            if (logLines.size > 2000) logLines.removeAt(0)
            // 边运行边写文件：即使进程被系统杀掉，也能看到停在哪一步
            logFile?.let { f -> runCatching { f.appendText("${fmt.format(Date())}  $msg\n") } }
        }
        android.util.Log.i("PuppetLight", msg)
    }

    fun clearLog() = synchronized(logLines) { logLines.clear() }

    /** 开始运行：清空日志文件并记下「运行中」标记；正常结束时清除标记。 */
    fun beginRun(context: Context) {
        logFile = File(context.filesDir, "last_run.log").apply { runCatching { writeText("") } }
        prefs(context).edit().putBoolean("running", true).commit()
    }

    fun endRun(context: Context) {
        logFile = null
        prefs(context).edit().putBoolean("running", false).apply()
    }

    /** 上次运行是否没有正常结束（进程被系统杀掉）。读取后清除标记。 */
    fun consumeInterrupted(context: Context): Boolean {
        val p = prefs(context)
        val v = p.getBoolean("running", false)
        if (v) p.edit().putBoolean("running", false).apply()
        return v
    }

    private fun prefs(context: Context) = context.getSharedPreferences("run_state", Context.MODE_PRIVATE)

    fun logText(): String = synchronized(logLines) { logLines.joinToString("\n") }

    fun loadLastLog(context: Context): String =
        runCatching { File(context.filesDir, "last_run.log").readText() }.getOrDefault("").ifEmpty { logText() }
}
