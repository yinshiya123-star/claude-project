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
    private val fmt = SimpleDateFormat("HH:mm:ss", Locale.US)

    fun update(f: (Snapshot) -> Snapshot) {
        synchronized(this) { _state.value = f(_state.value) }
    }

    fun log(msg: String) {
        synchronized(logLines) {
            logLines += "${fmt.format(Date())}  $msg"
            if (logLines.size > 2000) logLines.removeAt(0)
        }
        android.util.Log.i("PuppetLight", msg)
    }

    fun clearLog() = synchronized(logLines) { logLines.clear() }

    fun logText(): String = synchronized(logLines) { logLines.joinToString("\n") }

    fun saveLog(context: Context) {
        runCatching { File(context.filesDir, "last_run.log").writeText(logText()) }
    }

    fun loadLastLog(context: Context): String =
        runCatching { File(context.filesDir, "last_run.log").readText() }.getOrDefault("").ifEmpty { logText() }
}
