package com.puppetlight.oneclick.engine

import android.graphics.Bitmap

/**
 * 「操作手机」的抽象。两种实现：
 * - [A11yDevice]：无障碍服务（AccessibilityService）
 * - [ShizukuDevice]：通过 Shizuku 获得 adb（shell）权限，用 uiautomator / input / screencap
 */
interface Device {
    val name: String
    val screenW: Int
    val screenH: Int

    /** 当前屏幕所有窗口的控件树（顶层窗口在前）。 */
    suspend fun snapshot(): List<UiNode>

    suspend fun clickNode(node: UiNode, long: Boolean): Boolean
    /** 把 [text] 填进输入框；node 为 null 表示当前焦点输入框。 */
    suspend fun setText(node: UiNode?, text: String): Boolean
    /** 进度条直接设值，不支持返回 false。 */
    suspend fun setProgress(node: UiNode, value: Double, min: Double, max: Double): Boolean

    suspend fun tap(x: Float, y: Float): Boolean
    suspend fun longPress(x: Float, y: Float): Boolean
    suspend fun swipe(x1: Float, y1: Float, x2: Float, y2: Float, durationMs: Long): Boolean
    suspend fun drag(x1: Float, y1: Float, x2: Float, y2: Float, holdMs: Long, durationMs: Long, holdEndMs: Long): Boolean
    suspend fun pinch(cx: Float, cy: Float, startSpan: Float, endSpan: Float, durationMs: Long): Boolean
    suspend fun scrollForward(): Boolean

    suspend fun back()
    suspend fun home()
    suspend fun imeEnter(): Boolean

    /** 启动 App（不等待），返回是否已发出启动请求。 */
    suspend fun launch(pkg: String, fresh: Boolean): Boolean
    suspend fun isForeground(pkg: String): Boolean

    /** 截图（不含本 App 悬浮窗），不支持返回 null。 */
    suspend fun screenshot(): Bitmap?
}
