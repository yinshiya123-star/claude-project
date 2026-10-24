package com.puppetlight.oneclick.service

import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import com.puppetlight.oneclick.R
import com.puppetlight.oneclick.ui.MainActivity

/**
 * 制作期间的前台服务（常驻通知）。
 * 小米 MIUI/HyperOS 等系统会在 App 退到后台后清理进程，连带无障碍服务和悬浮窗一起消失；
 * 前台服务能显著降低被清理的概率。
 */
class KeepAliveService : Service() {

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Notifier.createChannels(this)
        val pi = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE,
        )
        val n = NotificationCompat.Builder(this, Notifier.CHANNEL_CAPTURE)
            .setSmallIcon(R.drawable.ic_stat_puppet)
            .setContentTitle("一键木偶打光")
            .setContentText("正在自动制作，请勿清理后台")
            .setOngoing(true)
            .setContentIntent(pi)
            .build()
        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            startForeground(ID, n)
        }
        return START_NOT_STICKY
    }

    companion object {
        private const val ID = 2002

        fun start(context: Context) {
            runCatching { context.startForegroundService(Intent(context, KeepAliveService::class.java)) }
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, KeepAliveService::class.java))
        }
    }
}
