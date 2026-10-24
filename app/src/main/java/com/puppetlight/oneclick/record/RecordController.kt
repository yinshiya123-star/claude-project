package com.puppetlight.oneclick.record

import android.content.Context
import android.view.accessibility.AccessibilityEvent
import android.widget.Toast
import com.puppetlight.oneclick.engine.A11yDevice
import com.puppetlight.oneclick.engine.ShizukuDevice
import com.puppetlight.oneclick.service.PuppetAccessibilityService

/** 录制模式管理（无障碍 / Shizuku 两种来源）。所有方法在主线程调用。 */
object RecordController {

    private var recorder: Recorder? = null
    private var overlay: RecordOverlay? = null
    private var a11ySource: A11yRecordSource? = null
    private var shizukuSource: ShizukuRecordSource? = null
    private var appContext: Context? = null

    val isRecording: Boolean get() = recorder != null

    fun startA11y(service: PuppetAccessibilityService, packageVars: Map<String, String>) {
        if (isRecording) return
        val device = A11yDevice(service)
        val r = Recorder(service, device.screenW, device.screenH, packageVars)
        val source = A11yRecordSource(device, r)
        recorder = r
        a11ySource = source
        appContext = service.applicationContext
        overlay = RecordOverlay(service, r, device, captureButtons = true,
            currentPackage = { service.currentPackage }, onReplay = source::suppress).also { it.show() }
    }

    fun startShizuku(context: Context, device: ShizukuDevice, packageVars: Map<String, String>) {
        if (isRecording) return
        val app = context.applicationContext
        val r = Recorder(app, device.screenW, device.screenH, packageVars)
        recorder = r
        appContext = app
        val ov = RecordOverlay(app, r, device, captureButtons = false, currentPackage = { null }, onReplay = {})
        ov.show()
        overlay = ov
        shizukuSource = ShizukuRecordSource(device, r, { ov.screenBounds() }, ov::showHint).also { it.start() }
    }

    fun onA11yEvent(e: AccessibilityEvent) {
        a11ySource?.onEvent(e)
    }

    /** 结束录制；save=true 时保存为脚本文件并返回文件名。 */
    fun stop(save: Boolean): String? {
        val r = recorder ?: return null
        recorder = null
        a11ySource = null
        shizukuSource?.stop()
        shizukuSource = null
        overlay?.dismiss()
        overlay = null
        val ctx = appContext ?: return null
        if (!save) return null
        return try {
            val id = r.save()
            Toast.makeText(ctx, "录制已保存：$id（在「脚本管理」中查看）", Toast.LENGTH_LONG).show()
            id
        } catch (e: Exception) {
            Toast.makeText(ctx, "保存失败：${e.message}", Toast.LENGTH_LONG).show()
            null
        }
    }
}
