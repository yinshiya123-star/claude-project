package com.skgram.core

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import com.sun.jna.Callback
import com.sun.jna.Library
import com.sun.jna.Memory
import com.sun.jna.Native
import com.sun.jna.Pointer
import com.sun.jna.Structure
import com.sun.jna.ptr.ByteByReference
import java.io.File
import java.util.concurrent.ConcurrentHashMap

// JNA mirror of the parts of ntgcalls.h (NTgCalls C API) SKGram uses. size_t is 64-bit on every supported desktop.
// C `bool` arguments are declared as Byte: JNA passes a Java `true` as -1, which the library misreads.

@Suppress("FunctionName")
private interface Ntg : Library {
    fun ntg_get_version(): String
    fun ntg_last_error(): String?
    fun ntg_set_log_callback(cb: LogCb, user: Pointer?)
    fun ntg_instance_create(): Pointer
    fun ntg_instance_destroy(h: Pointer)
    fun ntg_get_protocol(out: NtgProtocol): Int
    fun ntg_get_media_devices(out: NtgMediaDevices): Int
    fun ntg_create_p2p_call(h: Pointer, userId: Long): Int
    fun ntg_set_stream_sources(h: Pointer, chatId: Long, mode: Int, media: NtgMediaDescription.ByValue): Int
    fun ntg_skip_exchange(h: Pointer, userId: Long, key: ByteArray, keyLen: Long, outgoing: Byte): Int
    fun ntg_connect_p2p(
        h: Pointer, userId: Long,
        servers: Pointer?, serversLen: Long,
        versions: Array<String>, versionsLen: Long,
        p2pAllowed: Byte, customParameters: String?,
    ): Int
    fun ntg_send_signaling_data(h: Pointer, chatId: Long, data: ByteArray, len: Long): Int
    fun ntg_mute(h: Pointer, chatId: Long, out: ByteByReference): Int
    fun ntg_unmute(h: Pointer, chatId: Long, out: ByteByReference): Int
    fun ntg_stop(h: Pointer, chatId: Long): Int
    fun ntg_on_signaling_data_callback(h: Pointer, cb: SignalingCb, user: Pointer?): Int
    fun ntg_on_connection_change_callback(h: Pointer, cb: ConnectionCb, user: Pointer?): Int
    fun ntg_on_update_emojis_callback(h: Pointer, cb: EmojisCb, user: Pointer?): Int
}

private const val NTG_MEDIA_SOURCE_DEVICE = 4
private const val NTG_STREAM_MODE_CAPTURE = 0
private const val NTG_STREAM_MODE_PLAYBACK = 1
private const val NTG_CONNECTION_STATE_CONNECTED = 1
private const val NTG_CONNECTION_STATE_FAILED = 2
private const val NTG_CONNECTION_STATE_TIMEOUT = 3

@Structure.FieldOrder("min_layer", "max_layer", "udp_p2p", "udp_reflector", "library_versions", "library_versions_len")
internal class NtgProtocol : Structure() {
    @JvmField var min_layer = 0
    @JvmField var max_layer = 0
    @JvmField var udp_p2p: Byte = 0
    @JvmField var udp_reflector: Byte = 0
    @JvmField var library_versions: Pointer? = null
    @JvmField var library_versions_len = 0L
}

@Structure.FieldOrder("name", "metadata")
internal class NtgDeviceInfo(p: Pointer? = null) : Structure(p) {
    @JvmField var name: String? = null
    @JvmField var metadata: String? = null
    init { if (p != null) read() }
}

@Structure.FieldOrder("microphone", "microphone_len", "speaker", "speaker_len", "camera", "camera_len", "screen", "screen_len")
internal class NtgMediaDevices : Structure() {
    @JvmField var microphone: Pointer? = null
    @JvmField var microphone_len = 0L
    @JvmField var speaker: Pointer? = null
    @JvmField var speaker_len = 0L
    @JvmField var camera: Pointer? = null
    @JvmField var camera_len = 0L
    @JvmField var screen: Pointer? = null
    @JvmField var screen_len = 0L

    fun list(p: Pointer?, len: Long): List<NtgDeviceInfo> {
        if (p == null || len <= 0) return emptyList()
        val first = NtgDeviceInfo(p)
        @Suppress("UNCHECKED_CAST")
        return (first.toArray(len.toInt()) as Array<NtgDeviceInfo>).onEach { it.read() }.toList()
    }
}

@Structure.FieldOrder("media_source", "sample_rate", "channel_count", "input", "keep_open")
internal open class NtgAudioDescription : Structure() {
    @JvmField var media_source = 0
    @JvmField var sample_rate = 0
    @JvmField var channel_count: Byte = 0
    @JvmField var input: String? = null
    @JvmField var keep_open: Byte = 0

    class ByReference : NtgAudioDescription(), Structure.ByReference
}

@Structure.FieldOrder("microphone", "speaker", "camera", "screen")
internal open class NtgMediaDescription : Structure() {
    @JvmField var microphone: NtgAudioDescription.ByReference? = null
    @JvmField var speaker: NtgAudioDescription.ByReference? = null
    @JvmField var camera: Pointer? = null
    @JvmField var screen: Pointer? = null

    class ByValue : NtgMediaDescription(), Structure.ByValue
}

@Structure.FieldOrder("id", "ipv4", "ipv6", "port", "username", "password", "turn", "stun", "tcp", "peer_tag", "peer_tag_len")
internal class NtgRtcServer : Structure() {
    @JvmField var id = 0L
    @JvmField var ipv4: String? = null
    @JvmField var ipv6: String? = null
    @JvmField var port: Short = 0
    @JvmField var username: String? = null
    @JvmField var password: String? = null
    @JvmField var turn: Byte = 0
    @JvmField var stun: Byte = 0
    @JvmField var tcp: Byte = 0
    @JvmField var peer_tag: Pointer? = null
    @JvmField var peer_tag_len = 0L
}

@Structure.FieldOrder("state", "kind")
internal open class NtgConnectionInfo : Structure() {
    @JvmField var state = 0
    @JvmField var kind = 0

    class ByValue : NtgConnectionInfo(), Structure.ByValue
}

private fun interface SignalingCb : Callback {
    fun invoke(h: Pointer?, chatId: Long, data: Pointer?, len: Long, user: Pointer?)
}

private fun interface ConnectionCb : Callback {
    fun invoke(h: Pointer?, chatId: Long, info: NtgConnectionInfo.ByValue, user: Pointer?)
}

@Structure.FieldOrder("level", "source", "file", "line", "message")
internal open class NtgLogMessage : Structure() {
    @JvmField var level = 0
    @JvmField var source = 0
    @JvmField var file: String? = null
    @JvmField var line = 0
    @JvmField var message: String? = null

    class ByValue : NtgLogMessage(), Structure.ByValue
}

private fun interface LogCb : Callback {
    fun invoke(message: NtgLogMessage.ByValue, user: Pointer?)
}

private fun interface EmojisCb : Callback {
    fun invoke(h: Pointer?, chatId: Long, emojis: String?, user: Pointer?)
}

private fun Boolean.b(): Byte = if (this) 1 else 0

/** Where the packaged app keeps ntgcalls: Compose's per-OS resources folder (or SKGRAM_NTGCALLS_DIR when developing). */
private fun findLibrary(): File? {
    val name = System.mapLibraryName("ntgcalls")
    return listOfNotNull(System.getProperty("compose.application.resources.dir"), System.getenv("SKGRAM_NTGCALLS_DIR"))
        .map { File(it, name) }
        .firstOrNull { it.isFile }
}

actual object CallEngine {
    private val listeners = ConcurrentHashMap<Long, CallEngineListener>()
    // Kept in fields so the JVM never collects them while native code holds them.
    private val signalingCb = SignalingCb { _, key, data, len, _ ->
        if (data != null) listeners[key]?.onSignalingData(data.getByteArray(0, len.toInt()))
    }
    private val connectionCb = ConnectionCb { _, key, info, _ ->
        val l = listeners[key] ?: return@ConnectionCb
        when (info.state) {
            NTG_CONNECTION_STATE_CONNECTED -> l.onConnected()
            NTG_CONNECTION_STATE_FAILED -> l.onFailed("failed")
            NTG_CONNECTION_STATE_TIMEOUT -> l.onFailed("timeout")
        }
    }
    private val emojisCb = EmojisCb { _, key, emojis, _ -> if (emojis != null) listeners[key]?.onEmojis(emojis) }

    // Set SKGRAM_CALL_LOG=1 to print the call library's own log (for diagnosing call problems).
    private val logCb = LogCb { m, _ -> System.err.println("[ntgcalls ${m.level}] ${m.file}:${m.line} ${m.message}") }

    private val lib: Ntg? by lazy {
        runCatching {
            findLibrary()?.let { Native.load(it.absolutePath, Ntg::class.java) }
                ?.also { if (System.getenv("SKGRAM_CALL_LOG") != null) it.ntg_set_log_callback(logCb, null) }
        }.getOrNull()
    }

    /** One native instance per call, so calls never share state. */
    private val instances = ConcurrentHashMap<Long, Pointer>()

    private fun newInstance(ntg: Ntg): Pointer = ntg.ntg_instance_create().also { h ->
        ntg.ntg_on_signaling_data_callback(h, signalingCb, null)
        ntg.ntg_on_connection_change_callback(h, connectionCb, null)
        ntg.ntg_on_update_emojis_callback(h, emojisCb, null)
    }

    actual val available: Boolean get() = lib != null

    /** Version string of the loaded library, for diagnostics. */
    val version: String? get() = lib?.ntg_get_version()

    private fun check(ntg: Ntg, code: Int, what: String) {
        if (code != 0) error("$what: ${ntg.ntg_last_error() ?: code}")
    }

    actual fun protocol(): CallProtocolInfo? {
        val ntg = lib ?: return null
        val p = NtgProtocol()
        if (ntg.ntg_get_protocol(p) != 0) return null
        p.read()
        val versions = p.library_versions?.getStringArray(0, p.library_versions_len.toInt())?.toList() ?: emptyList()
        return CallProtocolInfo(p.min_layer, p.max_layer, p.udp_p2p != 0.toByte(), p.udp_reflector != 0.toByte(), versions)
    }

    /** Microphones and speakers the library can see (metadata strings identify them). */
    fun devices(): Pair<List<String>, List<String>> {
        val ntg = lib ?: return emptyList<String>() to emptyList()
        val d = NtgMediaDevices()
        if (ntg.ntg_get_media_devices(d) != 0) return emptyList<String>() to emptyList()
        d.read()
        return d.list(d.microphone, d.microphone_len).mapNotNull { it.metadata } to d.list(d.speaker, d.speaker_len).mapNotNull { it.metadata }
    }

    private fun audio(input: String) = NtgAudioDescription.ByReference().apply {
        media_source = NTG_MEDIA_SOURCE_DEVICE
        sample_rate = 48000
        channel_count = 2
        this.input = input
        keep_open = 0
        write()
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
        val ntg = lib ?: error("call engine unavailable")
        val (mics, speakers) = devices()
        val mic = mics.firstOrNull() ?: error("no microphone")
        val speaker = speakers.firstOrNull() ?: error("no speaker")
        listeners[key] = listener
        val h = newInstance(ntg).also { instances[key] = it }
        check(ntg, ntg.ntg_create_p2p_call(h, key), "create call")
        check(ntg, ntg.ntg_set_stream_sources(h, key, NTG_STREAM_MODE_CAPTURE,
            NtgMediaDescription.ByValue().apply { microphone = audio(mic) }), "microphone")
        check(ntg, ntg.ntg_set_stream_sources(h, key, NTG_STREAM_MODE_PLAYBACK,
            NtgMediaDescription.ByValue().apply { this.speaker = audio(speaker) }), "speaker")
        check(ntg, ntg.ntg_skip_exchange(h, key, encryptionKey, encryptionKey.size.toLong(), outgoing.b()), "encryption")

        val array = if (servers.isEmpty()) null else {
            @Suppress("UNCHECKED_CAST")
            (NtgRtcServer().toArray(servers.size) as Array<NtgRtcServer>).also { arr ->
                servers.forEachIndexed { i, s ->
                    arr[i].apply {
                        id = s.id; ipv4 = s.ipv4; ipv6 = s.ipv6; port = s.port.toShort()
                        username = s.username; password = s.password
                        turn = s.turn.b(); stun = s.stun.b(); tcp = s.tcp.b()
                        s.peerTag?.takeIf { it.isNotEmpty() }?.let { tag ->
                            peer_tag = Memory(tag.size.toLong()).apply { write(0, tag, 0, tag.size) }
                            peer_tag_len = tag.size.toLong()
                        }
                        write()
                    }
                }
            }
        }
        check(ntg, ntg.ntg_connect_p2p(h, key, array?.get(0)?.pointer, servers.size.toLong(),
            versions.toTypedArray(), versions.size.toLong(), p2pAllowed.b(), customParameters), "connect")
    }.onFailure { stop(key) }

    actual fun receiveSignalingData(key: Long, data: ByteArray) {
        val ntg = lib ?: return
        val h = instances[key] ?: return
        ntg.ntg_send_signaling_data(h, key, data, data.size.toLong())
    }

    actual fun setMuted(key: Long, muted: Boolean) {
        val ntg = lib ?: return
        val h = instances[key] ?: return
        if (muted) ntg.ntg_mute(h, key, ByteByReference()) else ntg.ntg_unmute(h, key, ByteByReference())
    }

    actual fun stop(key: Long) {
        listeners.remove(key)
        val ntg = lib ?: return
        val h = instances.remove(key) ?: return
        ntg.ntg_stop(h, key)
        ntg.ntg_instance_destroy(h)
    }
}

/** Desktop plays calls through the default devices; there's no earpiece/speaker switch or system ringtone. */
actual object CallAudio {
    actual val canToggleSpeaker = false
    actual fun callStarted() = Unit
    actual fun callEnded() = Unit
    actual fun setSpeaker(on: Boolean) = Unit
    actual fun startRinging() { java.awt.Toolkit.getDefaultToolkit().beep() }
    actual fun stopRinging() = Unit
}

@Composable
actual fun rememberMicPermission(): ((Boolean) -> Unit) -> Unit = remember { { cb -> cb(true) } }
