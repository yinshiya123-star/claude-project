package com.puppetlight.oneclick.script

import org.json.JSONArray
import org.json.JSONObject

/**
 * 控件定位描述。查找顺序：文字 / contentDescription / viewId（可组合，全部满足才算匹配），
 * 找不到时使用 x/y 坐标兜底（0~1 为屏幕比例；>1 按脚本 screen 参考分辨率换算）。
 */
data class Target(
    val texts: List<String> = emptyList(),
    val descs: List<String> = emptyList(),
    val viewIds: List<String> = emptyList(),
    val className: String? = null,
    val exact: Boolean = false,
    /** 多个匹配时取第几个；负数表示倒数。 */
    val index: Int = 0,
    /** 多个匹配时的排序方式："y" 从上到下（默认），"x" 从左到右。 */
    val sort: String = "y",
    /** 找不到时是否尝试滚动可滚动容器继续查找。 */
    val scroll: Boolean = false,
    /** 默认不匹配可编辑输入框（避免点到搜索框本身）。 */
    val includeEditable: Boolean = false,
    /** 强制使用手势点击控件中心，而不是 ACTION_CLICK。 */
    val gesture: Boolean = false,
    val x: Double? = null,
    val y: Double? = null,
) {
    val hasSelector: Boolean get() = texts.isNotEmpty() || descs.isNotEmpty() || viewIds.isNotEmpty() || className != null
    val hasPoint: Boolean get() = x != null && y != null

    fun describe(): String {
        val parts = mutableListOf<String>()
        if (texts.isNotEmpty()) parts += "文字${texts}"
        if (descs.isNotEmpty()) parts += "描述${descs}"
        if (viewIds.isNotEmpty()) parts += "id${viewIds}"
        className?.let { parts += "类型[$it]" }
        if (index != 0) parts += "第${index}个"
        if (hasPoint) parts += "坐标(${Expr.format(x!!)}, ${Expr.format(y!!)})"
        return parts.joinToString(" ")
    }

    companion object {
        /** 从 JSON 解析，并对所有字符串做变量替换、对坐标做表达式求值。 */
        fun from(o: JSONObject?, vars: Map<String, String>): Target? {
            if (o == null) return null
            fun list(key: String): List<String> = when (val v = o.opt(key)) {
                null, JSONObject.NULL -> emptyList()
                is JSONArray -> (0 until v.length()).map { Expr.substitute(v.get(it).toString(), vars) }
                else -> listOf(Expr.substitute(v.toString(), vars))
            }.filter { it.isNotEmpty() }

            fun num(key: String): Double? = when (val v = o.opt(key)) {
                null, JSONObject.NULL -> null
                is Number -> v.toDouble()
                else -> Expr.eval(v.toString(), vars)
            }
            return Target(
                texts = list("text"),
                descs = list("desc"),
                viewIds = list("viewId"),
                className = o.optString("className").ifEmpty { null }?.let { Expr.substitute(it, vars) },
                exact = o.optBoolean("exact", false),
                index = o.optInt("index", 0),
                sort = o.optString("sort", "y"),
                scroll = o.optBoolean("scroll", false),
                includeEditable = o.optBoolean("includeEditable", false),
                gesture = o.optBoolean("gesture", false),
                x = num("x"),
                y = num("y"),
            )
        }
    }
}
