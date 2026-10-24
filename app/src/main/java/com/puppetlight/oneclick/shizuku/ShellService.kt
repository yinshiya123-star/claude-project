package com.puppetlight.oneclick.shizuku

import android.os.ParcelFileDescriptor
import android.os.SystemClock
import android.view.InputDevice
import android.view.InputEvent
import android.view.KeyCharacterMap
import android.view.KeyEvent
import android.view.MotionEvent
import java.lang.reflect.Method
import kotlin.concurrent.thread
import kotlin.system.exitProcess

/**
 * Shizuku UserService：由 Shizuku 以 shell（adb）身份启动的独立进程。
 * 拥有 INJECT_EVENTS 等 adb 权限，可以直接注入触摸/按键、执行 uiautomator、screencap。
 */
class ShellService : IShellService.Stub() {

    override fun destroy() {
        exitProcess(0)
    }

    override fun run(cmd: String): ParcelFileDescriptor {
        val pipe = ParcelFileDescriptor.createPipe()
        val process = ProcessBuilder("sh", "-c", cmd).redirectErrorStream(true).start()
        thread(isDaemon = true) {
            try {
                ParcelFileDescriptor.AutoCloseOutputStream(pipe[1]).use { out ->
                    process.inputStream.use { it.copyTo(out) }
                }
            } catch (_: Exception) {
                // 读取端被关闭（例如停止录制时的 getevent）
            } finally {
                process.destroy()
            }
        }
        return pipe[0]
    }

    // InputManager(Global).injectInputEvent 是隐藏 API，用反射调用（与 scrcpy 相同的做法）
    private val injector: Pair<Any, Method>? by lazy {
        runCatching {
            val c = Class.forName("android.hardware.input.InputManagerGlobal")
            c.getMethod("getInstance").invoke(null)!! to
                c.getMethod("injectInputEvent", InputEvent::class.java, Int::class.javaPrimitiveType)
        }.recoverCatching {
            val c = Class.forName("android.hardware.input.InputManager")
            c.getMethod("getInstance").invoke(null)!! to
                c.getMethod("injectInputEvent", InputEvent::class.java, Int::class.javaPrimitiveType)
        }.getOrNull()
    }

    private fun inject(e: InputEvent): Boolean {
        val (target, m) = injector ?: return false
        return runCatching { m.invoke(target, e, 0) as Boolean }.getOrDefault(false)
    }

    override fun injectMotion(downTime: Long, action: Int, xs: FloatArray, ys: FloatArray): Boolean {
        val n = xs.size
        val props = Array(n) { i ->
            MotionEvent.PointerProperties().apply { id = i; toolType = MotionEvent.TOOL_TYPE_FINGER }
        }
        val coords = Array(n) { i ->
            MotionEvent.PointerCoords().apply { x = xs[i]; y = ys[i]; pressure = 1f; size = 1f }
        }
        val ev = MotionEvent.obtain(
            downTime, SystemClock.uptimeMillis(), action, n, props, coords,
            0, 0, 1f, 1f, 0, 0, InputDevice.SOURCE_TOUCHSCREEN, 0,
        )
        return try {
            inject(ev)
        } finally {
            ev.recycle()
        }
    }

    override fun injectKey(keyCode: Int, metaState: Int): Boolean {
        val now = SystemClock.uptimeMillis()
        fun key(action: Int) = KeyEvent(
            now, now, action, keyCode, 0, metaState, KeyCharacterMap.VIRTUAL_KEYBOARD, 0, 0,
            InputDevice.SOURCE_KEYBOARD,
        )
        return inject(key(KeyEvent.ACTION_DOWN)) && inject(key(KeyEvent.ACTION_UP))
    }
}
