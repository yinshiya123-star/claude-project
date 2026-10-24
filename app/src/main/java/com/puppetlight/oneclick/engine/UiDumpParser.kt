package com.puppetlight.oneclick.engine

/**
 * 解析 `uiautomator dump` 的 XML 为 [UiNode] 树。
 * 用简单的扫描器而不是 XmlPullParser，方便在 JVM 单元测试中运行。
 */
object UiDumpParser {

    private val TAG = Regex("""<(/?)(node|hierarchy)\b([^>]*?)(/?)>""")
    private val ATTR = Regex("""([\w:-]+)="([^"]*)"""")
    private val BOUNDS = Regex("""\[(-?\d+),(-?\d+)]\[(-?\d+),(-?\d+)]""")

    fun parse(xml: String): List<UiNode> {
        val roots = mutableListOf<UiNode>()
        val stack = ArrayDeque<UiNode>()
        for (m in TAG.findAll(xml)) {
            val closing = m.groupValues[1] == "/"
            val tag = m.groupValues[2]
            val selfClosing = m.groupValues[4] == "/"
            if (tag != "node") continue
            if (closing) {
                stack.removeLastOrNull()
                continue
            }
            val node = toNode(m.groupValues[3])
            val parent = stack.lastOrNull()
            if (parent == null) roots += node else parent.add(node)
            if (!selfClosing) stack.addLast(node)
        }
        return roots
    }

    private fun toNode(attrs: String): UiNode {
        val a = HashMap<String, String>()
        for (m in ATTR.findAll(attrs)) a[m.groupValues[1]] = unescape(m.groupValues[2])
        val b = BOUNDS.find(a["bounds"].orEmpty())?.groupValues?.drop(1)?.map { it.toInt() }
        val cls = a["class"]
        fun bool(k: String) = a[k] == "true"
        return UiNode(
            text = a["text"]?.ifEmpty { null },
            desc = a["content-desc"]?.ifEmpty { null },
            viewId = a["resource-id"]?.ifEmpty { null },
            className = cls,
            pkg = a["package"],
            bounds = if (b != null) Box(b[0], b[1], b[2], b[3]) else Box(0, 0, 0, 0),
            clickable = bool("clickable"),
            longClickable = bool("long-clickable"),
            editable = cls?.contains("EditText") == true || cls?.contains("AutoCompleteTextView") == true,
            scrollable = bool("scrollable"),
            focused = bool("focused"),
            visible = a["visible-to-user"]?.let { it == "true" } ?: true,
            hasRange = cls?.let { it.contains("SeekBar") || it.contains("Slider") } == true,
        )
    }

    private fun unescape(s: String): String {
        if (!s.contains('&')) return s
        return s.replace("&lt;", "<").replace("&gt;", ">").replace("&quot;", "\"")
            .replace("&apos;", "'").replace("&#10;", "\n").replace("&#13;", "\r").replace("&amp;", "&")
    }
}
