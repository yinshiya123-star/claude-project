package com.puppetlight.oneclick.engine

import android.accessibilityservice.AccessibilityService
import android.graphics.Rect
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
import com.puppetlight.oneclick.script.Target
import kotlinx.coroutines.delay

/** 在当前屏幕（除本 App 悬浮窗和输入法外的所有窗口）中查找控件。 */
class NodeFinder(private val service: AccessibilityService) {

    private val ownPackage = service.packageName

    /** 按窗口层级从上到下返回各窗口根节点（弹窗、菜单优先）。 */
    fun roots(): List<AccessibilityNodeInfo> {
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
                    if (pkg == ownPackage || pkg == "com.android.systemui") return@forEach
                    result += r
                }
        } catch (_: Exception) {
        }
        if (result.isEmpty()) service.rootInActiveWindow?.let { result += it }
        return result
    }

    fun bounds(node: AccessibilityNodeInfo): Rect = Rect().also { node.getBoundsInScreen(it) }

    fun matches(node: AccessibilityNodeInfo, t: Target): Boolean {
        if (!node.isVisibleToUser) return false
        if (!t.includeEditable && t.className == null && node.isEditable) return false
        val text = node.text?.toString()
        val desc = node.contentDescription?.toString()
        fun m(value: String?, wanted: String): Boolean {
            if (value.isNullOrEmpty()) return false
            return if (t.exact) value.trim().equals(wanted.trim(), ignoreCase = true)
            else value.contains(wanted, ignoreCase = true)
        }
        if (t.texts.isNotEmpty()) {
            val ok = t.texts.any { m(text, it) } || (t.descs.isEmpty() && t.texts.any { m(desc, it) })
            if (!ok) return false
        }
        if (t.descs.isNotEmpty() && t.descs.none { m(desc, it) }) return false
        if (t.viewIds.isNotEmpty()) {
            val id = node.viewIdResourceName ?: return false
            if (t.viewIds.none { id == it || id.endsWith(":id/$it") || id.endsWith("/$it") }) return false
        }
        if (t.className != null) {
            val cls = node.className?.toString() ?: return false
            if (!cls.endsWith(t.className)) return false
        }
        return true
    }

    /** 返回第一个有匹配的窗口里的全部匹配控件（已排序）。 */
    fun findAll(t: Target): List<AccessibilityNodeInfo> {
        if (!t.hasSelector) return emptyList()
        for (root in roots()) {
            val found = mutableListOf<AccessibilityNodeInfo>()
            collect(root, t, found, 0)
            if (found.isNotEmpty()) {
                val withBounds = found.map { it to bounds(it) }.filter { !it.second.isEmpty }
                val sorted = if (t.sort == "x") withBounds.sortedWith(compareBy({ it.second.left }, { it.second.top }))
                else withBounds.sortedWith(compareBy({ it.second.top }, { it.second.left }))
                return removeNested(sorted).map { it.first }
            }
        }
        return emptyList()
    }

    /** 同一个控件常常父子都匹配（父节点带 desc，子节点带 text），只保留外层一个。 */
    private fun removeNested(list: List<Pair<AccessibilityNodeInfo, Rect>>): List<Pair<AccessibilityNodeInfo, Rect>> {
        val out = mutableListOf<Pair<AccessibilityNodeInfo, Rect>>()
        for (item in list) {
            if (out.none { it.second.contains(item.second) && centerDistance(it.second, item.second) < 8 }) out += item
        }
        return out
    }

    private fun centerDistance(a: Rect, b: Rect): Int =
        kotlin.math.abs(a.centerX() - b.centerX()) + kotlin.math.abs(a.centerY() - b.centerY())

    private fun collect(node: AccessibilityNodeInfo, t: Target, out: MutableList<AccessibilityNodeInfo>, depth: Int) {
        if (depth > 60) return
        if (matches(node, t)) out += node
        for (i in 0 until node.childCount) {
            val c = node.getChild(i) ?: continue
            collect(c, t, out, depth + 1)
        }
    }

    fun findOne(t: Target): AccessibilityNodeInfo? {
        val all = findAll(t)
        if (all.isEmpty()) return null
        val idx = if (t.index < 0) all.size + t.index else t.index
        return all.getOrNull(idx)
    }

    /** 轮询查找，直到超时；[t].scroll 为 true 时会尝试滚动列表。 */
    suspend fun waitFind(t: Target, timeoutMs: Long): AccessibilityNodeInfo? {
        if (!t.hasSelector) return null
        val deadline = System.currentTimeMillis() + timeoutMs
        var scrolls = 0
        while (true) {
            findOne(t)?.let { return it }
            if (System.currentTimeMillis() >= deadline) {
                if (t.scroll && scrolls < 8 && scrollForward()) {
                    scrolls++
                    delay(500)
                    continue
                }
                return null
            }
            delay(250)
        }
    }

    suspend fun waitGone(t: Target, timeoutMs: Long): Boolean {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (findOne(t) == null) return true
            delay(250)
        }
        return false
    }

    fun clickableAncestor(node: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        var n: AccessibilityNodeInfo? = node
        var depth = 0
        while (n != null && depth < 6) {
            if (n.isClickable || n.isLongClickable) return n
            n = n.parent
            depth++
        }
        return null
    }

    fun focusedInput(): AccessibilityNodeInfo? {
        for (r in roots()) {
            r.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)?.let { if (it.isEditable) return it }
        }
        return null
    }

    fun firstEditable(): AccessibilityNodeInfo? {
        for (r in roots()) {
            val out = mutableListOf<AccessibilityNodeInfo>()
            collectWhere(r, out, 0) { it.isEditable && it.isVisibleToUser }
            if (out.isNotEmpty()) return out.first()
        }
        return null
    }

    /** 在标签附近（同一父容器内）查找满足条件的控件，取垂直距离最近者。 */
    fun nearby(label: AccessibilityNodeInfo, pred: (AccessibilityNodeInfo) -> Boolean): AccessibilityNodeInfo? {
        val lb = bounds(label)
        var anc: AccessibilityNodeInfo? = label.parent
        var level = 0
        while (anc != null && level < 3) {
            val out = mutableListOf<AccessibilityNodeInfo>()
            collectWhere(anc, out, 0) { it.isVisibleToUser && pred(it) }
            if (out.isNotEmpty()) {
                return out.minByOrNull {
                    val b = bounds(it)
                    kotlin.math.abs(b.centerY() - lb.centerY()) * 2 + kotlin.math.abs(b.centerX() - lb.centerX()) / 4
                }
            }
            anc = anc.parent
            level++
        }
        return null
    }

    private fun collectWhere(
        node: AccessibilityNodeInfo, out: MutableList<AccessibilityNodeInfo>, depth: Int,
        pred: (AccessibilityNodeInfo) -> Boolean,
    ) {
        if (depth > 40) return
        if (pred(node)) out += node
        for (i in 0 until node.childCount) node.getChild(i)?.let { collectWhere(it, out, depth + 1, pred) }
    }

    fun scrollForward(): Boolean {
        for (r in roots()) {
            val out = mutableListOf<AccessibilityNodeInfo>()
            collectWhere(r, out, 0) { it.isScrollable && it.isVisibleToUser }
            val s = out.maxByOrNull { bounds(it).height() * bounds(it).width() } ?: continue
            return s.performAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD)
        }
        return false
    }

    fun packageOnScreen(pkg: String): Boolean = roots().any { it.packageName?.toString() == pkg }

    /** 用于录制：生成能唯一定位此控件的「第几个」索引。 */
    fun indexOf(node: AccessibilityNodeInfo, t: Target): Int {
        val b = bounds(node)
        val all = findAll(t)
        val i = all.indexOfFirst { bounds(it) == b }
        return if (i < 0) 0 else i
    }
}
