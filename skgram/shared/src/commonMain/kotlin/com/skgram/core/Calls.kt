package com.skgram.core

import androidx.compose.runtime.Composable
import com.skgram.i18n.I18n
import com.skgram.i18n.fmt
import dev.g000sha256.tdl.dto.Call
import dev.g000sha256.tdl.dto.CallDiscardReasonDeclined
import dev.g000sha256.tdl.dto.CallProtocol
import dev.g000sha256.tdl.dto.CallServerTypeTelegramReflector
import dev.g000sha256.tdl.dto.CallServerTypeWebrtc
import dev.g000sha256.tdl.dto.CallStateDiscarded
import dev.g000sha256.tdl.dto.CallStateError
import dev.g000sha256.tdl.dto.CallStateExchangingKeys
import dev.g000sha256.tdl.dto.CallStateHangingUp
import dev.g000sha256.tdl.dto.CallStatePending
import dev.g000sha256.tdl.dto.CallStateReady
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

/** What the call library supports, sent to Telegram when starting or accepting a call. */
data class CallProtocolInfo(
    val minLayer: Int,
    val maxLayer: Int,
    val udpP2p: Boolean,
    val udpReflector: Boolean,
    val versions: List<String>,
)

data class RtcServer(
    val id: Long,
    val ipv4: String,
    val ipv6: String,
    val port: Int,
    val username: String,
    val password: String,
    val turn: Boolean,
    val stun: Boolean,
    val tcp: Boolean,
    val peerTag: ByteArray?,
)

interface CallEngineListener {
    fun onSignalingData(data: ByteArray)
    fun onConnected()
    fun onFailed(reason: String)
    fun onEmojis(emojis: String)
}

/**
 * The media side of a voice call (NTgCalls/WebRTC). Telegram's signalling and key exchange are done by TDLib;
 * this gets the finished encryption key and relay servers, and carries the audio. [key] identifies the call.
 */
expect object CallEngine {
    /** False where no call library exists for this machine (e.g. Intel Macs). */
    val available: Boolean
    fun protocol(): CallProtocolInfo?
    fun start(
        key: Long,
        encryptionKey: ByteArray,
        outgoing: Boolean,
        servers: List<RtcServer>,
        versions: List<String>,
        p2pAllowed: Boolean,
        customParameters: String?,
        listener: CallEngineListener,
    ): Result<Unit>
    fun receiveSignalingData(key: Long, data: ByteArray)
    fun setMuted(key: Long, muted: Boolean)
    fun stop(key: Long)
}

/** Phone-call audio routing, ringtone and keeping the app alive during a call. */
expect object CallAudio {
    val canToggleSpeaker: Boolean
    /** The call is connecting: switch to call audio and keep the app alive. */
    fun callStarted()
    fun callEnded()
    fun setSpeaker(on: Boolean)
    fun startRinging()
    fun stopRinging()
}

/** Asks for the microphone if needed; the callback gets whether it may be used. */
@Composable
expect fun rememberMicPermission(): ((Boolean) -> Unit) -> Unit

/** UI state of the one call an account can have at a time. */
data class ActiveCall(
    val call: Call,
    val phase: Phase,
    val emojis: List<String> = emptyList(),
    val connectedAt: Long = 0,
    val muted: Boolean = false,
    val speaker: Boolean = false,
    val endReason: String? = null,
) {
    enum class Phase { INCOMING, OUTGOING_REQUESTING, EXCHANGING_KEYS, CONNECTING, ACTIVE, ENDED }
}

/** Telegram voice calls for one account: TDLib signalling on one side, [CallEngine] media on the other. */
class CallManager(private val account: Account) {
    private val _active = MutableStateFlow<ActiveCall?>(null)
    val active: StateFlow<ActiveCall?> = _active

    private fun engineKey(callId: Int) = (account.id.toLong() shl 32) or (callId.toLong() and 0xFFFFFFFFL)

    private fun tdProtocol(): CallProtocol? = CallEngine.protocol()?.let {
        CallProtocol(it.udpP2p, it.udpReflector, it.minLayer, it.maxLayer, it.versions.toTypedArray())
    }

    fun startCall(userId: Long) {
        val protocol = tdProtocol() ?: return Unit.also { com.skgram.ui.Toaster.show(I18n.strings.callsUnsupported) }
        account.scope.launch {
            runCatching { account.client.createCall(userId, protocol, false).getOrThrow() }
                .onFailure { com.skgram.ui.Toaster.show(I18n.strings.callFailed.fmt(it.message)) }
        }
    }

    fun accept() {
        val c = _active.value ?: return
        val protocol = tdProtocol() ?: return
        CallAudio.stopRinging()
        account.scope.launch {
            runCatching { account.client.acceptCall(c.call.id, protocol).getOrThrow() }
                .onFailure { com.skgram.ui.Toaster.show(I18n.strings.callFailed.fmt(it.message)) }
        }
    }

    fun hangUp() {
        val c = _active.value ?: return
        CallAudio.stopRinging()
        val duration = if (c.connectedAt > 0) ((System.currentTimeMillis() - c.connectedAt) / 1000).toInt() else 0
        account.scope.launch {
            account.client.discardCall(c.call.id, false, "", duration, false, 0)
        }
        finish(c.call.id, I18n.strings.callEnded)
    }

    fun toggleMute() {
        val c = _active.value ?: return
        CallEngine.setMuted(engineKey(c.call.id), !c.muted)
        _active.value = c.copy(muted = !c.muted)
    }

    fun toggleSpeaker() {
        val c = _active.value ?: return
        CallAudio.setSpeaker(!c.speaker)
        _active.value = c.copy(speaker = !c.speaker)
    }

    fun dismissEnded() {
        if (_active.value?.phase == ActiveCall.Phase.ENDED) _active.value = null
    }

    /** From TDLib's updateCall. */
    fun onCall(call: Call) {
        val current = _active.value
        // Ignore other calls while one is in progress (TDLib discards them as busy for us).
        if (current != null && current.call.id != call.id && current.phase != ActiveCall.Phase.ENDED) return
        when (val st = call.state) {
            is CallStatePending -> {
                val phase = if (call.isOutgoing) ActiveCall.Phase.OUTGOING_REQUESTING else ActiveCall.Phase.INCOMING
                if (current?.call?.id != call.id) {
                    _active.value = ActiveCall(call, phase)
                    if (!call.isOutgoing) CallAudio.startRinging()
                } else {
                    _active.value = current.copy(call = call, phase = phase)
                }
            }
            is CallStateExchangingKeys -> {
                CallAudio.stopRinging()
                _active.value = (current ?: ActiveCall(call, ActiveCall.Phase.EXCHANGING_KEYS))
                    .copy(call = call, phase = ActiveCall.Phase.EXCHANGING_KEYS)
            }
            is CallStateReady -> {
                CallAudio.stopRinging()
                val base = current ?: ActiveCall(call, ActiveCall.Phase.CONNECTING)
                if (base.phase == ActiveCall.Phase.CONNECTING || base.phase == ActiveCall.Phase.ACTIVE) return
                _active.value = base.copy(call = call, phase = ActiveCall.Phase.CONNECTING, emojis = st.emojis.toList())
                CallAudio.callStarted()
                connect(call, st)
            }
            is CallStateHangingUp -> Unit
            is CallStateDiscarded -> finish(call.id,
                if (st.reason is CallDiscardReasonDeclined) I18n.strings.callDeclined else I18n.strings.callEnded)
            is CallStateError -> finish(call.id, I18n.strings.callFailed.fmt(st.error.message))
            else -> Unit
        }
    }

    private fun connect(call: Call, st: CallStateReady) {
        val servers = st.servers.mapNotNull { s ->
            when (val t = s.type) {
                // NTgCalls 3.0.5 crashes on hang-up if a TCP relay never connected; Telegram always offers UDP relays too.
                is CallServerTypeTelegramReflector -> if (t.isTcp) null else RtcServer(s.id, s.ipAddress, s.ipv6Address, s.port, "", "", false, false, t.isTcp, t.peerTag)
                is CallServerTypeWebrtc -> RtcServer(s.id, s.ipAddress, s.ipv6Address, s.port, t.username, t.password, t.supportsTurn, t.supportsStun, false, null)
                else -> null
            }
        }
        val key = engineKey(call.id)
        val result = CallEngine.start(
            key, st.encryptionKey, call.isOutgoing, servers, st.protocol.libraryVersions.toList(),
            st.allowP2p, st.customParameters.ifEmpty { null },
            object : CallEngineListener {
                override fun onSignalingData(data: ByteArray) {
                    account.scope.launch { account.client.sendCallSignalingData(call.id, data) }
                }

                override fun onConnected() {
                    val c = _active.value ?: return
                    if (c.call.id == call.id && c.phase != ActiveCall.Phase.ACTIVE) {
                        _active.value = c.copy(phase = ActiveCall.Phase.ACTIVE, connectedAt = System.currentTimeMillis())
                    }
                }

                override fun onFailed(reason: String) {
                    if (_active.value?.call?.id == call.id) hangUpWith(reason)
                }

                override fun onEmojis(emojis: String) {
                    val c = _active.value ?: return
                    if (c.call.id == call.id && c.emojis.isEmpty()) _active.value = c.copy(emojis = emojis.chunkedEmojis())
                }
            },
        )
        result.onFailure { hangUpWith(it.message ?: "") }
    }

    private fun hangUpWith(reason: String) {
        val c = _active.value ?: return
        account.scope.launch { account.client.discardCall(c.call.id, true, "", 0, false, 0) }
        finish(c.call.id, I18n.strings.callFailed.fmt(reason))
    }

    fun onSignalingData(callId: Int, data: ByteArray) {
        if (_active.value?.call?.id == callId) CallEngine.receiveSignalingData(engineKey(callId), data)
    }

    private fun finish(callId: Int, reason: String) {
        val c = _active.value ?: return
        if (c.call.id != callId || c.phase == ActiveCall.Phase.ENDED) return
        CallEngine.stop(engineKey(callId))
        CallAudio.stopRinging()
        CallAudio.callEnded()
        _active.value = c.copy(phase = ActiveCall.Phase.ENDED, endReason = reason)
        // Leave "call ended" on screen briefly, then close it.
        account.scope.launch {
            delay(1800)
            if (_active.value?.call?.id == callId && _active.value?.phase == ActiveCall.Phase.ENDED) _active.value = null
        }
    }
}

/** Splits an emoji fingerprint string into its four emoji (each may be several code points). */
private fun String.chunkedEmojis(): List<String> {
    val it = java.text.BreakIterator.getCharacterInstance()
    it.setText(this)
    val out = mutableListOf<String>()
    var start = it.first()
    var end = it.next()
    while (end != java.text.BreakIterator.DONE) {
        substring(start, end).takeIf { s -> s.isNotBlank() }?.let(out::add)
        start = end
        end = it.next()
    }
    return out
}
