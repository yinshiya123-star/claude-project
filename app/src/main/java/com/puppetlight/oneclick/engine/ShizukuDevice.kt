package com.puppetlight.oneclick.engine

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import android.view.KeyEvent
import android.view.MotionEvent
import com.puppetlight.oneclick.shizuku.IShellService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlin.math.max

/**
 * Shizuku（adb 权限）实现：
 * - 控件：`uiautomator dump` 解析
 * - 手势/按键：shell 进程直接注入 InputEvent（失败时退回 `input` 命令）
 * - 截图：`screencap -p`
 * - 输入中文：剪贴板 + 粘贴键（`input text` 不支持中文）
 */
class ShizukuDevice(private val context: Context, private val shell: IShellService) : Device {

    override val name = "Shizuku"
    override val screenW: Int
    override val screenH: Int
    @Volatile private var injectBroken = false

    init {
        val size = Screen.realSize(context)
        screenW = size.x
        screenH = size.y
    }

    // ------------------------------------------------------------ shell

    suspend fun exec(cmd: String): String = withContext(Dispatchers.IO) {
        ParcelFileDescriptor.AutoCloseInputStream(shell.run(cmd)).bufferedReader().use { it.readText() }
    }

    fun stream(cmd: String): ParcelFileDescriptor = shell.run(cmd)

    // ------------------------------------------------------------ 控件

    override suspend fun snapshot(): List<UiNode> {
        repeat(3) {
            val out = exec("uiautomator dump /data/local/tmp/puppet_ui.xml >/dev/null 2>&1; cat /data/local/tmp/puppet_ui.xml")
            val nodes = UiDumpParser.parse(out)
                .filter { it.pkg != context.packageName && it.pkg != "com.android.systemui" }
            if (nodes.isNotEmpty()) return nodes
            delay(300)
        }
        return emptyList()
    }

    override suspend fun clickNode(node: UiNode, long: Boolean): Boolean =
        if (long) longPress(node.bounds.cx, node.bounds.cy) else tap(node.bounds.cx, node.bounds.cy)

    override suspend fun setText(node: UiNode?, text: String): Boolean {
        if (node != null) {
            tap(node.bounds.cx, node.bounds.cy)
            delay(400)
        }
        // 全选，后续输入会替换原内容；注入不可用时退回「移到末尾 + 连按删除」
        if (injectBroken) exec("input keyevent ${KeyEvent.KEYCODE_MOVE_END}" + " ${KeyEvent.KEYCODE_DEL}".repeat(40))
        else key(KeyEvent.KEYCODE_A, KeyEvent.META_CTRL_ON or KeyEvent.META_CTRL_LEFT_ON)
        if (text.isEmpty()) return key(KeyEvent.KEYCODE_DEL)
        if (text.all { it.code in 33..126 }) {
            exec("input text '${text.replace("'", "'\\''")}'")
            return true
        }
        // 中文等：先放进剪贴板，再发送粘贴键
        withContext(Dispatchers.Main) {
            val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            cm.setPrimaryClip(ClipData.newPlainText("puppet", text))
        }
        delay(200)
        return key(KeyEvent.KEYCODE_PASTE)
    }

    override suspend fun setProgress(node: UiNode, value: Double, min: Double, max: Double) = false

    override suspend fun scrollForward(): Boolean {
        val s = snapshot().flatMap { it.walk().toList() }.filter { it.scrollable }
            .maxByOrNull { it.bounds.width.toLong() * it.bounds.height } ?: return false
        val b = s.bounds
        return drag(b.cx, b.top + b.height * 0.75f, b.cx, b.top + b.height * 0.25f, 0, 400, 200)
    }

    // ------------------------------------------------------------ 注入

    private fun motion(downTime: Long, action: Int, xs: FloatArray, ys: FloatArray): Boolean {
        if (injectBroken) return false
        val ok = runCatching { shell.injectMotion(downTime, action, xs, ys) }.getOrDefault(false)
        if (!ok) injectBroken = true
        return ok
    }

    private suspend fun key(code: Int, meta: Int = 0): Boolean {
        val ok = !injectBroken && runCatching { shell.injectKey(code, meta) }.getOrDefault(false)
        if (!ok && meta == 0) exec("input keyevent $code")
        return true
    }

    private fun i(v: Float) = v.toInt().coerceIn(0, max(screenW, screenH))

    /** 单指路径：按住 holdMs → 按路径移动 → 停留 holdEndMs → 抬起。 */
    private suspend fun stroke(points: List<Pair<Float, Float>>, holdMs: Long, moveMs: Long, holdEndMs: Long): Boolean {
        val (sx, sy) = points.first()
        val down = SystemClock.uptimeMillis()
        if (!motion(down, MotionEvent.ACTION_DOWN, floatArrayOf(sx), floatArrayOf(sy))) return false
        if (holdMs > 0) delay(holdMs)
        val steps = max(1, (moveMs / 16).toInt())
        for (s in 1..steps) {
            val f = s.toFloat() / steps
            val (x, y) = interpolate(points, f)
            motion(down, MotionEvent.ACTION_MOVE, floatArrayOf(x), floatArrayOf(y))
            delay(moveMs / steps)
        }
        val (ex, ey) = points.last()
        if (holdEndMs > 0) delay(holdEndMs)
        return motion(down, MotionEvent.ACTION_UP, floatArrayOf(ex), floatArrayOf(ey))
    }

    private fun interpolate(p: List<Pair<Float, Float>>, f: Float): Pair<Float, Float> {
        if (p.size == 1) return p[0]
        val seg = (f * (p.size - 1)).coerceAtMost(p.size - 1.0001f)
        val k = seg.toInt()
        val t = seg - k
        val (x1, y1) = p[k]; val (x2, y2) = p[k + 1]
        return (x1 + (x2 - x1) * t) to (y1 + (y2 - y1) * t)
    }

    override suspend fun tap(x: Float, y: Float): Boolean = withPassThrough {
        stroke(listOf(x to y), 0, 0, 50) || run { exec("input tap ${i(x)} ${i(y)}"); true }
    }

    override suspend fun longPress(x: Float, y: Float): Boolean = withPassThrough {
        stroke(listOf(x to y), 900, 0, 0) || run { exec("input swipe ${i(x)} ${i(y)} ${i(x)} ${i(y)} 900"); true }
    }

    override suspend fun swipe(x1: Float, y1: Float, x2: Float, y2: Float, durationMs: Long): Boolean = withPassThrough {
        stroke(listOf(x1 to y1, x2 to y2), 0, durationMs, 0) ||
            run { exec("input swipe ${i(x1)} ${i(y1)} ${i(x2)} ${i(y2)} $durationMs"); true }
    }

    override suspend fun drag(
        x1: Float, y1: Float, x2: Float, y2: Float, holdMs: Long, durationMs: Long, holdEndMs: Long,
    ): Boolean = withPassThrough {
        stroke(listOf(x1 to y1, x2 to y2), holdMs, durationMs, holdEndMs) ||
            run { exec("input swipe ${i(x1)} ${i(y1)} ${i(x2)} ${i(y2)} ${holdMs + durationMs + holdEndMs}"); true }
    }

    override suspend fun pinch(cx: Float, cy: Float, startSpan: Float, endSpan: Float, durationMs: Long): Boolean =
        withPassThrough {
            val down = SystemClock.uptimeMillis()
            fun xs(span: Float) = floatArrayOf(cx - span / 2, cx + span / 2)
            val ys = floatArrayOf(cy, cy)
            if (!motion(down, MotionEvent.ACTION_DOWN, floatArrayOf(cx - startSpan / 2), floatArrayOf(cy))) return@withPassThrough false
            val pointerDown = MotionEvent.ACTION_POINTER_DOWN or (1 shl MotionEvent.ACTION_POINTER_INDEX_SHIFT)
            motion(down, pointerDown, xs(startSpan), ys)
            val steps = max(1, (durationMs / 16).toInt())
            for (s in 1..steps) {
                val span = startSpan + (endSpan - startSpan) * s / steps
                motion(down, MotionEvent.ACTION_MOVE, xs(span), ys)
                delay(durationMs / steps)
            }
            delay(150)
            val pointerUp = MotionEvent.ACTION_POINTER_UP or (1 shl MotionEvent.ACTION_POINTER_INDEX_SHIFT)
            motion(down, pointerUp, xs(endSpan), ys)
            motion(down, MotionEvent.ACTION_UP, floatArrayOf(cx - endSpan / 2), floatArrayOf(cy))
        }

    private suspend fun <T> withPassThrough(block: suspend () -> T): T {
        withContext(Dispatchers.Main) { RunController.overlay?.setPassThrough(true) }
        try {
            return block()
        } finally {
            withContext(Dispatchers.Main) { RunController.overlay?.setPassThrough(false) }
        }
    }

    override suspend fun back() { key(KeyEvent.KEYCODE_BACK) }
    override suspend fun home() { key(KeyEvent.KEYCODE_HOME) }
    override suspend fun imeEnter(): Boolean = key(KeyEvent.KEYCODE_ENTER)

    // ------------------------------------------------------------ App / 截图

    override suspend fun launch(pkg: String, fresh: Boolean): Boolean {
        val comp = context.packageManager.getLaunchIntentForPackage(pkg)?.component ?: return false
        exec("am start ${if (fresh) "-S " else ""}-n ${comp.flattenToShortString()}")
        return true
    }

    override suspend fun isForeground(pkg: String): Boolean =
        exec("dumpsys window | grep -E 'mCurrentFocus|mFocusedApp'").contains("$pkg/")

    override suspend fun screenshot(): Bitmap? {
        withContext(Dispatchers.Main) { RunController.overlay?.setHidden(true) }
        try {
            delay(250)
            return withContext(Dispatchers.IO) {
                ParcelFileDescriptor.AutoCloseInputStream(shell.run("screencap -p")).use { BitmapFactory.decodeStream(it) }
            }
        } finally {
            withContext(Dispatchers.Main) { RunController.overlay?.setHidden(false) }
        }
    }
}
