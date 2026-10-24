package com.puppetlight.oneclick.service

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.widget.Toast
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.puppetlight.oneclick.R
import com.puppetlight.oneclick.ui.MainActivity

object Notifier {

    const val CHANNEL_DONE = "done"
    const val CHANNEL_CAPTURE = "capture"

    fun createChannels(context: Context) {
        val nm = context.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_DONE, "制作完成提醒", NotificationManager.IMPORTANCE_HIGH)
        )
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_CAPTURE, "截图检测", NotificationManager.IMPORTANCE_LOW)
        )
    }

    fun notifyDone(context: Context, title: String, text: String, videoUri: Uri?) {
        createChannels(context)
        val intent = if (videoUri != null) {
            Intent(Intent.ACTION_VIEW).setDataAndType(videoUri, "video/*")
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        } else {
            Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        val pi = PendingIntent.getActivity(
            context, 1, intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val n = NotificationCompat.Builder(context, CHANNEL_DONE)
            .setSmallIcon(R.drawable.ic_stat_puppet)
            .setContentTitle(title)
            .setContentText(text)
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setContentIntent(pi)
            .build()
        try {
            NotificationManagerCompat.from(context).notify(1001, n)
        } catch (_: SecurityException) {
            // 没有通知权限时只弹 Toast
        }
        Handler(Looper.getMainLooper()).post { Toast.makeText(context, "$title：$text", Toast.LENGTH_LONG).show() }
    }
}
