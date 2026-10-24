package com.puppetlight.oneclick.engine

import android.accessibilityservice.AccessibilityService
import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Rect
import android.hardware.display.DisplayManager
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.view.Display
import androidx.annotation.RequiresApi
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.resume

interface ScreenCapturer {
    suspend fun capture(): Bitmap?
}

/** Android 11+：无障碍服务直接截图，无需额外授权。 */
@RequiresApi(Build.VERSION_CODES.R)
class AccessibilityCapturer(private val service: AccessibilityService) : ScreenCapturer {
    override suspend fun capture(): Bitmap? = withTimeoutOrNull(5000) {
        suspendCancellableCoroutine { cont ->
            service.takeScreenshot(Display.DEFAULT_DISPLAY, service.mainExecutor,
                object : AccessibilityService.TakeScreenshotCallback {
                    override fun onSuccess(result: AccessibilityService.ScreenshotResult) {
                        val hw = Bitmap.wrapHardwareBuffer(result.hardwareBuffer, result.colorSpace)
                        val bmp = hw?.copy(Bitmap.Config.ARGB_8888, false)
                        result.hardwareBuffer.close()
                        if (cont.isActive) cont.resume(bmp)
                    }

                    override fun onFailure(errorCode: Int) {
                        if (cont.isActive) cont.resume(null)
                    }
                })
        }
    }
}

/** Android 8~10：使用录屏授权（MediaProjection）截一帧。 */
object ProjectionHolder {
    @Volatile
    var projection: MediaProjection? = null
}

class ProjectionCapturer(private val context: Context, private val w: Int, private val h: Int, private val dpi: Int) :
    ScreenCapturer {

    @SuppressLint("WrongConstant")
    override suspend fun capture(): Bitmap? {
        val projection = ProjectionHolder.projection ?: return null
        val reader = ImageReader.newInstance(w, h, PixelFormat.RGBA_8888, 2)
        val handler = Handler(Looper.getMainLooper())
        val vd = projection.createVirtualDisplay(
            "puppet-capture", w, h, dpi, DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
            reader.surface, null, handler,
        )
        try {
            return withTimeoutOrNull(3000) {
                suspendCancellableCoroutine { cont ->
                    reader.setOnImageAvailableListener({ r ->
                        val image = r.acquireLatestImage() ?: return@setOnImageAvailableListener
                        try {
                            val plane = image.planes[0]
                            val rowPadding = plane.rowStride - plane.pixelStride * w
                            val bmp = Bitmap.createBitmap(w + rowPadding / plane.pixelStride, h, Bitmap.Config.ARGB_8888)
                            bmp.copyPixelsFromBuffer(plane.buffer)
                            val cropped = Bitmap.createBitmap(bmp, 0, 0, w, h)
                            if (cont.isActive) cont.resume(cropped)
                        } finally {
                            image.close()
                            r.setOnImageAvailableListener(null, null)
                        }
                    }, handler)
                }
            }
        } finally {
            vd?.release()
            reader.close()
        }
    }
}

object GreenDetector {
    /**
     * 统计区域内「绿幕绿」像素占比。采用较严格的判定（色相 80°~165°、饱和度和亮度都较高、
     * G 明显大于 R 和 B），以减少把背景里普通绿色误判为残留绿幕的概率。
     */
    fun greenRatio(bmp: Bitmap, region: Rect, exclude: Rect?, step: Int = 4): Double {
        val r = Rect(region)
        if (!r.intersect(0, 0, bmp.width, bmp.height)) return 0.0
        var total = 0
        var green = 0
        val hsv = FloatArray(3)
        var y = r.top
        while (y < r.bottom) {
            var x = r.left
            while (x < r.right) {
                if (exclude == null || !exclude.contains(x, y)) {
                    val c = bmp.getPixel(x, y)
                    val rr = Color.red(c); val gg = Color.green(c); val bb = Color.blue(c)
                    total++
                    if (gg > 90 && gg > rr * 1.35 && gg > bb * 1.25) {
                        Color.RGBToHSV(rr, gg, bb, hsv)
                        if (hsv[0] in 80f..165f && hsv[1] > 0.45f && hsv[2] > 0.35f) green++
                    }
                }
                x += step
            }
            y += step
        }
        return if (total == 0) 0.0 else green.toDouble() / total
    }
}
