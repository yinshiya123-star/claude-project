package com.skgram.core

import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.ImageBitmap
import java.util.Locale

/** A message notification to show (or replace) in the system tray. */
data class ChatNotification(
    val key: Int,
    val accountId: Int,
    val chatId: Long,
    val title: String,
    val lines: List<String>,
)

/** A file the user picked to send, already copied to a path TDLib can read. */
data class PickedFile(
    val path: String,
    val name: String,
    val kind: Kind,
    val width: Int = 0,
    val height: Int = 0,
    val durationSec: Int = 0,
) {
    enum class Kind { PHOTO, VIDEO, FILE }
}

data class RecordedVoice(val path: String, val durationSec: Int)

/** What differs between Android and desktop. */
expect object Platform {
    val deviceModel: String
    val systemVersion: String
    /** Root folder for app data (TDLib databases live under it). */
    val dataDir: String
    val systemLocale: Locale
    val store: KeyValueStore

    fun openFile(path: String, mime: String?)
    fun notify(n: ChatNotification)
    fun cancelNotification(key: Int)
    /** Called when the "stay connected" setting or login state changes. */
    fun updateBackgroundMode(enabled: Boolean)
}

interface KeyValueStore {
    fun get(key: String): String?
    fun put(key: String, value: String?)
}

expect fun decodeImageFile(path: String, maxPx: Int): ImageBitmap?
expect fun decodeImageBytes(bytes: ByteArray): ImageBitmap?

/** Returns a launcher that asks the user for files and hands back what they chose. */
@Composable
expect fun rememberFilePicker(onPicked: (List<PickedFile>) -> Unit): (PickedFile.Kind) -> Unit

/** Voice recording; null where it isn't supported. */
interface VoiceRecorder {
    /** Starts recording (asking for the microphone first if needed); false if it couldn't. */
    fun start(onStarted: () -> Unit)
    fun stop(): RecordedVoice?
    fun cancel()
}

@Composable
expect fun rememberVoiceRecorder(onDenied: () -> Unit): VoiceRecorder?

/** Plays a downloaded video in-app where possible, else hands it to the system player. */
@Composable
expect fun VideoPlayer(path: String, onDismiss: () -> Unit)

/** Plays voice notes and audio. */
expect object AudioPlayer {
    /** Starts [path], stopping anything else; [onEnd] runs when it finishes or is stopped. */
    fun play(path: String, onEnd: () -> Unit)
    fun stop()
}

/** "Go back" requests from the keyboard (desktop Esc). */
object BackEvents {
    val flow = kotlinx.coroutines.flow.MutableSharedFlow<Unit>(extraBufferCapacity = 1)
}

@Composable
expect fun PlatformBackHandler(enabled: Boolean = true, onBack: () -> Unit)
