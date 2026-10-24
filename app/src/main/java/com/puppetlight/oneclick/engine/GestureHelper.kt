package com.puppetlight.oneclick.engine

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Path
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlin.coroutines.resume

/**
 * 用 dispatchGesture 模拟点击、长按、滑动、拖动、双指缩放。
 * [passThrough] 在手势期间把悬浮窗设为「不可触摸」，避免手势落在悬浮窗上。
 */
class GestureHelper(
    private val service: AccessibilityService,
    private val screenW: Int,
    private val screenH: Int,
    private val passThrough: (Boolean) -> Unit,
) {

    private fun cx(x: Float) = x.coerceIn(1f, screenW - 2f)
    private fun cy(y: Float) = y.coerceIn(1f, screenH - 2f)

    private suspend fun dispatch(stroke: GestureDescription.StrokeDescription): Boolean =
        dispatch(GestureDescription.Builder().addStroke(stroke).build())

    private suspend fun dispatch(gesture: GestureDescription): Boolean {
        withContext(Dispatchers.Main) { passThrough(true) }
        try {
            return withContext(Dispatchers.Main) {
                suspendCancellableCoroutine { cont ->
                    val ok = service.dispatchGesture(gesture, object : AccessibilityService.GestureResultCallback() {
                        override fun onCompleted(g: GestureDescription?) {
                            if (cont.isActive) cont.resume(true)
                        }

                        override fun onCancelled(g: GestureDescription?) {
                            if (cont.isActive) cont.resume(false)
                        }
                    }, null)
                    if (!ok && cont.isActive) cont.resume(false)
                }
            }
        } finally {
            withContext(Dispatchers.Main) { passThrough(false) }
        }
    }

    suspend fun tap(x: Float, y: Float, durationMs: Long = 60): Boolean {
        val p = Path().apply { moveTo(cx(x), cy(y)) }
        return dispatch(GestureDescription.StrokeDescription(p, 0, durationMs))
    }

    suspend fun longPress(x: Float, y: Float, durationMs: Long = 900): Boolean = tap(x, y, durationMs)

    suspend fun swipe(x1: Float, y1: Float, x2: Float, y2: Float, durationMs: Long = 400): Boolean {
        val p = Path().apply { moveTo(cx(x1), cy(y1)); lineTo(cx(x2), cy(y2)) }
        return dispatch(GestureDescription.StrokeDescription(p, 0, durationMs.coerceAtLeast(1)))
    }

    /**
     * 按住 [holdMs] → 移动到终点（[durationMs]）→ 在终点停留 [holdEndMs] 后松手。
     * 停留可以消除惯性滑动，适合拖动图层、拖动滑块、精确拖动时间轴。
     */
    suspend fun drag(
        x1: Float, y1: Float, x2: Float, y2: Float,
        holdMs: Long = 600, durationMs: Long = 800, holdEndMs: Long = 300,
    ): Boolean {
        val sx = cx(x1); val sy = cy(y1); val ex = cx(x2); val ey = cy(y2)
        // 分段续接的手势（continueStroke）在部分机型上不稳定，失败时退回单段路径
        try {
            var stroke: GestureDescription.StrokeDescription? = null
            if (holdMs > 0) {
                val p = Path().apply { moveTo(sx, sy); lineTo(sx + 1, sy) }
                stroke = GestureDescription.StrokeDescription(p, 0, holdMs, true)
                if (!dispatch(stroke)) throw IllegalStateException("hold cancelled")
            }
            val startX = if (holdMs > 0) sx + 1 else sx
            val move = Path().apply { moveTo(startX, sy); lineTo(ex, ey) }
            stroke = stroke?.continueStroke(move, 0, durationMs, holdEndMs > 0)
                ?: GestureDescription.StrokeDescription(move, 0, durationMs, holdEndMs > 0)
            if (!dispatch(stroke)) throw IllegalStateException("move cancelled")
            if (holdEndMs > 0) {
                val end = Path().apply { moveTo(ex, ey); lineTo(ex, ey + 1) }
                if (!dispatch(stroke.continueStroke(end, 0, holdEndMs, false))) throw IllegalStateException("end cancelled")
            }
            return true
        } catch (e: Exception) {
            val p = Path().apply { moveTo(sx, sy); lineTo(sx + 1, sy); lineTo(ex, ey) }
            return dispatch(GestureDescription.StrokeDescription(p, 0, holdMs + durationMs + holdEndMs))
        }
    }

    /** 双指缩放：两指从 [startSpan] 间距变为 [endSpan]（水平方向）。 */
    suspend fun pinch(cx0: Float, cy0: Float, startSpan: Float, endSpan: Float, durationMs: Long = 600): Boolean {
        val y = cy(cy0)
        val p1 = Path().apply { moveTo(cx(cx0 - startSpan / 2), y); lineTo(cx(cx0 - endSpan / 2), y) }
        val p2 = Path().apply { moveTo(cx(cx0 + startSpan / 2), y); lineTo(cx(cx0 + endSpan / 2), y) }
        val g = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(p1, 0, durationMs))
            .addStroke(GestureDescription.StrokeDescription(p2, 0, durationMs))
            .build()
        return dispatch(g)
    }
}
