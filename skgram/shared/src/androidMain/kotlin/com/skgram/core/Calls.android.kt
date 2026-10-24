package com.skgram.core

import android.Manifest
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.media.Ringtone
import android.media.RingtoneManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.skgram.i18n.I18n
import io.github.pytgcalls.ConnectionState
import io.github.pytgcalls.NTgCalls
import io.github.pytgcalls.media.AudioDescription
import io.github.pytgcalls.media.MediaDescription
import io.github.pytgcalls.media.MediaSource
import io.github.pytgcalls.media.StreamMode
import io.github.pytgcalls.p2p.RTCServer
import java.util.concurrent.ConcurrentHashMap

actual object CallEngine {
    private val listeners = ConcurrentHashMap<Long, CallEngineListener>()

    /** One NTgCalls instance for every call; created on first use (it loads the native library). */
    private val ntg: NTgCalls? by lazy {
        runCatching {
            org.webrtc.ContextUtils.initialize(Platform.context)
            NTgCalls().apply {
                onSignalingData { key, data -> listeners[key]?.onSignalingData(data) }
                onConnectionChange { key, info ->
                    val l = listeners[key] ?: return@onConnectionChange
                    when (info.state) {
                        ConnectionState.CONNECTED -> l.onConnected()
                        ConnectionState.FAILED, ConnectionState.TIMEOUT -> l.onFailed(info.state.name.lowercase())
                        else -> Unit
                    }
                }
                onUpdateEmojis { key, emojis -> listeners[key]?.onEmojis(emojis) }
            }
        }.getOrNull()
    }

    actual val available: Boolean get() = ntg != null

    actual fun protocol(): CallProtocolInfo? {
        if (ntg == null) return null
        val p = NTgCalls.getProtocol()
        return CallProtocolInfo(p.min_layer, p.max_layer, p.udp_p2p, p.udp_reflector, p.library_versions)
    }

    actual fun start(
        key: Long,
        encryptionKey: ByteArray,
        outgoing: Boolean,
        servers: List<RtcServer>,
        versions: List<String>,
        p2pAllowed: Boolean,
        customParameters: String?,
        listener: CallEngineListener,
    ): Result<Unit> = runCatching {
        val n = ntg ?: error("call engine unavailable")
        listeners[key] = listener
        val devices = NTgCalls.getMediaDevices()
        val mic = devices.microphone.firstOrNull()?.metadata ?: error("no microphone")
        val speaker = devices.speaker.firstOrNull()?.metadata ?: error("no speaker")
        n.createP2pCall(key)
        n.setStreamSources(key, StreamMode.CAPTURE,
            MediaDescription(AudioDescription(MediaSource.DEVICE, 48000, 2, mic, false), null, null, null))
        n.setStreamSources(key, StreamMode.PLAYBACK,
            MediaDescription(null, AudioDescription(MediaSource.DEVICE, 48000, 2, speaker, false), null, null))
        n.skipExchange(key, encryptionKey, outgoing)
        n.connectP2p(
            key,
            servers.map { RTCServer(it.id, it.ipv4, it.ipv6, it.port, it.username, it.password, it.turn, it.stun, it.tcp, it.peerTag) },
            versions, p2pAllowed, customParameters,
        )
    }.onFailure { listeners.remove(key) }

    actual fun receiveSignalingData(key: Long, data: ByteArray) {
        runCatching { ntg?.sendSignalingData(key, data) }
    }

    actual fun setMuted(key: Long, muted: Boolean) {
        runCatching { if (muted) ntg?.mute(key) else ntg?.unmute(key) }
    }

    actual fun stop(key: Long) {
        listeners.remove(key)
        runCatching { ntg?.stop(key) }
    }
}

actual object CallAudio {
    private val audio get() = Platform.context.getSystemService(AudioManager::class.java)
    private var ringtone: Ringtone? = null

    actual val canToggleSpeaker = true

    actual fun callStarted() {
        // A microphone-type foreground service keeps the call going when SKGram is in the background.
        val ctx = Platform.context
        // A microphone service may only start once the permission is granted (otherwise Android kills the app).
        if (ContextCompat.checkSelfPermission(ctx, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
            runCatching { ContextCompat.startForegroundService(ctx, Intent(ctx, CallService::class.java)) }
        }
        audio.mode = AudioManager.MODE_IN_COMMUNICATION
    }

    actual fun callEnded() {
        setSpeaker(false)
        audio.mode = AudioManager.MODE_NORMAL
        Platform.context.stopService(Intent(Platform.context, CallService::class.java))
    }

    actual fun setSpeaker(on: Boolean) {
        val am = audio
        if (Build.VERSION.SDK_INT >= 31) {
            if (on) am.availableCommunicationDevices.firstOrNull { it.type == AudioDeviceInfo.TYPE_BUILTIN_SPEAKER }?.let(am::setCommunicationDevice)
            else am.clearCommunicationDevice()
        } else {
            @Suppress("DEPRECATION")
            am.isSpeakerphoneOn = on
        }
    }

    actual fun startRinging() {
        stopRinging()
        ringtone = runCatching {
            RingtoneManager.getRingtone(Platform.context, RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE))
        }.getOrNull()?.also { r ->
            if (Build.VERSION.SDK_INT >= 28) r.isLooping = true
            r.play()
        }
    }

    actual fun stopRinging() {
        ringtone?.stop()
        ringtone = null
    }
}

@Composable
actual fun rememberMicPermission(): ((Boolean) -> Unit) -> Unit {
    val ctx = LocalContext.current
    val pending = remember { arrayOfNulls<(Boolean) -> Unit>(1) }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        pending[0]?.invoke(granted)
        pending[0] = null
    }
    return remember(launcher) {
        { cb ->
            if (ContextCompat.checkSelfPermission(ctx, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) cb(true)
            else { pending[0] = cb; launcher.launch(Manifest.permission.RECORD_AUDIO) }
        }
    }
}

/** Ongoing-call notification; holds the microphone while SKGram isn't on screen. */
class CallService : android.app.Service() {
    override fun onBind(intent: Intent?) = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val open = packageManager.getLaunchIntentForPackage(packageName)
        val pi = PendingIntent.getActivity(this, 2, open, PendingIntent.FLAG_IMMUTABLE)
        val n = NotificationCompat.Builder(this, CHANNEL_MESSAGES)
            .setSmallIcon(com.skgram.shared.R.drawable.ic_stat_skgram)
            .setContentTitle("SKGram")
            .setContentText(I18n.strings.callAction)
            .setOngoing(true)
            .setCategory(NotificationCompat.CATEGORY_CALL)
            .setContentIntent(pi)
            .build()
        val type = if (Build.VERSION.SDK_INT >= 30) android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE else 0
        if (runCatching { ServiceCompat.startForeground(this, 3, n, type) }.isFailure) stopSelf()
        return START_NOT_STICKY
    }
}

