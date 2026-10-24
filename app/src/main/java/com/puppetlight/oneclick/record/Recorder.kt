package com.puppetlight.oneclick.record

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import com.puppetlight.oneclick.data.ScriptRepository
import com.puppetlight.oneclick.engine.NodeQuery
import com.puppetlight.oneclick.engine.UiNode
import com.puppetlight.oneclick.script.Target
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.roundToInt

/**
 * 录制模式核心：把用户的点击（控件文字/描述/viewId + 坐标）、长按、输入、拖动记录为步骤，
 * 最后生成脚本 JSON。输入来源可以是无障碍事件（[A11yRecordSource]）或 Shizuku 触摸事件（[ShizukuRecordSource]）。
 *
 * @param packageVars 包名 → 变量名（例如 NV MAX 的包名 → "nvmaxPackage"），生成的脚本里用变量代替包名。
 */
class Recorder(
    private val context: Context,
    private val screenW: Int,
    private val screenH: Int,
    private val packageVars: Map<String, String>,
) {
    private val steps = mutableListOf<JSONObject>()
    private var lastTime = System.currentTimeMillis()
    private var lastPkg: String? = null
    @Volatile var paused = false
    var onChanged: (() -> Unit)? = null

    /** 本 App、系统界面、桌面上的操作不录制。 */
    val ignored: Set<String> = buildSet {
        add(context.packageName)
        add("com.android.systemui")
        context.packageManager.resolveActivity(
            Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME), PackageManager.MATCH_DEFAULT_ONLY,
        )?.activityInfo?.packageName?.let { add(it) }
    }

    val count: Int get() = synchronized(steps) { steps.size }
    val lastDesc: String get() = synchronized(steps) { steps.lastOrNull()?.optString("desc") ?: "" }

    private fun r4(v: Double) = (v * 10000).roundToInt() / 10000.0

    fun ensureApp(pkg: String?) {
        if (pkg == null || pkg in ignored || pkg == lastPkg) return
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
        synchronized(steps) {
            val now = System.currentTimeMillis()
            // 上一步之后实际等了多久，就作为上一步的等待时间（限制在 0.8~5 秒）
            steps.lastOrNull()?.let { prev ->
                if (!prev.has("waitMs")) prev.put("waitMs", (now - lastTime).coerceIn(800, 5000))
            }
            lastTime = now
            steps += step
        }
        onChanged?.invoke()
    }

    private fun firstText(node: UiNode, depth: Int = 0): String? {
        node.text?.takeIf { it.isNotBlank() }?.let { return it }
        if (depth >= 3) return null
        for (c in node.children) firstText(c, depth + 1)?.let { return it }
        return null
    }

    /** 为控件生成定位描述（文字/描述/viewId/第几个 + 坐标兜底）。 */
    fun targetFor(node: UiNode, roots: List<UiNode>): JSONObject {
        val t = JSONObject()
        val text = firstText(node)?.trim()?.take(30)
        val desc = node.desc?.trim()?.take(40)
        val id = node.viewId?.substringAfter(":id/")
        if (!text.isNullOrEmpty()) t.put("text", text)
        if (!desc.isNullOrEmpty()) t.put("desc", desc)
        if (!id.isNullOrEmpty()) t.put("viewId", id)
        val probe = Target.from(t, emptyMap())!!
        if (probe.hasSelector) {
            val idx = NodeQuery.indexOf(roots, node, probe)
            if (idx > 0) t.put("index", idx)
        }
        t.put("x", r4(node.bounds.cx.toDouble() / screenW))
        t.put("y", r4(node.bounds.cy.toDouble() / screenH))
        return t
    }

    fun recordClick(pkg: String?, node: UiNode, roots: List<UiNode>, long: Boolean) {
        if (paused) return
        ensureApp(pkg)
        val t = targetFor(node, roots)
        val label = t.optString("text").ifEmpty { t.optString("desc") }.ifEmpty { t.optString("viewId") }
            .ifEmpty { "(${t.optDouble("x")}, ${t.optDouble("y")})" }
        add(
            JSONObject()
                .put("action", if (long) "LONG_CLICK" else "CLICK")
                .put("desc", "${if (long) "长按" else "点击"}「$label」")
                .put("target", t)
        )
    }

    fun recordText(pkg: String?, node: UiNode, roots: List<UiNode>, text: String) {
        if (paused) return
        ensureApp(pkg)
        val t = targetFor(node, roots).apply { remove("text"); remove("index"); put("className", "EditText") }
        synchronized(steps) {
            val last = steps.lastOrNull()
            if (last != null && last.optString("action") == "SET_TEXT" &&
                last.optJSONObject("target")?.optString("viewId") == t.optString("viewId")
            ) {
                last.put("text", text).put("desc", "输入「$text」")
                onChanged?.invoke()
                return
            }
        }
        add(JSONObject().put("action", "SET_TEXT").put("desc", "输入「$text」").put("target", t).put("text", text))
    }

    /** 纯坐标点击（预览画面、时间轴等自绘区域）。 */
    fun addTap(pkg: String?, x: Float, y: Float, long: Boolean) {
        if (paused) return
        ensureApp(pkg)
        val t = JSONObject().put("x", r4(x.toDouble() / screenW)).put("y", r4(y.toDouble() / screenH))
        add(
            JSONObject()
                .put("action", if (long) "LONG_CLICK" else "CLICK")
                .put("desc", "${if (long) "长按" else "点击"}坐标 (${t.getDouble("x")}, ${t.getDouble("y")})")
                .put("target", t)
        )
    }

    fun addDrag(pkg: String?, x1: Float, y1: Float, x2: Float, y2: Float, holdMs: Long, durationMs: Long) {
        if (paused) return
        ensureApp(pkg)
        val from = JSONObject().put("x", r4(x1.toDouble() / screenW)).put("y", r4(y1.toDouble() / screenH))
        val to = JSONObject().put("x", r4(x2.toDouble() / screenW)).put("y", r4(y2.toDouble() / screenH))
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
        synchronized(steps) {
            if (steps.isNotEmpty()) steps.removeAt(steps.size - 1)
            steps.lastOrNull()?.remove("waitMs")
        }
        onChanged?.invoke()
    }

    fun toJson(): JSONObject {
        val name = "录制脚本 " + SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US).format(Date())
        val arr = JSONArray()
        synchronized(steps) {
            steps.forEachIndexed { i, s ->
                val copy = JSONObject(s.toString())
                copy.put("id", "R${i + 1}")
                if (i == 0) copy.put("phase", "录制的步骤")
                arr.put(copy)
            }
        }
        return JSONObject()
            .put("name", name)
            .put("version", 1)
            .put("description", "由录制模式自动生成。坐标为屏幕比例（0~1）。")
            .put("screen", JSONObject().put("width", screenW).put("height", screenH))
            .put("defaults", JSONObject().put("waitMs", 1200).put("retries", 3))
            .put("steps", arr)
    }

    fun save(): String {
        if (count == 0) throw IllegalStateException("没有录制到任何步骤")
        val name = "recorded_" + SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date()) + ".json"
        return ScriptRepository(context).save(name, toJson().toString(2))
    }
}
