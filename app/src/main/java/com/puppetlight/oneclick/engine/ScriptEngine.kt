package com.puppetlight.oneclick.engine

import android.accessibilityservice.AccessibilityService
import android.content.Intent
import android.graphics.Rect
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.view.accessibility.AccessibilityNodeInfo
import com.puppetlight.oneclick.data.MediaHelper
import com.puppetlight.oneclick.script.ActionType
import com.puppetlight.oneclick.script.Expr
import com.puppetlight.oneclick.script.Script
import com.puppetlight.oneclick.script.ScriptParser
import com.puppetlight.oneclick.script.Step
import com.puppetlight.oneclick.script.Target
import com.puppetlight.oneclick.service.Notifier
import com.puppetlight.oneclick.service.PuppetAccessibilityService
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import org.json.JSONObject

class StepFailure(message: String) : Exception(message)

/**
 * 脚本执行引擎：逐步执行，每步执行后等待 → 校验 → 失败自动重试，
 * 重试用尽后暂停并在悬浮窗提示，由用户选择「重试 / 跳过 / 停止」。
 */
class ScriptEngine(
    private val service: PuppetAccessibilityService,
    private val script: Script,
    initialVars: Map<String, String>,
) {
    enum class Decision { RETRY, SKIP, STOP }

    private val vars = LinkedHashMap<String, String>().apply {
        putAll(script.variables)
        putAll(initialVars)
    }
    private val d = script.defaults
    private val screenW = service.screenSize().x
    private val screenH = service.screenSize().y
    private val finder = NodeFinder(service)
    private val gestures = GestureHelper(service, screenW, screenH) { service.overlay?.setPassThrough(it) }

    private val paused = MutableStateFlow(false)
    @Volatile private var pendingDecision: CompletableDeferred<Decision>? = null

    /** 用于「找到上一步导出的最新视频」：只认这个时间点之后出现的文件。 */
    private var mediaCursorMs = System.currentTimeMillis() - 5000
    private val seenMedia = mutableSetOf<Uri>()

    init {
        vars["screenWidth"] = screenW.toString()
        vars["screenHeight"] = screenH.toString()
    }

    // ---------------------------------------------------------------- 控制

    fun pause() {
        paused.value = true
        RunState.update { it.copy(status = RunState.Status.PAUSED) }
        RunState.log("已暂停")
    }

    fun resume() {
        paused.value = false
        RunState.update { if (it.status == RunState.Status.PAUSED) it.copy(status = RunState.Status.RUNNING) else it }
        RunState.log("继续运行")
    }

    val isPaused: Boolean get() = paused.value

    fun decide(d: Decision) {
        pendingDecision?.complete(d)
    }

    private suspend fun awaitResume() {
        if (paused.value) paused.first { !it }
    }

    /** 可暂停的等待。 */
    private suspend fun sleep(ms: Long) {
        var left = ms
        while (left > 0) {
            awaitResume()
            val chunk = minOf(left, 100L)
            delay(chunk)
            left -= chunk
        }
    }

    private fun status(detail: String) {
        RunState.update { it.copy(detail = detail) }
    }

    // ---------------------------------------------------------------- 主循环

    /** 返回 true 表示全部完成。 */
    suspend fun run(): Boolean {
        val steps = script.steps
        RunState.log("开始执行脚本「${script.name}」 共 ${steps.size} 步，屏幕 ${screenW}x$screenH")
        for ((i, step) in steps.withIndex()) {
            awaitResume()
            RunState.update {
                it.copy(
                    status = RunState.Status.RUNNING, index = i + 1, total = steps.size,
                    phase = step.phase, desc = Expr.substitute(step.desc, vars), detail = "", error = null,
                )
            }
            if (!Expr.truthy(step.enabledIf, vars)) {
                RunState.log("跳过（未启用）$step")
                continue
            }
            RunState.log("▶ [${step.id}] ${Expr.substitute(step.desc, vars)}")
            if (!runWithRetry(step, i + 1)) return false
        }
        RunState.update { it.copy(status = RunState.Status.DONE, detail = "全部完成", error = null) }
        RunState.log("全部步骤执行完毕")
        return true
    }

    /** 执行一步（含重试与出错暂停）。返回 false 表示用户选择停止。 */
    private suspend fun runWithRetry(step: Step, number: Int): Boolean {
        val retries = step.json.optInt("retries", d.retries)
        var attempt = 0
        while (true) {
            val error: String? = try {
                execute(step)
                null
            } catch (e: StepFailure) {
                e.message
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                "${e.javaClass.simpleName}: ${e.message}"
            }
            if (error == null) return true
            RunState.log("✗ ${step.id} 失败（第 ${attempt + 1} 次）：$error")
            if (attempt < retries) {
                attempt++
                status("失败，自动重试 $attempt/$retries：$error")
                sleep(1000)
                continue
            }
            if (step.optional) {
                RunState.log("可选步骤失败，已跳过：${step.id}")
                return true
            }
            // 重试用尽：暂停，等用户决定
            val deferred = CompletableDeferred<Decision>()
            pendingDecision = deferred
            RunState.update {
                it.copy(
                    status = RunState.Status.ERROR,
                    error = "第 $number 步「${Expr.substitute(step.desc, vars)}」失败：$error\n（已自动重试 $retries 次）",
                )
            }
            service.bringOverlayToFront()
            val decision = deferred.await()
            pendingDecision = null
            RunState.update { it.copy(status = RunState.Status.RUNNING, error = null) }
            when (decision) {
                Decision.RETRY -> { attempt = 0; RunState.log("用户选择重试") }
                Decision.SKIP -> { RunState.log("用户选择跳过"); return true }
                Decision.STOP -> return false
            }
        }
    }

    /** 执行一步 + 等待 + 校验。失败抛 [StepFailure]。 */
    private suspend fun execute(step: Step) {
        val o = step.json
        o.optLong("delayBeforeMs", 0).takeIf { it > 0 }?.let { sleep(it) }
        perform(step)
        sleep(o.optLong("waitMs", defaultWait(step.action)))
        o.optJSONObject("verify")?.let { v ->
            val t = Target.from(v, vars)!!
            status("校验：等待 ${t.describe()}")
            finder.waitFind(t, o.optLong("verifyTimeoutMs", d.verifyTimeoutMs))
                ?: throw StepFailure("执行后未检测到目标控件：${t.describe()}")
        }
        o.optJSONObject("verifyGone")?.let { v ->
            val t = Target.from(v, vars)!!
            if (!finder.waitGone(t, o.optLong("verifyTimeoutMs", d.verifyTimeoutMs))) {
                throw StepFailure("执行后控件仍未消失：${t.describe()}")
            }
        }
    }

    private fun defaultWait(a: ActionType): Long = when (a) {
        ActionType.WAIT, ActionType.SET_VAR, ActionType.NOTIFY, ActionType.WAIT_FOR,
        ActionType.WAIT_NEW_MEDIA, ActionType.GREEN_CHECK, ActionType.PREPARE_MEDIA -> 0
        ActionType.LAUNCH_APP -> 3000
        else -> d.waitMs
    }

    // ---------------------------------------------------------------- 各动作

    private suspend fun perform(step: Step) {
        val o = step.json
        when (step.action) {
            ActionType.LAUNCH_APP -> launchApp(o)
            ActionType.CLICK -> click(o, long = false)
            ActionType.LONG_CLICK -> click(o, long = true)
            ActionType.SET_TEXT -> setText(o)
            ActionType.SET_VALUE -> setValue(o)
            ActionType.SWIPE -> {
                val (x1, y1) = point(o.getJSONObject("from"))
                val (x2, y2) = point(o.getJSONObject("to"))
                ok(gestures.swipe(x1, y1, x2, y2, num(o, "durationMs", 400.0).toLong()), "滑动手势被取消")
            }
            ActionType.DRAG -> {
                val (x1, y1) = point(o.getJSONObject("from"))
                val (x2, y2) = point(o.getJSONObject("to"))
                ok(
                    gestures.drag(
                        x1, y1, x2, y2,
                        holdMs = num(o, "holdMs", 600.0).toLong(),
                        durationMs = num(o, "durationMs", 800.0).toLong(),
                        holdEndMs = num(o, "holdEndMs", 300.0).toLong(),
                    ), "拖动手势被取消",
                )
            }
            ActionType.PINCH -> {
                val c = o.optJSONObject("center") ?: JSONObject().put("x", 0.5).put("y", 0.4)
                val (cx, cy) = point(c)
                val span = num(o, "span", 0.3) * screenW
                val scale = num(o, "scale", 1.2)
                ok(gestures.pinch(cx, cy, span.toFloat(), (span * scale).toFloat(), num(o, "durationMs", 600.0).toLong()), "缩放手势被取消")
            }
            ActionType.TIMELINE_SEEK -> timelineSeek(o)
            ActionType.WAIT -> sleep(num(o, "ms", 1000.0).toLong())
            ActionType.WAIT_FOR -> {
                val t = target(o, "target")
                status("等待出现：${t.describe()}")
                finder.waitFind(t, num(o, "timeoutMs", 15000.0).toLong())
                    ?: throw StepFailure("等待超时，未出现：${t.describe()}")
            }
            ActionType.BACK -> service.performGlobalAction(AccessibilityService.GLOBAL_ACTION_BACK)
            ActionType.HOME -> service.performGlobalAction(AccessibilityService.GLOBAL_ACTION_HOME)
            ActionType.IME_ENTER -> imeEnter(o)
            ActionType.PREPARE_MEDIA -> prepareMedia(o)
            ActionType.WAIT_NEW_MEDIA -> waitNewMedia(o)
            ActionType.GREEN_CHECK -> greenCheck(step)
            ActionType.SET_VAR -> {
                val name = o.getString("name")
                val raw = o.get("value").toString()
                val v = runCatching { Expr.format(Expr.eval(raw, vars)) }.getOrElse { Expr.substitute(raw, vars) }
                vars[name] = v
                RunState.log("变量 $name = $v")
            }
            ActionType.NOTIFY -> {
                val uri = str(o, "openUri").takeIf { it.isNotEmpty() && !it.startsWith("\${") }?.let(Uri::parse)
                Notifier.notifyDone(service, str(o, "title", "一键木偶打光"), str(o, "text", "制作完成"), uri)
            }
            ActionType.MACRO -> Unit // 解析阶段已展开
        }
    }

    private fun ok(b: Boolean, msg: String) {
        if (!b) throw StepFailure(msg)
    }

    // ---------------------------------------------------------------- 工具

    private fun str(o: JSONObject, key: String, def: String = ""): String =
        if (o.has(key)) Expr.substitute(o.get(key).toString(), vars) else def

    private fun num(o: JSONObject, key: String, def: Double): Double = when (val v = o.opt(key)) {
        null, JSONObject.NULL -> def
        is Number -> v.toDouble()
        else -> try {
            Expr.eval(v.toString(), vars)
        } catch (e: Exception) {
            throw StepFailure("字段 $key 的值 \"$v\" 无法计算：${e.message}")
        }
    }

    private fun target(o: JSONObject, key: String): Target =
        Target.from(o.optJSONObject(key), vars) ?: throw StepFailure("缺少 $key")

    /** 比例坐标(0~1) 或 参考分辨率像素坐标(>1) → 实际屏幕像素。 */
    private fun px(x: Double) = (if (x <= 1.0) x * screenW else x * screenW / d.refWidth).toFloat()
    private fun py(y: Double) = (if (y <= 1.0) y * screenH else y * screenH / d.refHeight).toFloat()

    /** 解析一个位置：优先找控件中心，找不到用坐标；支持 dx/dy 偏移（屏幕比例）。 */
    private suspend fun point(o: JSONObject): Pair<Float, Float> {
        val t = Target.from(o, vars)!!
        var p: Pair<Float, Float>? = null
        if (t.hasSelector) {
            finder.waitFind(t, o.optLong("findTimeoutMs", d.findTimeoutMs))?.let {
                val b = finder.bounds(it)
                p = b.exactCenterX() to b.exactCenterY()
            }
        }
        if (p == null && t.hasPoint) p = px(t.x!!) to py(t.y!!)
        val r = p ?: throw StepFailure("找不到位置：${t.describe()}")
        val dx = num(o, "dx", 0.0) * screenW
        val dy = num(o, "dy", 0.0) * screenH
        return (r.first + dx.toFloat()) to (r.second + dy.toFloat())
    }

    private suspend fun locate(t: Target, timeoutMs: Long): AccessibilityNodeInfo? {
        if (!t.hasSelector) return null
        status("查找：${t.describe()}")
        return finder.waitFind(t, timeoutMs)
    }

    private suspend fun click(o: JSONObject, long: Boolean) {
        val t = target(o, "target")
        val node = locate(t, o.optLong("findTimeoutMs", d.findTimeoutMs))
        if (node != null) {
            if (!t.gesture) {
                val c = finder.clickableAncestor(node)
                val action = if (long) AccessibilityNodeInfo.ACTION_LONG_CLICK else AccessibilityNodeInfo.ACTION_CLICK
                if (c != null && c.performAction(action)) {
                    RunState.log("  控件${if (long) "长按" else "点击"}成功：${t.describe()}")
                    return
                }
            }
            val b = finder.bounds(node)
            val okk = if (long) gestures.longPress(b.exactCenterX(), b.exactCenterY())
            else gestures.tap(b.exactCenterX(), b.exactCenterY())
            ok(okk, "手势点击被取消")
            RunState.log("  手势点击控件中心 (${b.centerX()}, ${b.centerY()})")
            return
        }
        if (t.hasPoint) {
            val x = px(t.x!!); val y = py(t.y!!)
            val okk = if (long) gestures.longPress(x, y) else gestures.tap(x, y)
            ok(okk, "坐标点击被取消")
            RunState.log("  ${if (t.hasSelector) "未找到控件，" else ""}坐标点击 (${x.toInt()}, ${y.toInt()})")
            return
        }
        throw StepFailure("找不到控件：${t.describe()}")
    }

    private suspend fun findInput(o: JSONObject): AccessibilityNodeInfo? {
        val t = Target.from(o.optJSONObject("target"), vars)
        if (t != null && t.hasSelector) {
            locate(t.copy(includeEditable = true), o.optLong("findTimeoutMs", d.findTimeoutMs))?.let { return it }
        }
        finder.focusedInput()?.let { return it }
        if (t != null && t.hasPoint) {
            gestures.tap(px(t.x!!), py(t.y!!))
            sleep(700)
            finder.focusedInput()?.let { return it }
        }
        return finder.firstEditable()
    }

    private fun setNodeText(node: AccessibilityNodeInfo, text: String): Boolean {
        if (!node.isFocused) node.performAction(AccessibilityNodeInfo.ACTION_FOCUS)
        val args = Bundle().apply {
            putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text)
        }
        return node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
    }

    private suspend fun setText(o: JSONObject) {
        val text = str(o, "text")
        val node = findInput(o) ?: throw StepFailure("找不到输入框")
        ok(setNodeText(node, text), "输入文字失败（ACTION_SET_TEXT 被拒绝）")
        RunState.log("  输入：$text")
        if (o.optBoolean("imeEnter", false)) {
            sleep(300)
            imeEnter(JSONObject())
        }
    }

    private suspend fun imeEnter(o: JSONObject) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val n = finder.focusedInput()
            if (n != null && n.performAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_IME_ENTER.id)) return
        }
        val t = Target.from(o.optJSONObject("target"), vars)
        if (t != null && t.hasPoint) {
            gestures.tap(px(t.x!!), py(t.y!!))
            return
        }
        RunState.log("  当前系统不支持输入法回车动作，已忽略")
    }

    /**
     * 设置参数值。按 modes 顺序依次尝试：
     * field  —— 标签旁的输入框，ACTION_SET_TEXT
     * range  —— 标签旁的进度条，ACTION_SET_PROGRESS
     * dialog —— 点击数值（或标签）弹出输入框后输入，再点确定
     * slider —— 用手势点/拖到滑块对应位置
     */
    private suspend fun setValue(o: JSONObject) {
        val value = num(o, "value", 0.0)
        val min = num(o, "min", 0.0)
        val max = num(o, "max", 1.0)
        val text = Expr.format(value)
        val labelT = Target.from(o.optJSONObject("label"), vars)
        val label = labelT?.let { locate(it, o.optLong("findTimeoutMs", d.findTimeoutMs)) }
        val modes = o.optJSONArray("modes")?.let { a -> (0 until a.length()).map { a.getString(it) } }
            ?: listOf("field", "range", "slider")
        val fraction = if (max > min) ((value - min) / (max - min)).coerceIn(0.0, 1.0) else 0.0

        for (mode in modes) {
            when (mode) {
                "field" -> if (label != null) {
                    val f = finder.nearby(label) { it.isEditable }
                    if (f != null && setNodeText(f, text)) {
                        RunState.log("  [输入框] ${labelT.describe()} = $text"); return
                    }
                }
                "range" -> if (label != null) {
                    val r = finder.nearby(label) { it.rangeInfo != null }
                    if (r != null) {
                        val ri = r.rangeInfo
                        val v = if (ri.max - ri.min == (max - min).toFloat()) value.toFloat()
                        else ri.min + fraction.toFloat() * (ri.max - ri.min)
                        val args = Bundle().apply {
                            putFloat(AccessibilityNodeInfo.ACTION_ARGUMENT_PROGRESS_VALUE, v)
                        }
                        if (r.performAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_SET_PROGRESS.id, args)) {
                            RunState.log("  [进度条] ${labelT.describe()} = $v"); return
                        }
                    }
                }
                "dialog" -> {
                    val tapT = Target.from(o.optJSONObject("dialogTap"), vars)
                    val tapNode = tapT?.let { locate(it, 1500) } ?: label
                    val tapped = when {
                        tapNode != null -> {
                            val c = finder.clickableAncestor(tapNode)
                            if (c?.performAction(AccessibilityNodeInfo.ACTION_CLICK) == true) true
                            else finder.bounds(tapNode).let { gestures.tap(it.exactCenterX(), it.exactCenterY()) }
                        }
                        tapT != null && tapT.hasPoint -> gestures.tap(px(tapT.x!!), py(tapT.y!!))
                        else -> false
                    }
                    if (tapped) {
                        sleep(800)
                        val input = finder.focusedInput() ?: finder.firstEditable()
                        if (input != null && setNodeText(input, text)) {
                            sleep(300)
                            val confirm = Target(texts = listOf("确定", "完成", "OK", "Done", "确认"), exact = true)
                            finder.findOne(confirm)?.let { n ->
                                (finder.clickableAncestor(n) ?: n).performAction(AccessibilityNodeInfo.ACTION_CLICK)
                            } ?: imeEnter(JSONObject())
                            RunState.log("  [弹窗输入] = $text"); return
                        }
                    }
                }
                "slider" -> o.optJSONObject("slider")?.let { s ->
                    val x1 = px(num(s, "x1", 0.1)); val x2 = px(num(s, "x2", 0.9))
                    val y = when {
                        s.has("y") -> py(num(s, "y", 0.5))
                        label != null -> finder.bounds(label).exactCenterY() + (num(s, "dy", 0.0) * screenH).toFloat()
                        else -> null
                    }
                    if (y != null) {
                        val x = x1 + (x2 - x1) * fraction.toFloat()
                        val from = s.optString("from", "tap")
                        val okk = if (from == "start") gestures.drag(x1, y, x, y, 200, 500, 200)
                        else if (from == "end") gestures.drag(x2, y, x, y, 200, 500, 200)
                        else gestures.drag(x, y, x + 1, y, 100, 100, 100)
                        if (okk) {
                            RunState.log("  [滑块手势] 拖到 ${(fraction * 100).toInt()}% (${x.toInt()}, ${y.toInt()})"); return
                        }
                    }
                }
            }
        }
        throw StepFailure("无法设置参数「${labelT?.describe() ?: ""}」= $text（尝试方式：$modes）")
    }

    private suspend fun timelineSeek(o: JSONObject) {
        val seconds = num(o, "seconds", 0.0)
        val area = o.optJSONObject("area") ?: JSONObject()
        val y = py(num(area, "y", 0.75))
        val x1 = px(num(area, "x1", 0.15))
        val x2 = px(num(area, "x2", 0.85))
        val pps = num(o, "pxPerSecond", 0.15) * screenW
        val mode = o.optString("mode", "drag")
        status("时间轴定位到 ${Expr.format(seconds)} 秒")
        if (mode == "tap") {
            val origin = px(num(o, "originX", 0.5))
            ok(gestures.tap(origin + (seconds * pps).toFloat(), y), "点击时间轴失败")
            return
        }
        if (o.optBoolean("reset", true)) {
            repeat(o.optInt("resetSwipes", 3)) {
                gestures.drag(x1, y, x2, y, 80, 250, 0)
                sleep(250)
            }
            sleep(400)
        }
        var remaining = seconds * pps
        val maxStep = (x2 - x1).toDouble()
        while (remaining > 1) {
            val dd = minOf(remaining, maxStep).toFloat()
            ok(gestures.drag(x2, y, x2 - dd, y, 150, 900, 350), "拖动时间轴失败")
            remaining -= dd
            sleep(300)
        }
        RunState.log("  时间轴定位 ${Expr.format(seconds)}s（${Expr.format(seconds * pps)}px）")
    }

    private suspend fun prepareMedia(o: JSONObject) {
        val bg = vars["backgroundUri"].orEmpty()
        val green = vars["greenUri"].orEmpty()
        if (bg.isEmpty() || green.isEmpty()) throw StepFailure("未选择背景图或绿幕素材")
        status("复制素材到相册…")
        withContext(Dispatchers.IO) {
            // 先背景后绿幕：相册里绿幕素材是最新第 1 项，背景图是第 2 项
            vars["bgGalleryUri"] = MediaHelper.copyToGallery(service, Uri.parse(bg), MediaHelper.BG_PREFIX).toString()
            Thread.sleep(1100)
            vars["greenGalleryUri"] = MediaHelper.copyToGallery(service, Uri.parse(green), MediaHelper.GREEN_PREFIX).toString()
        }
        mediaCursorMs = System.currentTimeMillis()
        RunState.log("  素材已放入相册「${MediaHelper.ALBUM}」")
    }

    private suspend fun waitNewMedia(o: JSONObject) {
        val timeout = num(o, "timeoutMs", 900_000.0).toLong()
        val item = MediaHelper.waitForNewVideo(
            service, mediaCursorMs, timeout, num(o, "stableMs", 4000.0).toLong(), seenMedia,
        ) { status(it) } ?: throw StepFailure("在 ${timeout / 1000} 秒内没有在相册/下载目录发现新导出的视频")
        seenMedia += item.uri
        mediaCursorMs = maxOf(mediaCursorMs, item.modifiedMs - 1000)
        if (o.optBoolean("ensureInGallery", true)) MediaHelper.ensureInGallery(service, item)
        o.optString("saveAs").takeIf { it.isNotEmpty() }?.let { vars[it] = item.uri.toString() }
        RunState.log("  找到导出视频：${item.name}（${item.size / 1024} KB）")
    }

    private suspend fun greenCheck(step: Step) {
        val o = step.json
        val capturer = service.capturer()
        if (capturer == null) {
            if (o.optBoolean("skipIfUnavailable", true)) {
                RunState.log("  无法截图（未授权），跳过绿色残留检测"); return
            }
            throw StepFailure("无法截图")
        }
        val reg = o.optJSONObject("region") ?: JSONObject()
        val region = Rect(
            px(num(reg, "x1", 0.0)).toInt(), py(num(reg, "y1", 0.1)).toInt(),
            px(num(reg, "x2", 1.0)).toInt(), py(num(reg, "y2", 0.45)).toInt(),
        )
        val threshold = num(o, "threshold", 0.005)
        val maxAttempts = o.optInt("maxAttempts", 3)
        val fix = o.optJSONArray("fixSteps")?.let { ScriptParser.parseStepList(it, step.phase, "${step.id}.fix.") }.orEmpty()
        for (attempt in 1..maxAttempts) {
            status("截图检测残留绿色（第 $attempt 次）")
            val bmp = service.captureWithoutOverlay(capturer) ?: throw StepFailure("截图失败")
            val ratio = GreenDetector.greenRatio(bmp, region, null)
            bmp.recycle()
            RunState.log("  残留绿色占比 ${"%.3f".format(ratio * 100)}%（阈值 ${"%.3f".format(threshold * 100)}%）")
            if (ratio <= threshold) return
            if (attempt == maxAttempts) break
            status("仍有残留绿色，调高抠像容差后重试（$attempt/${maxAttempts - 1}）")
            for (s in fix) {
                RunState.log("  修正 ▶ $s")
                execute(s)
            }
            sleep(num(o, "settleMs", 1500.0).toLong())
        }
        throw StepFailure("调高容差 ${maxAttempts - 1} 次后画面仍有残留绿色")
    }

    private suspend fun launchApp(o: JSONObject) {
        val pkg = str(o, "package")
        if (pkg.isEmpty() || pkg.startsWith("\${")) throw StepFailure("未检测到要打开的 App（包名为空）")
        val intent = service.packageManager.getLaunchIntentForPackage(pkg)
            ?: throw StepFailure("未安装：$pkg")
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        if (o.optBoolean("freshStart", false)) intent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TASK)
        withContext(Dispatchers.Main) { service.startActivity(intent) }
        status("等待 $pkg 打开…")
        val deadline = System.currentTimeMillis() + num(o, "timeoutMs", 15000.0).toLong()
        while (System.currentTimeMillis() < deadline) {
            if (finder.packageOnScreen(pkg)) return
            sleep(400)
        }
        throw StepFailure("$pkg 没有出现在前台")
    }
}
