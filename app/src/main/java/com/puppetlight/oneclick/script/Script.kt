package com.puppetlight.oneclick.script

import org.json.JSONArray
import org.json.JSONObject

/** 所有支持的动作类型。详见 docs/SCRIPT_FORMAT.md */
enum class ActionType {
    LAUNCH_APP,      // 打开 App
    CLICK,           // 点击控件（找不到则按坐标点击）
    LONG_CLICK,      // 长按控件
    SET_TEXT,        // 输入文字（ACTION_SET_TEXT）
    SET_VALUE,       // 设置参数值：输入框 → 进度条 → 弹窗输入 → 手势拖动滑块
    SWIPE,           // 滑动手势
    DRAG,            // 按住后拖动（拖动图层/滑块）
    PINCH,           // 双指缩放
    TIMELINE_SEEK,   // 拖动时间轴到指定秒数
    WAIT,            // 固定等待
    WAIT_FOR,        // 等待控件出现
    BACK,            // 系统返回键
    HOME,            // 回到桌面
    IME_ENTER,       // 输入法回车/搜索
    PREPARE_MEDIA,   // 把用户选的素材复制到相册最新位置
    WAIT_NEW_MEDIA,  // 等待相册/下载目录出现新导出的视频
    GREEN_CHECK,     // 截图检测残留绿色，不合格则执行修正步骤后重试
    SET_VAR,         // 修改运行时变量
    NOTIFY,          // 发送通知
    MACRO,           // 调用宏（解析时展开）
}

/** 一个步骤。原始 JSON 保留在 [json]，执行时再做变量替换，便于使用运行时变量。 */
class Step(
    val id: String,
    val action: ActionType,
    val desc: String,
    val phase: String,
    val json: JSONObject,
) {
    val optional: Boolean get() = json.optBoolean("optional", false)
    val enabledIf: String? get() = json.optString("enabledIf").ifEmpty { null }

    override fun toString(): String = "[$id] $desc"
}

class ScriptDefaults(
    val waitMs: Long = 1200,
    val retries: Int = 3,
    val findTimeoutMs: Long = 2500,
    val verifyTimeoutMs: Long = 6000,
    /** 录制/编写脚本时的参考分辨率；坐标 > 1 时按此分辨率换算成比例。 */
    val refWidth: Int = 1080,
    val refHeight: Int = 2400,
)

class Script(
    val name: String,
    val version: Int,
    val description: String,
    val defaults: ScriptDefaults,
    val variables: Map<String, String>,
    val steps: List<Step>,
    val sourceJson: JSONObject,
) {
    val phases: List<String> get() = steps.map { it.phase }.distinct()
}

class ScriptException(message: String) : Exception(message)

object ScriptParser {

    fun parse(text: String): Script {
        val root = try {
            JSONObject(text)
        } catch (e: Exception) {
            throw ScriptException("JSON 格式错误: ${e.message}")
        }
        return parse(root)
    }

    fun parse(root: JSONObject): Script {
        val d = root.optJSONObject("defaults") ?: JSONObject()
        val screen = root.optJSONObject("screen") ?: JSONObject()
        val defaults = ScriptDefaults(
            waitMs = d.optLong("waitMs", 1200),
            retries = d.optInt("retries", 3),
            findTimeoutMs = d.optLong("findTimeoutMs", 2500),
            verifyTimeoutMs = d.optLong("verifyTimeoutMs", 6000),
            refWidth = screen.optInt("width", 1080),
            refHeight = screen.optInt("height", 2400),
        )
        val vars = linkedMapOf<String, String>()
        root.optJSONObject("variables")?.let { v ->
            v.keys().forEach { k -> vars[k] = v.get(k).toString() }
        }
        val macros = mutableMapOf<String, JSONArray>()
        root.optJSONObject("macros")?.let { m ->
            m.keys().forEach { k ->
                macros[k] = m.optJSONArray(k) ?: throw ScriptException("宏 $k 必须是步骤数组")
            }
        }
        val stepsJson = root.optJSONArray("steps") ?: throw ScriptException("缺少 steps 数组")
        val steps = mutableListOf<Step>()
        parseSteps(stepsJson, "", "", macros, steps, 0)
        if (steps.isEmpty()) throw ScriptException("steps 为空")
        return Script(
            name = root.optString("name", "未命名脚本"),
            version = root.optInt("version", 1),
            description = root.optString("description", ""),
            defaults = defaults,
            variables = vars,
            steps = steps,
            sourceJson = root,
        )
    }

    /** 解析一组步骤（嵌套的 fixSteps 也用这个）。 */
    fun parseStepList(arr: JSONArray, phase: String, idPrefix: String): List<Step> {
        val out = mutableListOf<Step>()
        parseSteps(arr, phase, idPrefix, emptyMap(), out, 0)
        return out
    }

    private fun parseSteps(
        arr: JSONArray,
        inheritedPhase: String,
        idPrefix: String,
        macros: Map<String, JSONArray>,
        out: MutableList<Step>,
        depth: Int,
    ) {
        if (depth > 8) throw ScriptException("宏嵌套过深（是否递归调用？）")
        var phase = inheritedPhase
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: throw ScriptException("第 ${i + 1} 个步骤不是对象")
            if (o.has("phase")) phase = o.getString("phase")
            val id = o.optString("id").ifEmpty { "$idPrefix${i + 1}" }
            val actionName = o.optString("action")
            val action = try {
                ActionType.valueOf(actionName)
            } catch (e: IllegalArgumentException) {
                throw ScriptException("步骤 $id 的 action \"$actionName\" 不支持。可用: ${ActionType.values().joinToString()}")
            }
            val desc = o.optString("desc").ifEmpty { actionName }
            if (action == ActionType.MACRO) {
                val name = o.optString("name")
                val body = macros[name] ?: throw ScriptException("步骤 $id 调用了不存在的宏 \"$name\"")
                val args = o.optJSONObject("args") ?: JSONObject()
                val expanded = expandMacro(body, args)
                val before = out.size
                parseSteps(expanded, phase, "$id.", macros, out, depth + 1)
                // 宏内步骤继承调用处的 optional / enabledIf，并在描述里带上调用说明
                for (k in before until out.size) {
                    val s = out[k]
                    if (o.has("enabledIf") && !s.json.has("enabledIf")) s.json.put("enabledIf", o.getString("enabledIf"))
                    if (o.optBoolean("optional", false)) s.json.put("optional", true)
                    out[k] = Step(s.id, s.action, "$desc › ${s.desc}", s.phase, s.json)
                }
                continue
            }
            validate(id, action, o)
            out += Step(id, action, desc, phase, o)
        }
    }

    private fun validate(id: String, action: ActionType, o: JSONObject) {
        fun need(key: String) {
            if (!o.has(key)) throw ScriptException("步骤 $id ($action) 缺少字段 \"$key\"")
        }
        when (action) {
            ActionType.LAUNCH_APP -> need("package")
            ActionType.CLICK, ActionType.LONG_CLICK, ActionType.WAIT_FOR -> need("target")
            ActionType.SET_TEXT -> need("text")
            ActionType.SET_VALUE -> need("value")
            ActionType.SWIPE, ActionType.DRAG -> { need("from"); need("to") }
            ActionType.TIMELINE_SEEK -> need("seconds")
            ActionType.SET_VAR -> { need("name"); need("value") }
            ActionType.GREEN_CHECK -> {
                o.optJSONArray("fixSteps")?.let { parseStepList(it, "", "$id.fix.") }
            }
            else -> Unit
        }
    }

    /** 宏参数替换：字段值恰好为 "$[k]" 时整体替换为参数（可为数组），否则做字符串内替换。 */
    private fun expandMacro(body: JSONArray, args: JSONObject): JSONArray {
        fun sub(v: Any?): Any? = when (v) {
            is JSONObject -> JSONObject().also { n -> v.keys().forEach { k -> n.put(k, sub(v.get(k))) } }
            is JSONArray -> JSONArray().also { n -> for (i in 0 until v.length()) n.put(sub(v.get(i))) }
            is String -> {
                val whole = Regex("""^\$\[([A-Za-z0-9_]+)]$""").find(v)
                if (whole != null && args.has(whole.groupValues[1])) {
                    args.get(whole.groupValues[1])
                } else {
                    Regex("""\$\[([A-Za-z0-9_]+)]""").replace(v) { m ->
                        val a = args.opt(m.groupValues[1])
                        when (a) {
                            null -> m.value
                            is JSONArray -> if (a.length() > 0) a.get(0).toString() else ""
                            else -> a.toString()
                        }
                    }
                }
            }
            else -> v
        }
        return sub(body) as JSONArray
    }
}
