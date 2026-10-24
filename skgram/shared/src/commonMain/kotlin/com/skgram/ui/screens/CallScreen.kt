package com.skgram.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Call
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.skgram.core.Account
import com.skgram.core.ActiveCall
import com.skgram.core.CallAudio
import com.skgram.core.displayName
import com.skgram.core.duration
import com.skgram.core.rememberMicPermission
import com.skgram.i18n.LocalStrings
import com.skgram.i18n.fmt
import com.skgram.ui.Toaster
import com.skgram.ui.components.Avatar
import com.skgram.ui.components.Halo
import com.skgram.ui.components.stripes
import com.skgram.ui.theme.LocalSkColors
import dev.g000sha256.tdl.dto.User
import kotlinx.coroutines.delay

/** Full-screen voice call UI for [acc]'s current call, if it has one. */
@Composable
fun CallOverlay(acc: Account) {
    val active by acc.calls.active.collectAsState()
    val c = active ?: return
    val s = LocalStrings.current
    val sk = LocalSkColors.current
    var peer by remember(c.call.userId) { mutableStateOf<User?>(null) }
    LaunchedEffect(c.call.userId) { peer = acc.user(c.call.userId) }
    val askMic = rememberMicPermission()
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(c.phase) {
        while (c.phase == ActiveCall.Phase.ACTIVE) { now = System.currentTimeMillis(); delay(1000) }
    }
    // Outgoing calls need the microphone from the start.
    LaunchedEffect(c.call.id) {
        if (c.call.isOutgoing) askMic { granted -> if (!granted) { Toaster.show(s.micDenied); acc.calls.hangUp() } }
    }

    val status = when (c.phase) {
        ActiveCall.Phase.INCOMING -> s.incomingCall.fmt(peer?.displayName() ?: "")
        ActiveCall.Phase.OUTGOING_REQUESTING -> s.callRequesting
        ActiveCall.Phase.EXCHANGING_KEYS -> s.callExchanging
        ActiveCall.Phase.CONNECTING -> s.connecting
        ActiveCall.Phase.ACTIVE -> duration(((now - c.connectedAt) / 1000).toInt().coerceAtLeast(0))
        ActiveCall.Phase.ENDED -> c.endReason ?: s.callEnded
    }

    Box(
        Modifier.fillMaxSize().background(sk.header).stripes(Color(0x14FFFFFF), 38f)
            .clickable(enabled = false) {}, // swallow touches meant for the screen below
    ) {
        Column(
            Modifier.fillMaxSize().systemBarsPadding().padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Spacer(Modifier.height(48.dp))
            Box(contentAlignment = Alignment.TopCenter) {
                Box(Modifier.padding(top = 26.dp)) {
                    Avatar(c.call.userId, peer?.displayName() ?: "?", null, size = 120.dp)
                }
                Halo(size = 54.dp)
            }
            Spacer(Modifier.height(18.dp))
            Text(peer?.displayName() ?: "…", color = Color.White, fontSize = 28.sp, fontWeight = FontWeight.Black, textAlign = TextAlign.Center)
            Spacer(Modifier.height(8.dp))
            Text(status, color = Color.White.copy(alpha = 0.9f), style = MaterialTheme.typography.titleMedium, textAlign = TextAlign.Center)
            if (c.emojis.isNotEmpty() && c.phase != ActiveCall.Phase.ENDED) {
                Spacer(Modifier.height(20.dp))
                Text(c.emojis.joinToString("  "), fontSize = 34.sp)
                Text(s.callEmojiHint, color = Color.White.copy(alpha = 0.8f), style = MaterialTheme.typography.bodySmall,
                    textAlign = TextAlign.Center, modifier = Modifier.widthIn(max = 320.dp).padding(top = 6.dp))
            }
            Spacer(Modifier.weight(1f))
            when (c.phase) {
                ActiveCall.Phase.INCOMING -> Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                    RoundButton(Icons.Filled.Close, s.decline, Color(0xFFE5484D)) { acc.calls.hangUp() }
                    RoundButton(Icons.Filled.Call, s.accept, Color(0xFF1EC28B)) {
                        askMic { granted -> if (granted) acc.calls.accept() else Toaster.show(s.micDenied) }
                    }
                }
                ActiveCall.Phase.ENDED -> RoundButton(Icons.Filled.Close, s.ok, Color.White.copy(alpha = 0.25f)) { acc.calls.dismissEnded() }
                else -> Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                    ToggleButton(if (c.muted) "🔇" else "🎙", if (c.muted) s.unmute else s.mute, c.muted) { acc.calls.toggleMute() }
                    if (CallAudio.canToggleSpeaker) ToggleButton("🔊", s.speaker, c.speaker) { acc.calls.toggleSpeaker() }
                    RoundButton(Icons.Filled.Call, s.hangUp, Color(0xFFE5484D)) { acc.calls.hangUp() }
                }
            }
            Spacer(Modifier.height(32.dp))
        }
    }
}

@Composable
private fun RoundButton(icon: ImageVector, label: String, color: Color, onClick: () -> Unit) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Box(Modifier.size(72.dp).clip(CircleShape).background(color).clickable(onClick = onClick), contentAlignment = Alignment.Center) {
            Icon(icon, label, tint = Color.White, modifier = Modifier.size(32.dp))
        }
        Text(label, color = Color.White, style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(top = 8.dp))
    }
}

@Composable
private fun ToggleButton(emoji: String, label: String, on: Boolean, onClick: () -> Unit) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Box(
            Modifier.size(72.dp).clip(CircleShape).background(if (on) Color.White else Color.White.copy(alpha = 0.22f)).clickable(onClick = onClick),
            contentAlignment = Alignment.Center,
        ) { Text(emoji, fontSize = 28.sp) }
        Text(label, color = Color.White, style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(top = 8.dp))
    }
}
