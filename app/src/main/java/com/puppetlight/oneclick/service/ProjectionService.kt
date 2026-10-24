package com.puppetlight.oneclick.service

import android.app.Activity
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import com.puppetlight.oneclick.R
import com.puppetlight.oneclick.engine.ProjectionHolder

/**
 * 仅 Android 8~10 使用：持有录屏授权（MediaProjection），用于绿幕残留检测截图。
 * Android 11+ 由无障碍服务直接截图，不需要这个服务。
 */
class ProjectionService : Service() {

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Notifier.createChannels(this)
        val n = NotificationCompat.Builder(this, Notifier.CHANNEL_CAPTURE)
            .setSmallIcon(R.drawable.ic_stat_puppet)
            .setContentTitle("一键木偶打光")
            .setContentText("正在自动制作，用于检测抠像效果")
            .build()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(2001, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION)
        } else {
            startForeground(2001, n)
        }
        val code = intent?.getIntExtra(EXTRA_CODE, Activity.RESULT_CANCELED) ?: Activity.RESULT_CANCELED
        @Suppress("DEPRECATION")
        val data = intent?.getParcelableExtra<Intent>(EXTRA_DATA)
        if (code == Activity.RESULT_OK && data != null) {
            val mpm = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
            ProjectionHolder.projection?.stop()
            ProjectionHolder.projection = mpm.getMediaProjection(code, data)
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        ProjectionHolder.projection?.stop()
        ProjectionHolder.projection = null
        super.onDestroy()
    }

    companion object {
        private const val EXTRA_CODE = "code"
        private const val EXTRA_DATA = "data"

        fun start(context: Context, resultCode: Int, data: Intent) {
            val i = Intent(context, ProjectionService::class.java)
                .putExtra(EXTRA_CODE, resultCode).putExtra(EXTRA_DATA, data)
            context.startForegroundService(i)
        }

        fun stop(context: Context) {
            if (ProjectionHolder.projection != null) context.stopService(Intent(context, ProjectionService::class.java))
        }
    }
}
