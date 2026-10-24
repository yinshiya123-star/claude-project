package com.puppetlight.oneclick.service

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.PixelFormat
import android.provider.Settings
import android.view.ContextThemeWrapper
import android.view.Gravity
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import com.puppetlight.oneclick.R

/**
 * 悬浮窗基础封装。优先使用无障碍悬浮窗（TYPE_ACCESSIBILITY_OVERLAY）：
 * 不需要额外权限，且不会被 Android 12+ 的「不受信任触摸」策略拦截；
 * 失败时退回普通悬浮窗（需要悬浮窗权限）。
 */
open class OverlayWindow(protected val context: Context, layoutRes: Int) {

    protected val wm = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    val view: View = LayoutInflater.from(ContextThemeWrapper(context, R.style.Theme_PuppetLight)).inflate(layoutRes, null)
    protected val params = WindowManager.LayoutParams(
        WindowManager.LayoutParams.WRAP_CONTENT,
        WindowManager.LayoutParams.WRAP_CONTENT,
        WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
        PixelFormat.TRANSLUCENT,
    ).apply {
        gravity = Gravity.TOP or Gravity.START
        x = dp(8)
        y = dp(96)
    }
    var isShown = false
        private set

    protected fun dp(v: Int) = (v * context.resources.displayMetrics.density).toInt()

    open fun show() {
        if (isShown) return
        try {
            wm.addView(view, params)
        } catch (e: Exception) {
            if (Settings.canDrawOverlays(context)) {
                params.type = WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
                wm.addView(view, params)
            } else throw e
        }
        isShown = true
    }

    open fun dismiss() {
        if (!isShown) return
        runCatching { wm.removeView(view) }
        isShown = false
    }

    fun bringToFront() {
        if (!isShown) return
        runCatching { wm.removeView(view); wm.addView(view, params) }
    }

    /** 手势执行期间让触摸穿透悬浮窗。 */
    fun setPassThrough(on: Boolean) {
        if (!isShown) return
        val f = WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
        params.flags = if (on) params.flags or f else params.flags and f.inv()
        runCatching { wm.updateViewLayout(view, params) }
    }

    fun setHidden(hidden: Boolean) {
        view.visibility = if (hidden) View.INVISIBLE else View.VISIBLE
    }

    /** 让某个控件可以拖动整个悬浮窗。 */
    @SuppressLint("ClickableViewAccessibility")
    protected fun makeDraggable(handle: View) {
        var downX = 0f; var downY = 0f; var startX = 0; var startY = 0
        handle.setOnTouchListener { _, e ->
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downX = e.rawX; downY = e.rawY; startX = params.x; startY = params.y; true
                }
                MotionEvent.ACTION_MOVE -> {
                    params.x = startX + (e.rawX - downX).toInt()
                    params.y = startY + (e.rawY - downY).toInt()
                    runCatching { wm.updateViewLayout(view, params) }
                    true
                }
                else -> false
            }
        }
    }
}
