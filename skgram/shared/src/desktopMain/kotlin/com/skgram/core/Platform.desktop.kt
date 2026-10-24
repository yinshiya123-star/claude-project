package com.skgram.core

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toComposeImageBitmap
import org.jetbrains.skia.Image
import org.jetbrains.skia.Rect
import org.jetbrains.skia.Surface
import java.awt.Desktop
import java.awt.FileDialog
import java.awt.Frame
import java.io.File
import java.util.Locale
import java.util.prefs.Preferences
import kotlin.concurrent.thread

/** The desktop app installs its tray notifier here. */
object DesktopNotifier {
    @Volatile var show: ((ChatNotification) -> Unit)? = null
}

private val os = System.getProperty("os.name").lowercase()

actual object Platform {
    actual val deviceModel: String = "Desktop"
    actual val systemVersion: String = "${System.getProperty("os.name")} ${System.getProperty("os.version")}"

    actual val dataDir: String = run {
        val home = System.getProperty("user.home")
        val dir = when {
            os.contains("win") -> File(System.getenv("APPDATA") ?: "$home/AppData/Roaming", "SKGram")
            os.contains("mac") -> File(home, "Library/Application Support/SKGram")
            else -> File(System.getenv("XDG_DATA_HOME") ?: "$home/.local/share", "SKGram")
        }
        dir.mkdirs()
        dir.absolutePath
    }

    actual val systemLocale: Locale get() = Locale.getDefault()

    actual val store: KeyValueStore = object : KeyValueStore {
        private val prefs = Preferences.userRoot().node("skgram")
        override fun get(key: String): String? = prefs.get(key, null)
        override fun put(key: String, value: String?) {
            if (value == null) prefs.remove(key) else prefs.put(key, value)
            runCatching { prefs.flush() }
        }
    }

    actual fun openFile(path: String, mime: String?) {
        thread(isDaemon = true) {
            val f = File(path)
            val ok = runCatching { Desktop.getDesktop().open(f) }.isSuccess
            if (!ok) runCatching {
                val cmd = when {
                    os.contains("mac") -> listOf("open", path)
                    os.contains("win") -> listOf("cmd", "/c", "start", "", path)
                    else -> listOf("xdg-open", path)
                }
                ProcessBuilder(cmd).start()
            }
        }
    }

    actual fun notify(n: ChatNotification) {
        DesktopNotifier.show?.invoke(n)
    }

    actual fun cancelNotification(key: Int) = Unit

    actual fun updateBackgroundMode(enabled: Boolean) = Unit
}

actual fun decodeImageFile(path: String, maxPx: Int): ImageBitmap? = runCatching {
    val image = Image.makeFromEncoded(File(path).readBytes())
    val scale = minOf(1f, maxPx.toFloat() / maxOf(image.width, image.height))
    if (scale >= 1f) return@runCatching image.toComposeImageBitmap()
    val w = (image.width * scale).toInt().coerceAtLeast(1)
    val h = (image.height * scale).toInt().coerceAtLeast(1)
    val surface = Surface.makeRasterN32Premul(w, h)
    surface.canvas.drawImageRect(image, Rect.makeWH(w.toFloat(), h.toFloat()))
    surface.makeImageSnapshot().toComposeImageBitmap()
}.getOrNull()

actual fun decodeImageBytes(bytes: ByteArray): ImageBitmap? =
    runCatching { Image.makeFromEncoded(bytes).toComposeImageBitmap() }.getOrNull()

private val imageExt = setOf("jpg", "jpeg", "png", "webp", "bmp")
private val videoExt = setOf("mp4", "mov", "m4v", "mkv", "webm", "avi")

@Composable
actual fun rememberFilePicker(onPicked: (List<PickedFile>) -> Unit): (PickedFile.Kind) -> Unit = remember(onPicked) {
    { kind ->
        val dialog = FileDialog(null as Frame?, "SKGram", FileDialog.LOAD).apply {
            isMultipleMode = true
            if (kind == PickedFile.Kind.PHOTO) setFilenameFilter { _, name -> name.substringAfterLast('.').lowercase() in imageExt + videoExt }
            isVisible = true
        }
        onPicked(dialog.files.map { f ->
            val ext = f.extension.lowercase()
            when {
                kind == PickedFile.Kind.PHOTO && ext in imageExt -> {
                    val img = runCatching { Image.makeFromEncoded(f.readBytes()) }.getOrNull()
                    PickedFile(f.absolutePath, f.name, PickedFile.Kind.PHOTO, img?.width ?: 0, img?.height ?: 0)
                }
                kind == PickedFile.Kind.PHOTO && ext in videoExt -> PickedFile(f.absolutePath, f.name, PickedFile.Kind.VIDEO)
                else -> PickedFile(f.absolutePath, f.name, PickedFile.Kind.FILE)
            }
        })
    }
}

@Composable
actual fun rememberVoiceRecorder(onDenied: () -> Unit): VoiceRecorder? = null

/** Desktop hands videos to the system's player. */
@Composable
actual fun VideoPlayer(path: String, onDismiss: () -> Unit) {
    LaunchedEffect(path) {
        Platform.openFile(path, null)
        onDismiss()
    }
}

actual object AudioPlayer {
    actual fun play(path: String, onEnd: () -> Unit) {
        Platform.openFile(path, null)
        onEnd()
    }

    actual fun stop() = Unit
}

@Composable
actual fun PlatformBackHandler(enabled: Boolean, onBack: () -> Unit) {
    val current by androidx.compose.runtime.rememberUpdatedState(onBack)
    LaunchedEffect(enabled) {
        if (enabled) BackEvents.flow.collect { current() }
    }
}
