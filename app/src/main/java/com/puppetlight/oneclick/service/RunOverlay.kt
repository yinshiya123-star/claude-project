package com.puppetlight.oneclick.service

import android.content.Context
import android.view.View
import android.widget.Button
import android.widget.ProgressBar
import android.widget.TextView
import com.puppetlight.oneclick.R
import com.puppetlight.oneclick.engine.RunController
import com.puppetlight.oneclick.engine.RunState
import com.puppetlight.oneclick.engine.ScriptEngine
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/** 运行时悬浮窗：显示当前第几步、状态，可暂停/继续/停止，出错时可重试/跳过。 */
class RunOverlay(context: Context) : OverlayWindow(context, R.layout.overlay_run) {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val tvProgress: TextView = view.findViewById(R.id.tvProgress)
    private val tvPhase: TextView = view.findViewById(R.id.tvPhase)
    private val tvDesc: TextView = view.findViewById(R.id.tvDesc)
    private val tvDetail: TextView = view.findViewById(R.id.tvDetail)
    private val progress: ProgressBar = view.findViewById(R.id.progress)
    private val body: View = view.findViewById(R.id.body)
    private val btnPause: Button = view.findViewById(R.id.btnPause)
    private val btnStop: Button = view.findViewById(R.id.btnStop)
    private val rowError: View = view.findViewById(R.id.rowError)
    private val rowRun: View = view.findViewById(R.id.rowRun)
    private val btnClose: Button = view.findViewById(R.id.btnClose)

    init {
        makeDraggable(view.findViewById(R.id.header))
        view.findViewById<View>(R.id.btnMin).setOnClickListener {
            body.visibility = if (body.visibility == View.VISIBLE) View.GONE else View.VISIBLE
        }
        btnPause.setOnClickListener {
            val e = RunController.engine ?: return@setOnClickListener
            if (e.isPaused) e.resume() else e.pause()
        }
        btnStop.setOnClickListener { RunController.stop() }
        view.findViewById<View>(R.id.btnRetry).setOnClickListener { RunController.engine?.decide(ScriptEngine.Decision.RETRY) }
        view.findViewById<View>(R.id.btnSkip).setOnClickListener { RunController.engine?.decide(ScriptEngine.Decision.SKIP) }
        view.findViewById<View>(R.id.btnErrStop).setOnClickListener { RunController.stop() }
        btnClose.setOnClickListener { RunController.closeOverlay() }
    }

    override fun show() {
        super.show()
        scope.launch { RunState.state.collect { render(it) } }
    }

    override fun dismiss() {
        scope.cancel()
        super.dismiss()
    }

    private fun render(s: RunState.Snapshot) {
        tvProgress.text = if (s.total > 0) "第 ${s.index}/${s.total} 步" else "准备中…"
        progress.max = s.total.coerceAtLeast(1)
        progress.progress = s.index
        tvPhase.text = s.phase
        tvDesc.text = s.desc
        val err = s.error
        tvDetail.text = err ?: s.detail
        tvDetail.setTextColor(if (err != null) 0xFFFF6B6B.toInt() else 0xFFB8C0CC.toInt())
        rowError.visibility = if (s.status == RunState.Status.ERROR) View.VISIBLE else View.GONE
        val finished = s.status == RunState.Status.DONE || s.status == RunState.Status.STOPPED
        rowRun.visibility = if (s.status == RunState.Status.ERROR || finished) View.GONE else View.VISIBLE
        btnClose.visibility = if (finished) View.VISIBLE else View.GONE
        btnPause.text = if (s.status == RunState.Status.PAUSED) "继续" else "暂停"
        when (s.status) {
            RunState.Status.DONE -> { tvProgress.text = "✅ 制作完成"; tvDetail.text = "成品视频已保存到相册" }
            RunState.Status.STOPPED -> tvProgress.text = "⏹ 已停止"
            RunState.Status.ERROR -> body.visibility = View.VISIBLE
            else -> Unit
        }
    }
}
