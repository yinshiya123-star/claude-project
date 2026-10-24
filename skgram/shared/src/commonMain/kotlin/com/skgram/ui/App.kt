package com.skgram.ui

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.skgram.core.Account
import com.skgram.core.Accounts
import com.skgram.core.Platform
import com.skgram.core.Prefs
import com.skgram.core.displayName
import com.skgram.i18n.Lang
import com.skgram.i18n.LocalStrings
import com.skgram.i18n.fmt
import com.skgram.ui.screens.AuthFlow
import com.skgram.ui.screens.ChatListScreen
import com.skgram.ui.screens.ChatScreen
import com.skgram.ui.screens.NewChatScreen
import com.skgram.ui.screens.SettingsScreen
import com.skgram.ui.theme.SkgramTheme
import dev.g000sha256.tdl.dto.AuthorizationStateReady
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow

val LocalAccount = staticCompositionLocalOf<Account> { error("no account") }

/** Short messages shown at the bottom of the window. */
object Toaster {
    val messages = MutableSharedFlow<String>(extraBufferCapacity = 8)
    fun show(text: String) { messages.tryEmit(text) }
}

/** Requests to open a chat from outside the UI (e.g. tapping a notification): account id to chat id. */
object Nav {
    val pending = MutableStateFlow<Pair<Int, Long>?>(null)
}

private const val LIST = 0L
private const val SETTINGS = Long.MIN_VALUE
private const val NEW_CHAT = Long.MIN_VALUE + 1

@Composable
fun SkgramApp() {
    val theme by Prefs.theme.collectAsState()
    val lang by Prefs.lang.collectAsState()
    val account by Accounts.current.collectAsState()
    val accounts by Accounts.list.collectAsState()
    val pending by Nav.pending.collectAsState()
    val strings = (lang ?: Lang.forLocale(Platform.systemLocale)).strings

    LaunchedEffect(pending) {
        val target = pending?.let { p -> accounts.firstOrNull { it.id == p.first } } ?: return@LaunchedEffect
        if (target !== account) Accounts.switch(target)
    }

    SkgramTheme(theme) {
        CompositionLocalProvider(LocalStrings provides strings) {
            Box(Modifier.fillMaxSize()) {
                val acc = account
                if (acc != null) key(acc.id) {
                    CompositionLocalProvider(LocalAccount provides acc) { AccountRoot(acc) }
                }
                ToastHost(Modifier.align(Alignment.BottomCenter))
                accounts.forEach { IncomingCall(it) }
            }
        }
    }
}

@Composable
private fun AccountRoot(acc: Account) {
    val auth by acc.auth.collectAsState()
    var screen by rememberSaveable { mutableStateOf(LIST) }
    val pending by Nav.pending.collectAsState()
    LaunchedEffect(pending, auth) {
        val p = pending ?: return@LaunchedEffect
        if (p.first == acc.id && auth is AuthorizationStateReady) {
            acc.ensureChat(p.second)
            screen = p.second
            Nav.pending.value = null
        }
    }
    if (auth !is AuthorizationStateReady) {
        AuthFlow(auth)
        return
    }
    AnimatedContent(
        targetState = screen,
        transitionSpec = {
            if (targetState != LIST) (slideInHorizontally { it / 3 } + fadeIn()) togetherWith fadeOut()
            else fadeIn() togetherWith (slideOutHorizontally { it / 3 } + fadeOut())
        },
        label = "screen",
    ) { s ->
        when (s) {
            LIST -> ChatListScreen(onOpen = { screen = it }, onSettings = { screen = SETTINGS }, onNewChat = { screen = NEW_CHAT })
            SETTINGS -> SettingsScreen(onBack = { screen = LIST })
            NEW_CHAT -> NewChatScreen(onOpen = { screen = it }, onBack = { screen = LIST })
            else -> ChatScreen(s, onBack = { screen = LIST })
        }
    }
}

@Composable
private fun ToastHost(modifier: Modifier) {
    val host = remember { SnackbarHostState() }
    LaunchedEffect(Unit) { Toaster.messages.collect { host.showSnackbar(it) } }
    SnackbarHost(host, modifier.navigationBarsPadding().padding(bottom = 72.dp))
}

/** Calls can't be answered here (no call engine yet), but the user can see them and decline. */
@Composable
private fun IncomingCall(acc: Account) {
    val call by acc.incomingCall.collectAsState()
    val c = call ?: return
    val s = LocalStrings.current
    var name by remember(c.id) { mutableStateOf("") }
    LaunchedEffect(c.id) { name = acc.user(c.userId)?.displayName() ?: "" }
    AlertDialog(
        onDismissRequest = {},
        title = { Text(s.incomingCall.fmt(name)) },
        text = { Text(s.callsUnsupported) },
        confirmButton = { TextButton(onClick = { acc.declineCall(c) }) { Text(s.decline) } },
    )
}
