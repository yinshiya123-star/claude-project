package com.puppetlight.oneclick.record

import android.graphics.Rect
import android.os.ParcelFileDescriptor
import com.puppetlight.oneclick.engine.NodeQuery
import com.puppetlight.oneclick.engine.RunState
import com.puppetlight.oneclick.engine.ShizukuDevice
import com.puppetlight.oneclick.engine.UiNode
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlin.math.hypot

/**
 * Shizuku 录制来源：用 `getevent` 读取触摸屏原始事件得到每次点击/长按/拖动的坐标，
 * 再到最近一次 `uiautomator dump` 的控件树里找到被点的控件（文字/描述/viewId）。
 */
class ShizukuRecordSource(
    private val device: ShizukuDevice,
    private val recorder: Recorder,
    private val panelBounds: () -> Rect?,
    private val onHint: (String) -> Unit,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var stream: ParcelFileDescriptor? = null

    @Volatile private var roots: List<UiNode> = emptyList()
    @Volatile private var rootsTime = 0L
    @Volatile private var lastUpTime = 0L

    fun start() {
        scope.launch { refreshLoop() }
        scope.launch {
            try {
                readTouches()
            } catch (e: Exception) {
                if (isActive) onHint("读取触摸事件失败：${e.message}")
            }
        }
    }

    fun stop() {
        runCatching { stream?.close() }
        scope.cancel()
    }

    /** 持续刷新控件树。点击后界面会变，刷新时间晚于上次抬手才认为可用。 */
    private suspend fun refreshLoop() {
        while (scope.isActive) {
            if (!recorder.paused) {
                val t = System.currentTimeMillis()
                val r = runCatching { device.snapshot() }.getOrDefault(emptyList())
                roots = r
                rootsTime = t
            }
            delay(700)
        }
    }

    private class Axis(var max: Int = 0)

    /** 解析 `getevent -lp`，找到触摸屏设备和坐标范围。 */
    private suspend fun findTouchscreen(): Triple<String, Int, Int>? {
        val out = device.exec("getevent -lp")
        var dev: String? = null
        val x = Axis(); val y = Axis()
        var result: Triple<String, Int, Int>? = null
        val maxRe = Regex("""max (\d+)""")
        for (line in out.lines()) {
            if (line.startsWith("add device")) {
                if (dev != null && x.max > 0 && y.max > 0 && result == null) result = Triple(dev, x.max, y.max)
                dev = line.substringAfter(":").trim()
                x.max = 0; y.max = 0
            } else if (line.contains("ABS_MT_POSITION_X")) {
                x.max = maxRe.find(line)?.groupValues?.get(1)?.toInt() ?: 0
            } else if (line.contains("ABS_MT_POSITION_Y")) {
                y.max = maxRe.find(line)?.groupValues?.get(1)?.toInt() ?: 0
            }
        }
        if (result == null && dev != null && x.max > 0 && y.max > 0) result = Triple(dev, x.max, y.max)
        return result
    }

    private suspend fun readTouches() {
        val (dev, maxX, maxY) = findTouchscreen() ?: run { onHint("没有找到触摸屏设备"); return }
        val sw = device.screenW.toFloat(); val sh = device.screenH.toFloat()
        val pfd = device.stream("getevent -lt $dev")
        stream = pfd
        val reader = ParcelFileDescriptor.AutoCloseInputStream(pfd).bufferedReader()
        val timeRe = Regex("""\[\s*(\d+\.\d+)]""")

        var curX = -1f; var curY = -1f
        var down = false; var pendingDown = false; var pendingUp = false
        var downX = 0f; var downY = 0f; var downT = 0.0; var moveT = 0.0
        var otherSlot = false
        val slop = 16 * (sw / 1080f)

        while (scope.isActive) {
            val line = reader.readLine() ?: break
            val t = timeRe.find(line)?.groupValues?.get(1)?.toDouble() ?: continue
            val parts = line.substringAfter("]").trim().split(Regex("\\s+"))
            if (parts.size < 3) continue
            val (type, code, value) = Triple(parts[0], parts[1], parts[2])
            when {
                code == "ABS_MT_SLOT" -> otherSlot = value.toLongOrNull(16) != 0L
                otherSlot -> Unit
                code == "ABS_MT_POSITION_X" -> curX = value.toLong(16) * sw / (maxX + 1)
                code == "ABS_MT_POSITION_Y" -> curY = value.toLong(16) * sh / (maxY + 1)
                code == "ABS_MT_TRACKING_ID" -> if (value == "ffffffff") pendingUp = true else pendingDown = true
                code == "BTN_TOUCH" -> if (value == "DOWN") pendingDown = true else pendingUp = true
                type == "EV_SYN" && code == "SYN_REPORT" -> {
                    if (pendingDown && !down && curX >= 0) {
                        down = true; downX = curX; downY = curY; downT = t; moveT = 0.0
                    }
                    if (down && moveT == 0.0 && hypot(curX - downX, curY - downY) > slop) moveT = t
                    if (pendingUp && down) {
                        down = false
                        onTouch(downX, downY, curX, curY, downT, moveT, t)
                    }
                    pendingDown = false; pendingUp = false
                }
            }
        }
    }

    private fun onTouch(x1: Float, y1: Float, x2: Float, y2: Float, downT: Double, moveT: Double, upT: Double) {
        val upWall = System.currentTimeMillis()
        val prevUp = lastUpTime
        lastUpTime = upWall
        if (recorder.paused) return
        panelBounds()?.let { if (it.contains(x1.toInt(), y1.toInt())) return }

        val snap = roots
        val pkg = snap.firstOrNull()?.pkg
        if (moveT > 0) {
            val hold = ((moveT - downT) * 1000).toLong()
            val dur = ((upT - moveT) * 1000).toLong()
            recorder.addDrag(pkg, x1, y1, x2, y2, hold, dur)
            return
        }
        val long = (upT - downT) > 0.5
        // 控件树要在上一次点击之后刷新过，才能代表当时的界面
        val fresh = rootsTime > prevUp + 300
        val node = if (fresh) pickNode(snap, x1, y1) else null
        if (node != null) recorder.recordClick(pkg, node, snap, long)
        else {
            recorder.addTap(pkg, x1, y1, long)
            if (!fresh) onHint("操作太快，这一步只记录了坐标；放慢一点能记录按钮文字")
        }
        RunState.log("录制：${recorder.lastDesc}")
    }

    /** 被点位置最内层的控件；没有文字/描述/id 时向上找最多 3 层。 */
    private fun pickNode(snap: List<UiNode>, x: Float, y: Float): UiNode? {
        var n = NodeQuery.deepestAt(snap, x, y)
        var depth = 0
        while (n != null && depth < 4) {
            if (!n.text.isNullOrBlank() || !n.desc.isNullOrBlank() || !n.viewId.isNullOrBlank()) return n
            n = n.parent
            depth++
        }
        return null
    }
}
