package com.puppetlight.oneclick.record

import android.content.Intent
import android.content.pm.PackageManager
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import com.puppetlight.oneclick.data.ScriptRepository
import com.puppetlight.oneclick.engine.NodeFinder
import com.puppetlight.oneclick.script.Target
import com.puppetlight.oneclick.service.PuppetAccessibilityService
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.roundToInt

/**
 * 录制模式：记录用户的每一次点击（控件文字/描述/viewId + 坐标）、长按、输入，
 * 以及通过「坐标点击」「拖动」按钮手动记录的手势，最后生成步骤脚本 JSON。
 *
 * @param packageVars 包名 → 变量名（例如 NV MAX 的包名 → "nvmaxPackage"），生成的脚本里用变量代替包名。
 */
class Recorder(
    private val service: PuppetAccessibilityService,
    private val packageVars: Map<String, String>,
) {
    private val steps = mutableListOf<JSONObject>()
    private val finder = NodeFinder(service)
    private var lastTime = System.currentTimeMillis()
    private var lastPkg: String? = null
    private val screen = service.screenSize()
    var paused = false
    var onChanged: (() -> Unit)? = null

    private val ignored: Set<String> = buildSet {
        add(service.packageName)
        add("com.android.systemui")
        service.packageManager.resolveActivity(
            Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME), PackageManager.MATCH_DEFAULT_ONLY,
        )?.activityInfo?.packageName?.let { add(it) }
    }

    val count: Int get() = steps.size
    val lastDesc: String get() = steps.lastOrNull()?.optString("desc") ?: ""

    /** 坐标点击/拖动录制后会替用户重放手势，重放产生的点击事件不要再记录一次。 */
    private var suppressUntil = 0L

    fun onEvent(e: AccessibilityEvent) {
        if (paused || System.currentTimeMillis() < suppressUntil) return
        val pkg = e.packageName?.toString() ?: return
        if (pkg in ignored) return
        when (e.eventType) {
            AccessibilityEvent.TYPE_VIEW_CLICKED -> e.source?.let { recordClick(pkg, it, long = false) }
            AccessibilityEvent.TYPE_VIEW_LONG_CLICKED -> e.source?.let { recordClick(pkg, it, long = true) }
            AccessibilityEvent.TYPE_VIEW_TEXT_CHANGED -> e.source?.let { recordText(pkg, it, e.text.joinToString("")) }
        }
    }

    private fun r4(v: Double) = (v * 10000).roundToInt() / 10000.0

    private fun ensureApp(pkg: String) {
        if (pkg == lastPkg) return
        lastPkg = pkg
        val v = packageVars[pkg]
        add(
            JSONObject()
                .put("action", "LAUNCH_APP")
                .put("desc", "打开 ${v ?: pkg}")
                .put("package", if (v != null) "\${$v}" else pkg)
        )
    }

    private fun add(step: JSONObject) {
        val now = System.currentTimeMillis()
        // 上一步之后实际等了多久，就作为上一步的等待时间（限制在 0.8~5 秒）
        steps.lastOrNull()?.let { prev ->
            if (!prev.has("waitMs")) prev.put("waitMs", (now - lastTime).coerceIn(800, 5000))
        }
        lastTime = now
        steps += step
        onChanged?.invoke()
    }

    private fun firstText(node: AccessibilityNodeInfo, depth: Int = 0): String? {
        node.text?.toString()?.takeIf { it.isNotBlank() }?.let { return it }
        if (depth >= 3) return null
        for (i in 0 until node.childCount) {
            node.getChild(i)?.let { c -> firstText(c, depth + 1)?.let { return it } }
        }
        return null
    }

    private fun targetFor(node: AccessibilityNodeInfo): JSONObject {
        val b = finder.bounds(node)
        val t = JSONObject()
        val text = firstText(node)?.trim()?.take(30)
        val desc = node.contentDescription?.toString()?.trim()?.take(40)
        val id = node.viewIdResourceName?.substringAfter(":id/")
        if (!text.isNullOrEmpty()) t.put("text", text)
        if (!desc.isNullOrEmpty()) t.put("desc", desc)
        if (!id.isNullOrEmpty()) t.put("viewId", id)
        val probe = Target.from(t, emptyMap())!!
        if (probe.hasSelector) {
            val idx = finder.indexOf(node, probe)
            if (idx > 0) t.put("index", idx)
        }
        t.put("x", r4(b.exactCenterX().toDouble() / screen.x))
        t.put("y", r4(b.exactCenterY().toDouble() / screen.y))
        return t
    }

    private fun recordClick(pkg: String, node: AccessibilityNodeInfo, long: Boolean) {
        ensureApp(pkg)
        val t = targetFor(node)
        val label = t.optString("text").ifEmpty { t.optString("desc") }.ifEmpty { t.optString("viewId") }
            .ifEmpty { "(${t.optDouble("x")}, ${t.optDouble("y")})" }
        add(
            JSONObject()
                .put("action", if (long) "LONG_CLICK" else "CLICK")
                .put("desc", "${if (long) "长按" else "点击"}「$label」")
                .put("target", t)
        )
    }

    private fun recordText(pkg: String, node: AccessibilityNodeInfo, text: String) {
        ensureApp(pkg)
        val t = targetFor(node).apply { remove("text"); put("className", "EditText") }
        val last = steps.lastOrNull()
        if (last != null && last.optString("action") == "SET_TEXT" &&
            last.optJSONObject("target")?.optString("viewId") == t.optString("viewId")
        ) {
            last.put("text", text).put("desc", "输入「$text」")
            onChanged?.invoke()
            return
        }
        add(JSONObject().put("action", "SET_TEXT").put("desc", "输入「$text」").put("target", t).put("text", text))
    }

    /** 坐标点击（用于没有无障碍信息的画布区域，例如预览画面、时间轴）。 */
    fun addTap(x: Float, y: Float, long: Boolean) {
        suppressUntil = System.currentTimeMillis() + 1500
        service.currentPackage?.takeIf { it !in ignored }?.let { ensureApp(it) }
        val t = JSONObject().put("x", r4(x.toDouble() / screen.x)).put("y", r4(y.toDouble() / screen.y))
        add(
            JSONObject()
                .put("action", if (long) "LONG_CLICK" else "CLICK")
                .put("desc", "${if (long) "长按" else "点击"}坐标 (${t.getDouble("x")}, ${t.getDouble("y")})")
                .put("target", t)
        )
    }

    fun addDrag(x1: Float, y1: Float, x2: Float, y2: Float, holdMs: Long, durationMs: Long) {
        suppressUntil = System.currentTimeMillis() + holdMs + durationMs + 1500
        service.currentPackage?.takeIf { it !in ignored }?.let { ensureApp(it) }
        val from = JSONObject().put("x", r4(x1.toDouble() / screen.x)).put("y", r4(y1.toDouble() / screen.y))
        val to = JSONObject().put("x", r4(x2.toDouble() / screen.x)).put("y", r4(y2.toDouble() / screen.y))
        add(
            JSONObject()
                .put("action", "DRAG")
                .put("desc", "拖动 (${from.getDouble("x")}, ${from.getDouble("y")}) → (${to.getDouble("x")}, ${to.getDouble("y")})")
                .put("from", from).put("to", to)
                .put("holdMs", holdMs.coerceIn(100, 2000))
                .put("durationMs", durationMs.coerceIn(200, 3000))
        )
    }

    fun addWait(ms: Long) = add(JSONObject().put("action", "WAIT").put("desc", "等待 ${ms / 1000.0} 秒").put("ms", ms))

    fun undo() {
        if (steps.isNotEmpty()) steps.removeAt(steps.size - 1)
        steps.lastOrNull()?.remove("waitMs")
        onChanged?.invoke()
    }

    fun toJson(): JSONObject {
        val name = "录制脚本 " + SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US).format(Date())
        val arr = JSONArray()
        steps.forEachIndexed { i, s ->
            val copy = JSONObject(s.toString())
            copy.put("id", "R${i + 1}")
            if (i == 0) copy.put("phase", "录制的步骤")
            arr.put(copy)
        }
        return JSONObject()
            .put("name", name)
            .put("version", 1)
            .put("description", "由录制模式自动生成。坐标为屏幕比例（0~1）。")
            .put("screen", JSONObject().put("width", screen.x).put("height", screen.y))
            .put("defaults", JSONObject().put("waitMs", 1200).put("retries", 3))
            .put("steps", arr)
    }

    fun save(): String {
        if (steps.isEmpty()) throw IllegalStateException("没有录制到任何步骤")
        val name = "recorded_" + SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date()) + ".json"
        return ScriptRepository(service).save(name, toJson().toString(2))
    }
}
