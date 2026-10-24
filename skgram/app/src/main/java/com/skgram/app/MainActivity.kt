package com.skgram.app

import android.content.Intent
import android.os.Bundle
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import com.skgram.app.core.Prefs
import com.skgram.app.core.Tg
import com.skgram.app.ui.screens.AuthFlow
import com.skgram.app.ui.screens.ChatListScreen
import com.skgram.app.ui.screens.ChatScreen
import com.skgram.app.ui.screens.SettingsScreen
import com.skgram.app.ui.theme.SkgramTheme
import dev.g000sha256.tdl.dto.AuthorizationStateClosed
import dev.g000sha256.tdl.dto.AuthorizationStateReady

class MainActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge(statusBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT))
        setContent {
            val theme by Prefs.theme.collectAsState()
            val auth by Tg.auth.collectAsState()
            SkgramTheme(theme) {
                // Screen stack: 0 = chat list, -1 = settings, other = open chat id.
                var screen by rememberSaveable { mutableStateOf(0L) }
                LaunchedEffect(auth) {
                    if (auth is AuthorizationStateReady) Tg.setOnline(true)
                    // After logging out TDLib closes the instance for good; start over with a fresh process.
                    if (auth is AuthorizationStateClosed) restart()
                }
                if (auth !is AuthorizationStateReady) {
                    AuthFlow(auth)
                } else {
                    AnimatedContent(
                        targetState = screen,
                        transitionSpec = {
                            if (targetState != 0L) (slideInHorizontally { it / 3 } + fadeIn()) togetherWith fadeOut()
                            else fadeIn() togetherWith (slideOutHorizontally { it / 3 } + fadeOut())
                        },
                        label = "screen",
                    ) { s ->
                        when (s) {
                            0L -> ChatListScreen(onOpen = { screen = it }, onSettings = { screen = SETTINGS })
                            SETTINGS -> SettingsScreen(onBack = { screen = 0L })
                            else -> ChatScreen(s, onBack = { screen = 0L })
                        }
                    }
                }
            }
        }
    }

    override fun onStart() { super.onStart(); Tg.setOnline(true) }
    override fun onStop() { super.onStop(); Tg.setOnline(false) }

    private fun restart() {
        startActivity(Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK))
        Runtime.getRuntime().exit(0)
    }

    private companion object {
        const val SETTINGS = Long.MIN_VALUE
    }
}
