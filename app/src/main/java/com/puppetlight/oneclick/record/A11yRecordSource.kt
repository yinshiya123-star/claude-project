package com.puppetlight.oneclick.record

import android.graphics.Rect
import android.view.accessibility.AccessibilityEvent
import com.puppetlight.oneclick.engine.A11yDevice
import com.puppetlight.oneclick.engine.Box

/** 无障碍录制来源：把点击/长按/输入事件转成录制步骤。 */
class A11yRecordSource(private val device: A11yDevice, private val recorder: Recorder) {

    /** 坐标点击/拖动录制后会替用户重放手势，重放产生的点击事件不要再记录一次。 */
    @Volatile private var suppressUntil = 0L

    fun suppress(ms: Long) {
        suppressUntil = System.currentTimeMillis() + ms
    }

    fun onEvent(e: AccessibilityEvent) {
        if (recorder.paused || System.currentTimeMillis() < suppressUntil) return
        val pkg = e.packageName?.toString() ?: return
        if (pkg in recorder.ignored) return
        val type = e.eventType
        if (type != AccessibilityEvent.TYPE_VIEW_CLICKED && type != AccessibilityEvent.TYPE_VIEW_LONG_CLICKED &&
            type != AccessibilityEvent.TYPE_VIEW_TEXT_CHANGED
        ) return
        val src = e.source ?: return
        val r = Rect().also { src.getBoundsInScreen(it) }
        val box = Box(r.left, r.top, r.right, r.bottom)
        val roots = device.snapshotNow()
        // 在完整控件树里找到同一个控件（这样才能算出「第几个」和子控件文字）
        val node = roots.asSequence().flatMap { it.walk() }
            .firstOrNull { it.bounds == box && it.viewId == src.viewIdResourceName && it.className == src.className?.toString() }
            ?: device.convert(src)
        when (type) {
            AccessibilityEvent.TYPE_VIEW_CLICKED -> recorder.recordClick(pkg, node, roots, long = false)
            AccessibilityEvent.TYPE_VIEW_LONG_CLICKED -> recorder.recordClick(pkg, node, roots, long = true)
            else -> recorder.recordText(pkg, node, roots, e.text.joinToString(""))
        }
    }
}
