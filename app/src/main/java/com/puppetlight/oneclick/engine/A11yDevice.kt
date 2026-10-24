package com.puppetlight.oneclick.engine

import android.accessibilityservice.AccessibilityService
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Rect
import android.os.Build
import android.os.Bundle
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
import com.puppetlight.oneclick.service.PuppetAccessibilityService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

/** 无障碍服务实现。 */
class A11yDevice(private val service: PuppetAccessibilityService) : Device {

    override val name = "无障碍"
    override val screenW = service.screenSize().x
    override val screenH = service.screenSize().y
    private val gestures = GestureHelper(service, screenW, screenH) { RunController.overlay?.setPassThrough(it) }

    // ------------------------------------------------------------ 控件

    private fun roots(): List<AccessibilityNodeInfo> {
        val result = mutableListOf<AccessibilityNodeInfo>()
        try {
            service.windows
                .filter {
                    it.type != AccessibilityWindowInfo.TYPE_INPUT_METHOD &&
                        it.type != AccessibilityWindowInfo.TYPE_ACCESSIBILITY_OVERLAY
                }
                .sortedByDescending { it.layer }
                .forEach { w ->
                    val r = w.root ?: return@forEach
                    val pkg = r.packageName?.toString()
                    if (pkg == service.packageName || pkg == "com.android.systemui") return@forEach
                    result += r
                }
        } catch (_: Exception) {
        }
        if (result.isEmpty()) service.rootInActiveWindow?.let { result += it }
        return result
    }

    override suspend fun snapshot(): List<UiNode> = snapshotNow()

    fun snapshotNow(): List<UiNode> = roots().map { convert(it, 0) }

    fun convert(n: AccessibilityNodeInfo, depth: Int = 0): UiNode {
        val u = toUiNode(n)
        if (depth < 60) {
            for (i in 0 until n.childCount) n.getChild(i)?.let { u.add(convert(it, depth + 1)) }
        }
        return u
    }

    private fun ref(node: UiNode) = node.ref as? AccessibilityNodeInfo

    override suspend fun clickNode(node: UiNode, long: Boolean): Boolean {
        val action = if (long) AccessibilityNodeInfo.ACTION_LONG_CLICK else AccessibilityNodeInfo.ACTION_CLICK
        val target = NodeQuery.clickableAncestor(node)?.let(::ref)
        if (target != null && target.performAction(action)) return true
        return if (long) longPress(node.bounds.cx, node.bounds.cy) else tap(node.bounds.cx, node.bounds.cy)
    }

    private fun focusedInput(): AccessibilityNodeInfo? {
        for (r in roots()) r.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)?.let { if (it.isEditable) return it }
        return null
    }

    override suspend fun setText(node: UiNode?, text: String): Boolean {
        val n = (node?.let(::ref)) ?: focusedInput() ?: return false
        if (!n.isFocused) n.performAction(AccessibilityNodeInfo.ACTION_FOCUS)
        val args = Bundle().apply {
            putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text)
        }
        return n.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
    }

    override suspend fun setProgress(node: UiNode, value: Double, min: Double, max: Double): Boolean {
        val n = ref(node) ?: return false
        val ri = n.rangeInfo ?: return false
        val fraction = if (max > min) ((value - min) / (max - min)).coerceIn(0.0, 1.0) else 0.0
        val v = if (ri.max - ri.min == (max - min).toFloat()) value.toFloat()
        else ri.min + fraction.toFloat() * (ri.max - ri.min)
        val args = Bundle().apply { putFloat(AccessibilityNodeInfo.ACTION_ARGUMENT_PROGRESS_VALUE, v) }
        return n.performAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_SET_PROGRESS.id, args)
    }

    override suspend fun scrollForward(): Boolean {
        for (r in snapshot()) {
            val s = r.walk().filter { it.scrollable && it.visible }
                .maxByOrNull { it.bounds.width.toLong() * it.bounds.height } ?: continue
            return ref(s)?.performAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD) == true
        }
        return false
    }

    // ------------------------------------------------------------ 手势

    override suspend fun tap(x: Float, y: Float) = gestures.tap(x, y)
    override suspend fun longPress(x: Float, y: Float) = gestures.longPress(x, y)
    override suspend fun swipe(x1: Float, y1: Float, x2: Float, y2: Float, durationMs: Long) =
        gestures.swipe(x1, y1, x2, y2, durationMs)

    override suspend fun drag(x1: Float, y1: Float, x2: Float, y2: Float, holdMs: Long, durationMs: Long, holdEndMs: Long) =
        gestures.drag(x1, y1, x2, y2, holdMs, durationMs, holdEndMs)

    override suspend fun pinch(cx: Float, cy: Float, startSpan: Float, endSpan: Float, durationMs: Long) =
        gestures.pinch(cx, cy, startSpan, endSpan, durationMs)

    override suspend fun back() {
        service.performGlobalAction(AccessibilityService.GLOBAL_ACTION_BACK)
    }

    override suspend fun home() {
        service.performGlobalAction(AccessibilityService.GLOBAL_ACTION_HOME)
    }

    override suspend fun imeEnter(): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return false
        return focusedInput()?.performAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_IME_ENTER.id) == true
    }

    // ------------------------------------------------------------ App / 截图

    override suspend fun launch(pkg: String, fresh: Boolean): Boolean {
        val intent = service.packageManager.getLaunchIntentForPackage(pkg) ?: return false
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        if (fresh) intent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TASK)
        withContext(Dispatchers.Main) { service.startActivity(intent) }
        return true
    }

    override suspend fun isForeground(pkg: String): Boolean = roots().any { it.packageName?.toString() == pkg }

    override suspend fun screenshot(): Bitmap? {
        val c = service.capturer() ?: return null
        withContext(Dispatchers.Main) { RunController.overlay?.setHidden(true) }
        try {
            delay(250)
            return c.capture()
        } finally {
            withContext(Dispatchers.Main) { RunController.overlay?.setHidden(false) }
        }
    }

    companion object {
        fun toUiNode(n: AccessibilityNodeInfo): UiNode {
            val r = Rect().also { n.getBoundsInScreen(it) }
            return UiNode(
                text = n.text?.toString(),
                desc = n.contentDescription?.toString(),
                viewId = n.viewIdResourceName,
                className = n.className?.toString(),
                pkg = n.packageName?.toString(),
                bounds = Box(r.left, r.top, r.right, r.bottom),
                clickable = n.isClickable,
                longClickable = n.isLongClickable,
                editable = n.isEditable,
                scrollable = n.isScrollable,
                focused = n.isFocused,
                visible = n.isVisibleToUser,
                hasRange = n.rangeInfo != null,
                ref = n,
            )
        }
    }
}
