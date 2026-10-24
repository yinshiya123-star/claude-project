package com.skgram.app.ui.components

import android.graphics.BitmapFactory
import android.util.LruCache
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.skgram.app.core.Tg
import dev.g000sha256.tdl.dto.ChatPhotoInfo
import dev.g000sha256.tdl.dto.File
import dev.g000sha256.tdl.dto.Minithumbnail
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Decoded images, keyed by path + target size; sized by bytes so it can't eat the heap. */
private object BitmapCache : LruCache<String, ImageBitmap>((Runtime.getRuntime().maxMemory() / 8).toInt()) {
    override fun sizeOf(key: String, value: ImageBitmap) = value.width * value.height * 4
}

private fun decode(path: String, maxPx: Int): ImageBitmap? {
    val key = "$path@$maxPx"
    BitmapCache.get(key)?.let { return it }
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeFile(path, bounds)
    if (bounds.outWidth <= 0) return null
    var sample = 1
    while (bounds.outWidth / (sample * 2) >= maxPx && bounds.outHeight / (sample * 2) >= maxPx) sample *= 2
    val bmp = BitmapFactory.decodeFile(path, BitmapFactory.Options().apply { inSampleSize = sample }) ?: return null
    return bmp.asImageBitmap().also { BitmapCache.put(key, it) }
}

private fun decodeMini(mini: Minithumbnail): ImageBitmap? =
    BitmapFactory.decodeByteArray(mini.data, 0, mini.data.size)?.asImageBitmap()

/** Loads a TDLib file as an image, showing the inline minithumbnail instantly while the real one loads. */
@Composable
fun TdImage(
    file: File?,
    mini: Minithumbnail?,
    modifier: Modifier = Modifier,
    maxPx: Int = 720,
    contentScale: ContentScale = ContentScale.Crop,
) {
    val cached = remember(file?.id, maxPx) {
        file?.local?.takeIf { it.isDownloadingCompleted }?.path?.let { BitmapCache.get("$it@$maxPx") }
    }
    var image by remember(file?.id, maxPx) { mutableStateOf(cached) }
    val placeholder = remember(mini) { mini?.let(::decodeMini) }
    LaunchedEffect(file?.id, maxPx) {
        if (image != null || file == null) return@LaunchedEffect
        val path = Tg.download(file) ?: return@LaunchedEffect
        image = withContext(Dispatchers.IO) { decode(path, maxPx) }
    }
    val shown = image ?: placeholder
    if (shown != null) {
        Image(shown, null, modifier, contentScale = contentScale)
    } else {
        Box(modifier.background(Color(0x22128AFA)))
    }
}

private val avatarGradients = listOf(
    listOf(Color(0xFF128AFA), Color(0xFF4FD0F7)),
    listOf(Color(0xFFFF7EB6), Color(0xFFFFB37E)),
    listOf(Color(0xFF7C6CFF), Color(0xFFB59CFF)),
    listOf(Color(0xFF1EC28B), Color(0xFF7DE3C0)),
    listOf(Color(0xFFFF9F43), Color(0xFFFFD66B)),
    listOf(Color(0xFF00B4D8), Color(0xFF90E0EF)),
    listOf(Color(0xFFEF5DA8), Color(0xFF9FB8FF)),
)

fun initials(title: String): String {
    val parts = title.trim().split(' ').filter { it.isNotEmpty() }
    return when {
        parts.isEmpty() -> "?"
        parts.size == 1 -> parts[0].take(1)
        else -> parts[0].take(1) + parts[1].take(1)
    }.uppercase()
}

@Composable
fun Avatar(id: Long, title: String, photo: ChatPhotoInfo?, size: Dp = 52.dp) {
    val colors = avatarGradients[((id % avatarGradients.size + avatarGradients.size) % avatarGradients.size).toInt()]
    Box(
        Modifier.size(size).clip(CircleShape).background(Brush.linearGradient(colors)),
        contentAlignment = Alignment.Center,
    ) {
        if (photo != null) {
            TdImage(photo.small, photo.minithumbnail, Modifier.size(size), maxPx = 160)
        } else {
            Text(initials(title), color = Color.White, fontWeight = FontWeight.Bold, fontSize = (size.value * 0.36f).sp)
        }
    }
}
