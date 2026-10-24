package com.puppetlight.oneclick.engine

import com.puppetlight.oneclick.script.Target

/** 与平台无关的矩形（便于单元测试）。 */
data class Box(val left: Int, val top: Int, val right: Int, val bottom: Int) {
    val width get() = right - left
    val height get() = bottom - top
    val cx get() = (left + right) / 2f
    val cy get() = (top + bottom) / 2f
    val isEmpty get() = width <= 0 || height <= 0
    fun contains(o: Box) = left <= o.left && top <= o.top && right >= o.right && bottom >= o.bottom
    fun contains(x: Float, y: Float) = x >= left && x < right && y >= top && y < bottom
}

/**
 * 界面控件快照。无障碍模式由 AccessibilityNodeInfo 转换而来（[ref] 指向原节点），
 * Shizuku 模式由 uiautomator dump 的 XML 解析而来。
 */
class UiNode(
    val text: String?,
    val desc: String?,
    val viewId: String?,
    val className: String?,
    val pkg: String?,
    val bounds: Box,
    val clickable: Boolean = false,
    val longClickable: Boolean = false,
    val editable: Boolean = false,
    val scrollable: Boolean = false,
    val focused: Boolean = false,
    val visible: Boolean = true,
    val hasRange: Boolean = false,
    val ref: Any? = null,
) {
    var parent: UiNode? = null
    val children = mutableListOf<UiNode>()

    fun add(child: UiNode) {
        child.parent = this
        children += child
    }

    fun walk(): Sequence<UiNode> = sequence {
        yield(this@UiNode)
        for (c in children) yieldAll(c.walk())
    }

    override fun toString() = "UiNode(text=$text, desc=$desc, id=$viewId, cls=$className, $bounds)"
}

/** 控件匹配、排序、取第 N 个、找附近控件等通用逻辑（两种模式共用）。 */
object NodeQuery {

    fun matches(node: UiNode, t: Target): Boolean {
        if (!node.visible || node.bounds.isEmpty) return false
        if (!t.includeEditable && t.className == null && node.editable) return false
        fun m(value: String?, wanted: String): Boolean {
            if (value.isNullOrEmpty()) return false
            return if (t.exact) value.trim().equals(wanted.trim(), ignoreCase = true)
            else value.contains(wanted, ignoreCase = true)
        }
        if (t.texts.isNotEmpty()) {
            val ok = t.texts.any { m(node.text, it) } || (t.descs.isEmpty() && t.texts.any { m(node.desc, it) })
            if (!ok) return false
        }
        if (t.descs.isNotEmpty() && t.descs.none { m(node.desc, it) }) return false
        if (t.viewIds.isNotEmpty()) {
            val id = node.viewId ?: return false
            if (t.viewIds.none { id == it || id.endsWith(":id/$it") || id.endsWith("/$it") }) return false
        }
        if (t.className != null) {
            val cls = node.className ?: return false
            if (!cls.endsWith(t.className)) return false
        }
        return true
    }

    /** 返回第一个有匹配的窗口里的全部匹配控件（已排序、已去掉父子重复）。 */
    fun findAll(roots: List<UiNode>, t: Target): List<UiNode> {
        if (!t.hasSelector) return emptyList()
        for (root in roots) {
            val found = root.walk().filter { matches(it, t) }.toList()
            if (found.isNotEmpty()) {
                val sorted = if (t.sort == "x") found.sortedWith(compareBy({ it.bounds.left }, { it.bounds.top }))
                else found.sortedWith(compareBy({ it.bounds.top }, { it.bounds.left }))
                val out = mutableListOf<UiNode>()
                for (n in sorted) {
                    if (out.none { it.bounds.contains(n.bounds) && centerDistance(it.bounds, n.bounds) < 8 }) out += n
                }
                return out
            }
        }
        return emptyList()
    }

    fun pick(all: List<UiNode>, t: Target): UiNode? {
        if (all.isEmpty()) return null
        val idx = if (t.index < 0) all.size + t.index else t.index
        return all.getOrNull(idx)
    }

    fun findOne(roots: List<UiNode>, t: Target): UiNode? = pick(findAll(roots, t), t)

    fun indexOf(roots: List<UiNode>, node: UiNode, t: Target): Int =
        findAll(roots, t).indexOfFirst { it.bounds == node.bounds }.coerceAtLeast(0)

    fun clickableAncestor(node: UiNode): UiNode? {
        var n: UiNode? = node
        var depth = 0
        while (n != null && depth < 6) {
            if (n.clickable || n.longClickable) return n
            n = n.parent
            depth++
        }
        return null
    }

    /** 在标签附近（同一父容器内）查找满足条件的控件，取距离最近者。 */
    fun nearby(label: UiNode, pred: (UiNode) -> Boolean): UiNode? {
        val lb = label.bounds
        var anc = label.parent
        var level = 0
        while (anc != null && level < 3) {
            val c = anc.walk().filter { it !== label && it.visible && pred(it) }.toList()
            if (c.isNotEmpty()) {
                return c.minByOrNull {
                    kotlin.math.abs(it.bounds.cy - lb.cy) * 2 + kotlin.math.abs(it.bounds.cx - lb.cx) / 4
                }
            }
            anc = anc.parent
            level++
        }
        return null
    }

    /** 包含某点的最小（最内层）控件，用于 Shizuku 录制。 */
    fun deepestAt(roots: List<UiNode>, x: Float, y: Float): UiNode? {
        for (root in roots) {
            val hit = root.walk().filter { it.visible && it.bounds.contains(x, y) }
                .minByOrNull { it.bounds.width.toLong() * it.bounds.height }
            if (hit != null) return hit
        }
        return null
    }

    private fun centerDistance(a: Box, b: Box): Float =
        kotlin.math.abs(a.cx - b.cx) + kotlin.math.abs(a.cy - b.cy)
}
