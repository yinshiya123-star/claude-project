package com.skgram.core

import android.Manifest
import android.annotation.SuppressLint
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.content.res.Resources
import android.graphics.BitmapFactory
import android.media.MediaMetadataRetriever
import android.media.MediaRecorder
import android.net.Uri
import android.os.Build
import android.provider.OpenableColumns
import android.webkit.MimeTypeMap
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import com.skgram.i18n.I18n
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.Locale

const val EXTRA_ACCOUNT = "skgram.account"
const val EXTRA_CHAT = "skgram.chat"
internal const val CHANNEL_MESSAGES = "messages"
internal const val CHANNEL_BACKGROUND = "background"

actual object Platform {
    lateinit var context: Context
        private set

    fun init(ctx: Context) {
        context = ctx.applicationContext
        val nm = context.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CHANNEL_MESSAGES, "Messages", NotificationManager.IMPORTANCE_HIGH))
        nm.createNotificationChannel(NotificationChannel(CHANNEL_BACKGROUND, "Background connection", NotificationManager.IMPORTANCE_MIN))
    }

    actual val deviceModel: String get() = Build.MODEL ?: "Android"
    actual val systemVersion: String get() = "Android ${Build.VERSION.RELEASE}"
    actual val dataDir: String get() = context.filesDir.absolutePath
    actual val systemLocale: Locale get() = Resources.getSystem().configuration.locales[0]

    actual val store: KeyValueStore by lazy {
        val sp: SharedPreferences = context.getSharedPreferences("skgram", Context.MODE_PRIVATE)
        object : KeyValueStore {
            override fun get(key: String): String? = sp.all[key]?.toString()
            override fun put(key: String, value: String?) {
                sp.edit().apply { if (value == null) remove(key) else putString(key, value) }.apply()
            }
        }
    }

    actual fun openFile(path: String, mime: String?) {
        val file = File(path)
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.files", file)
        val type = mime?.takeIf { it.isNotEmpty() }
            ?: MimeTypeMap.getSingleton().getMimeTypeFromExtension(file.extension.lowercase()) ?: "*/*"
        val view = Intent(Intent.ACTION_VIEW).setDataAndType(uri, type).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        runCatching {
            context.startActivity(Intent.createChooser(view, file.name).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }
    }

    @SuppressLint("MissingPermission")
    actual fun notify(n: ChatNotification) {
        val nm = NotificationManagerCompat.from(context)
        if (!nm.areNotificationsEnabled()) return
        val open = context.packageManager.getLaunchIntentForPackage(context.packageName)!!
            .putExtra(EXTRA_ACCOUNT, n.accountId).putExtra(EXTRA_CHAT, n.chatId)
            .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        val pi = PendingIntent.getActivity(context, n.key, open, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val style = NotificationCompat.InboxStyle().also { st -> n.lines.forEach { st.addLine(it) } }
        val notification = NotificationCompat.Builder(context, CHANNEL_MESSAGES)
            .setSmallIcon(com.skgram.shared.R.drawable.ic_stat_skgram)
            .setContentTitle(n.title)
            .setContentText(n.lines.last())
            .setStyle(if (n.lines.size > 1) style else NotificationCompat.BigTextStyle().bigText(n.lines.last()))
            .setNumber(n.lines.size)
            .setColor(0xFF128AFA.toInt())
            .setCategory(NotificationCompat.CATEGORY_MESSAGE)
            .setAutoCancel(true)
            .setContentIntent(pi)
            .build()
        nm.notify(n.key, notification)
    }

    actual fun cancelNotification(key: Int) {
        NotificationManagerCompat.from(context).cancel(key)
    }

    actual fun updateBackgroundMode(enabled: Boolean) {
        val intent = Intent(context, SyncService::class.java)
        // Starting a foreground service is only allowed while the app is visible; it then keeps itself alive.
        if (enabled) runCatching { if (AppState.foreground) ContextCompat.startForegroundService(context, intent) }
        else context.stopService(intent)
    }
}

actual fun decodeImageFile(path: String, maxPx: Int): ImageBitmap? {
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeFile(path, bounds)
    if (bounds.outWidth <= 0) return null
    var sample = 1
    while (bounds.outWidth / (sample * 2) >= maxPx && bounds.outHeight / (sample * 2) >= maxPx) sample *= 2
    return BitmapFactory.decodeFile(path, BitmapFactory.Options().apply { inSampleSize = sample })?.asImageBitmap()
}

actual fun decodeImageBytes(bytes: ByteArray): ImageBitmap? =
    BitmapFactory.decodeByteArray(bytes, 0, bytes.size)?.asImageBitmap()

/** Copies a picked document into the cache (TDLib needs a real path) and reads its media size. */
private fun importUri(ctx: Context, uri: Uri, forceFile: Boolean): PickedFile? {
    val cr = ctx.contentResolver
    val name = cr.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
        if (c.moveToFirst()) c.getString(0) else null
    } ?: "file"
    val dir = File(ctx.cacheDir, "upload/${System.nanoTime()}").apply { mkdirs() }
    val out = File(dir, name.replace('/', '_'))
    cr.openInputStream(uri)?.use { input -> out.outputStream().use { input.copyTo(it) } } ?: return null
    val type = cr.getType(uri) ?: ""
    return when {
        !forceFile && type.startsWith("image/") && type != "image/gif" -> {
            val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(out.path, o)
            PickedFile(out.path, name, PickedFile.Kind.PHOTO, o.outWidth, o.outHeight)
        }
        !forceFile && type.startsWith("video/") -> {
            val r = MediaMetadataRetriever()
            try {
                r.setDataSource(out.path)
                val w = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)?.toIntOrNull() ?: 0
                val h = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)?.toIntOrNull() ?: 0
                val rot = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION)?.toIntOrNull() ?: 0
                val ms = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0
                val (vw, vh) = if (rot == 90 || rot == 270) h to w else w to h
                PickedFile(out.path, name, PickedFile.Kind.VIDEO, vw, vh, (ms / 1000).toInt())
            } catch (e: Exception) {
                PickedFile(out.path, name, PickedFile.Kind.VIDEO)
            } finally {
                r.release()
            }
        }
        else -> PickedFile(out.path, name, PickedFile.Kind.FILE)
    }
}

@Composable
actual fun rememberFilePicker(onPicked: (List<PickedFile>) -> Unit): (PickedFile.Kind) -> Unit {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    fun handle(uris: List<Uri>, forceFile: Boolean) {
        if (uris.isEmpty()) return
        scope.launch {
            val files = withContext(Dispatchers.IO) { uris.mapNotNull { runCatching { importUri(ctx, it, forceFile) }.getOrNull() } }
            onPicked(files)
        }
    }
    val media = rememberLauncherForActivityResult(ActivityResultContracts.PickMultipleVisualMedia(10)) { handle(it, false) }
    val docs = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { handle(it, true) }
    return remember(media, docs) {
        { kind ->
            if (kind == PickedFile.Kind.FILE) docs.launch(arrayOf("*/*"))
            else media.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageAndVideo))
        }
    }
}

/** Records Ogg/Opus, the format Telegram uses for voice messages (Android 10+). */
private class AndroidVoiceRecorder(
    private val ctx: Context,
    private val askPermission: (onResult: (Boolean) -> Unit) -> Unit,
    private val onDenied: () -> Unit,
) : VoiceRecorder {
    private var recorder: MediaRecorder? = null
    private var file: File? = null
    private var startedAt = 0L

    override fun start(onStarted: () -> Unit) {
        if (ContextCompat.checkSelfPermission(ctx, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) begin(onStarted)
        else askPermission { granted -> if (granted) begin(onStarted) else onDenied() }
    }

    private fun begin(onStarted: () -> Unit) {
        val out = File(ctx.cacheDir, "voice/${System.currentTimeMillis()}.ogg").apply { parentFile?.mkdirs() }
        val r = if (Build.VERSION.SDK_INT >= 31) MediaRecorder(ctx) else @Suppress("DEPRECATION") MediaRecorder()
        try {
            r.setAudioSource(MediaRecorder.AudioSource.MIC)
            r.setOutputFormat(MediaRecorder.OutputFormat.OGG)
            r.setAudioEncoder(MediaRecorder.AudioEncoder.OPUS)
            r.setAudioSamplingRate(48000)
            r.setAudioEncodingBitRate(32000)
            r.setOutputFile(out.path)
            r.prepare()
            r.start()
        } catch (e: Exception) {
            r.release()
            onDenied()
            return
        }
        recorder = r; file = out; startedAt = System.currentTimeMillis()
        onStarted()
    }

    override fun stop(): RecordedVoice? {
        val r = recorder ?: return null
        recorder = null
        val ok = runCatching { r.stop() }.isSuccess
        r.release()
        val f = file ?: return null
        val sec = ((System.currentTimeMillis() - startedAt) / 1000).toInt()
        return if (ok && sec >= 1) RecordedVoice(f.path, sec) else null
    }

    override fun cancel() {
        recorder?.let { r -> runCatching { r.stop() }; r.release() }
        recorder = null
        file?.delete()
    }
}

@Composable
actual fun rememberVoiceRecorder(onDenied: () -> Unit): VoiceRecorder? {
    if (Build.VERSION.SDK_INT < 29) return null
    val ctx = LocalContext.current
    val pending = remember { arrayOfNulls<(Boolean) -> Unit>(1) }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        pending[0]?.invoke(granted)
        pending[0] = null
    }
    val recorder = remember { AndroidVoiceRecorder(ctx, { cb -> pending[0] = cb; launcher.launch(Manifest.permission.RECORD_AUDIO) }, onDenied) }
    DisposableEffect(recorder) { onDispose { recorder.cancel() } }
    return recorder
}

@Composable
actual fun VideoPlayer(path: String, onDismiss: () -> Unit) {
    val ctx = LocalContext.current
    val player = remember(path) {
        AudioPlayer.stop()
        ExoPlayer.Builder(ctx).build().apply {
            setMediaItem(MediaItem.fromUri(Uri.fromFile(File(path))))
            repeatMode = Player.REPEAT_MODE_ONE
            prepare()
            play()
        }
    }
    DisposableEffect(player) { onDispose { player.release() } }
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Box(Modifier.fillMaxSize().background(Color.Black)) {
            AndroidView(factory = { PlayerView(it).apply { this.player = player } }, modifier = Modifier.fillMaxSize())
        }
    }
}

actual object AudioPlayer {
    private var player: ExoPlayer? = null
    private var onEnd: (() -> Unit)? = null

    actual fun play(path: String, onEnd: () -> Unit) {
        stop()
        val p = player ?: ExoPlayer.Builder(Platform.context).build().also { exo ->
            exo.addListener(object : Player.Listener {
                override fun onPlaybackStateChanged(state: Int) {
                    if (state == Player.STATE_ENDED) finish()
                }
            })
            player = exo
        }
        this.onEnd = onEnd
        p.setMediaItem(MediaItem.fromUri(Uri.fromFile(File(path))))
        p.prepare()
        p.play()
    }

    private fun finish() {
        val cb = onEnd
        onEnd = null
        cb?.invoke()
    }

    actual fun stop() {
        player?.stop()
        finish()
    }
}

@Composable
actual fun PlatformBackHandler(enabled: Boolean, onBack: () -> Unit) = BackHandler(enabled, onBack)

/** Keeps the process (and so every account's TDLib connection) alive in the background. */
class SyncService : android.app.Service() {
    override fun onBind(intent: Intent?) = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val open = packageManager.getLaunchIntentForPackage(packageName)
        val pi = PendingIntent.getActivity(this, 0, open, PendingIntent.FLAG_IMMUTABLE)
        val n = NotificationCompat.Builder(this, CHANNEL_BACKGROUND)
            .setSmallIcon(com.skgram.shared.R.drawable.ic_stat_skgram)
            .setContentTitle("SKGram")
            .setContentText(I18n.strings.connectedBg)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_MIN)
            .setContentIntent(pi)
            .build()
        val type = if (Build.VERSION.SDK_INT >= 34) android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_REMOTE_MESSAGING else 0
        runCatching { androidx.core.app.ServiceCompat.startForeground(this, 1, n, type) }
        return START_STICKY
    }
}
