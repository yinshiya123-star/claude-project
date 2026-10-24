package com.puppetlight.oneclick.record

import android.annotation.SuppressLint
import android.graphics.PixelFormat
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.FrameLayout
import android.widget.TextView
import com.puppetlight.oneclick.R
import com.puppetlight.oneclick.engine.GestureHelper
import com.puppetlight.oneclick.service.OverlayWindow
import com.puppetlight.oneclick.service.PuppetAccessibilityService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.hypot

/**
 * 录制面板悬浮窗。
 * 普通控件的点击会被自动记录；预览画面/时间轴这类自绘区域没有无障碍信息，
 * 需先点「坐标点击」或「拖动」，再在屏幕上操作一次，App 记录坐标后会替你把这次操作执行到底层 App。
 */
class RecordOverlay(
    private val service: PuppetAccessibilityService,
    private val recorder: Recorder,
) : OverlayWindow(service, R.layout.overlay_record) {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val tvCount: TextView = view.findViewById(R.id.tvCount)
    private val tvLast: TextView = view.findViewById(R.id.tvLast)
    private val btnPause: Button = view.findViewById(R.id.btnRecPause)
    private var captureLayer: View? = null
    private val size = service.screenSize()
    private val gestures = GestureHelper(service, size.x, size.y) { setPassThrough(it) }

    init {
        makeDraggable(view.findViewById(R.id.recHeader))
        view.findViewById<View>(R.id.btnRecTap).setOnClickListener { startCapture(drag = false) }
        view.findViewById<View>(R.id.btnRecDrag).setOnClickListener { startCapture(drag = true) }
        view.findViewById<View>(R.id.btnRecWait).setOnClickListener { recorder.addWait(2000) }
        view.findViewById<View>(R.id.btnRecUndo).setOnClickListener { recorder.undo() }
        btnPause.setOnClickListener {
            recorder.paused = !recorder.paused
            render()
        }
        view.findViewById<View>(R.id.btnRecSave).setOnClickListener { service.stopRecording(save = true) }
        view.findViewById<View>(R.id.btnRecCancel).setOnClickListener { service.stopRecording(save = false) }
        recorder.onChanged = { scope.launch { render() } }
        render()
    }

    private fun render() {
        tvCount.text = if (recorder.paused) "⏸ 录制已暂停 · ${recorder.count} 步" else "● 录制中 · ${recorder.count} 步"
        tvLast.text = recorder.lastDesc.ifEmpty { "去 NV MAX / Motion Ninja 操作一遍吧" }
        btnPause.text = if (recorder.paused) "继续" else "暂停"
    }

    override fun dismiss() {
        removeCapture()
        scope.cancel()
        super.dismiss()
    }

    private fun removeCapture() {
        captureLayer?.let { runCatching { wm.removeView(it) } }
        captureLayer = null
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun startCapture(drag: Boolean) {
        if (captureLayer != null) return
        val layer = FrameLayout(service).apply {
            setBackgroundColor(0x33FF9800)
            addView(TextView(service).apply {
                text = if (drag) "按住要拖动的位置，拖到目标处再松手" else "点击要记录的位置（长按 = 记录长按）"
                setTextColor(0xFFFFFFFF.toInt())
                setBackgroundColor(0xCC000000.toInt())
                setPadding(dp(12), dp(8), dp(12), dp(8))
            }, FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT,
                Gravity.CENTER_HORIZONTAL or Gravity.TOP,
            ).apply { topMargin = dp(48) })
        }
        var downX = 0f; var downY = 0f; var downT = 0L; var moveStartT = 0L
        layer.setOnTouchListener { _, e ->
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downX = e.rawX; downY = e.rawY; downT = e.eventTime; moveStartT = 0L
                }
                MotionEvent.ACTION_MOVE -> {
                    if (moveStartT == 0L && hypot(e.rawX - downX, e.rawY - downY) > dp(12)) moveStartT = e.eventTime
                }
                MotionEvent.ACTION_UP -> {
                    val upX = e.rawX; val upY = e.rawY
                    val dist = hypot(upX - downX, upY - downY)
                    val total = e.eventTime - downT
                    removeCapture()
                    scope.launch {
                        delay(150)
                        if (dist < dp(16)) {
                            val long = total > 500
                            recorder.addTap(downX, downY, long)
                            if (long) gestures.longPress(downX, downY) else gestures.tap(downX, downY)
                        } else {
                            val hold = if (moveStartT > 0) moveStartT - downT else 300
                            val dur = if (moveStartT > 0) e.eventTime - moveStartT else 600
                            recorder.addDrag(downX, downY, upX, upY, hold, dur)
                            gestures.drag(downX, downY, upX, upY, hold.coerceIn(100, 2000), dur.coerceIn(200, 3000), 200)
                        }
                    }
                }
            }
            true
        }
        val lp = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            params.type,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT,
        ).apply { gravity = Gravity.TOP or Gravity.START }
        wm.addView(layer, lp)
        captureLayer = layer
    }
}
