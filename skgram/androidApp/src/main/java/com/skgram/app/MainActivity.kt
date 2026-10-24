package com.skgram.app

import android.Manifest
import android.content.Intent
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import com.skgram.core.Accounts
import com.skgram.core.EXTRA_ACCOUNT
import com.skgram.core.EXTRA_CHAT
import com.skgram.core.Platform
import com.skgram.core.Prefs
import com.skgram.ui.Nav
import com.skgram.ui.SkgramApp

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge(statusBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT))
        if (Build.VERSION.SDK_INT >= 33) requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1)
        handle(intent)
        setContent { SkgramApp() }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handle(intent)
    }

    /** Opens the chat a notification was about. */
    private fun handle(intent: Intent?) {
        val chat = intent?.getLongExtra(EXTRA_CHAT, 0L) ?: 0L
        if (chat != 0L) Nav.pending.value = intent!!.getIntExtra(EXTRA_ACCOUNT, 0) to chat
    }

    override fun onStart() {
        super.onStart()
        Accounts.setForeground(true)
        Platform.updateBackgroundMode(Prefs.background.value && Accounts.list.value.any { it.isReady })
    }

    override fun onStop() {
        super.onStop()
        Accounts.setForeground(false)
    }
}
