package com.puppetlight.oneclick.data

import android.content.ContentValues
import android.content.Context
import android.media.MediaScannerConnection
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.webkit.MimeTypeMap
import kotlinx.coroutines.delay
import java.io.File
import java.io.FileOutputStream

/**
 * 相册读写：
 * - 把用户选择的素材复制到相册（让它们成为相册里最新的两项，方便在 NV MAX 选择器里直接点第一/第二个）
 * - 查找某个时间点之后新导出的视频（相册 + 下载目录）
 */
object MediaHelper {

    const val ALBUM = "一键木偶打光"
    const val BG_PREFIX = "木偶背景_"
    const val GREEN_PREFIX = "木偶角色_"
    private val VIDEO_EXT = setOf("mp4", "mov", "m4v", "3gp", "webm", "mkv")

    data class MediaItem(val uri: Uri, val name: String, val size: Long, val modifiedMs: Long, val path: String?)

    fun isOwnSource(name: String) = name.startsWith(BG_PREFIX) || name.startsWith(GREEN_PREFIX)

    fun isVideo(context: Context, uri: Uri): Boolean =
        context.contentResolver.getType(uri)?.startsWith("video/") == true

    /** 把 [src] 复制进相册的「一键木偶打光」相册，返回新的 Uri。 */
    fun copyToGallery(context: Context, src: Uri, baseName: String): Uri {
        val resolver = context.contentResolver
        val mime = resolver.getType(src) ?: "image/jpeg"
        val video = mime.startsWith("video/")
        val ext = MimeTypeMap.getSingleton().getExtensionFromMimeType(mime) ?: if (video) "mp4" else "jpg"
        val name = "$baseName${System.currentTimeMillis()}.$ext"
        val now = System.currentTimeMillis()

        val collection = if (video) MediaStore.Video.Media.EXTERNAL_CONTENT_URI else MediaStore.Images.Media.EXTERNAL_CONTENT_URI
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, name)
            put(MediaStore.MediaColumns.MIME_TYPE, mime)
            put(MediaStore.MediaColumns.DATE_ADDED, now / 1000)
            put(MediaStore.MediaColumns.DATE_MODIFIED, now / 1000)
            put(if (video) MediaStore.Video.Media.DATE_TAKEN else MediaStore.Images.Media.DATE_TAKEN, now)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val dir = if (video) Environment.DIRECTORY_MOVIES else Environment.DIRECTORY_PICTURES
            values.put(MediaStore.MediaColumns.RELATIVE_PATH, "$dir/$ALBUM")
            values.put(MediaStore.MediaColumns.IS_PENDING, 1)
            val uri = resolver.insert(collection, values) ?: throw IllegalStateException("无法写入相册")
            resolver.openOutputStream(uri)!!.use { out ->
                resolver.openInputStream(src)!!.use { it.copyTo(out) }
            }
            resolver.update(uri, ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }, null, null)
            return uri
        } else {
            val dir = File(
                Environment.getExternalStoragePublicDirectory(
                    if (video) Environment.DIRECTORY_MOVIES else Environment.DIRECTORY_PICTURES
                ), ALBUM
            ).apply { mkdirs() }
            val file = File(dir, name)
            FileOutputStream(file).use { out -> resolver.openInputStream(src)!!.use { it.copyTo(out) } }
            @Suppress("DEPRECATION")
            values.put(MediaStore.MediaColumns.DATA, file.absolutePath)
            return resolver.insert(collection, values) ?: Uri.fromFile(file)
        }
    }

    /** 查找 [sinceMs] 之后出现的最新视频（排除本 App 复制的素材）。 */
    fun findLatestVideo(context: Context, sinceMs: Long, exclude: Set<Uri> = emptySet()): MediaItem? {
        val fromStore = queryStore(context, sinceMs, exclude)
        val fromFiles = scanFolders(sinceMs, exclude)
        return listOfNotNull(fromStore, fromFiles).maxByOrNull { it.modifiedMs }
    }

    private fun queryStore(context: Context, sinceMs: Long, exclude: Set<Uri>): MediaItem? {
        val projection = mutableListOf(
            MediaStore.MediaColumns._ID,
            MediaStore.MediaColumns.DISPLAY_NAME,
            MediaStore.MediaColumns.SIZE,
            MediaStore.MediaColumns.DATE_ADDED,
            MediaStore.MediaColumns.DATE_MODIFIED,
        )
        @Suppress("DEPRECATION")
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) projection += MediaStore.MediaColumns.DATA
        val sinceSec = sinceMs / 1000
        val sel = "(${MediaStore.MediaColumns.DATE_ADDED} >= ? OR ${MediaStore.MediaColumns.DATE_MODIFIED} >= ?)"
        return try {
            context.contentResolver.query(
                MediaStore.Video.Media.EXTERNAL_CONTENT_URI,
                projection.toTypedArray(),
                sel,
                arrayOf(sinceSec.toString(), sinceSec.toString()),
                "${MediaStore.MediaColumns.DATE_MODIFIED} DESC",
            )?.use { c ->
                while (c.moveToNext()) {
                    val name = c.getString(1) ?: ""
                    if (isOwnSource(name)) continue
                    val id = c.getLong(0)
                    val uri = Uri.withAppendedPath(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, id.toString())
                    if (uri in exclude) continue
                    val modified = maxOf(c.getLong(3), c.getLong(4)) * 1000
                    val path = if (projection.size > 5) c.getString(5) else null
                    return MediaItem(uri, name, c.getLong(2), modified, path)
                }
                null
            }
        } catch (e: SecurityException) {
            null
        }
    }

    /** 直接扫描下载/影片/DCIM 目录，覆盖尚未被媒体库收录的文件（主要用于 Android 9 及以下）。 */
    private fun scanFolders(sinceMs: Long, exclude: Set<Uri>): MediaItem? {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) return null
        @Suppress("DEPRECATION")
        val roots = listOf(
            Environment.DIRECTORY_DOWNLOADS, Environment.DIRECTORY_MOVIES, Environment.DIRECTORY_DCIM,
        ).map { Environment.getExternalStoragePublicDirectory(it) }
        var best: File? = null
        roots.forEach { root ->
            root.walkTopDown().maxDepth(3).forEach { f ->
                if (f.isFile && f.extension.lowercase() in VIDEO_EXT && f.lastModified() >= sinceMs &&
                    !isOwnSource(f.name) && Uri.fromFile(f) !in exclude && (best == null || f.lastModified() > best!!.lastModified())
                ) best = f
            }
        }
        return best?.let { MediaItem(Uri.fromFile(it), it.name, it.length(), it.lastModified(), it.absolutePath) }
    }

    /**
     * 等待新视频出现并写入完成（文件大小在 [stableMs] 内不再变化）。
     * 超时返回 null。
     */
    suspend fun waitForNewVideo(
        context: Context,
        sinceMs: Long,
        timeoutMs: Long,
        stableMs: Long = 4000,
        exclude: Set<Uri> = emptySet(),
        onStatus: (String) -> Unit = {},
    ): MediaItem? {
        val deadline = System.currentTimeMillis() + timeoutMs
        var candidate: MediaItem? = null
        var stableSince = 0L
        while (System.currentTimeMillis() < deadline) {
            val found = findLatestVideo(context, sinceMs, exclude)
            if (found != null) {
                if (candidate != null && candidate.uri == found.uri && candidate.size == found.size && found.size > 0) {
                    if (System.currentTimeMillis() - stableSince >= stableMs) return found
                } else {
                    stableSince = System.currentTimeMillis()
                }
                candidate = found
                onStatus("发现导出文件 ${found.name}（${found.size / 1024} KB），等待写入完成…")
            } else {
                val left = (deadline - System.currentTimeMillis()) / 1000
                onStatus("等待导出完成…（剩余 ${left} 秒）")
            }
            delay(2000)
        }
        return null
    }

    /** 确保文件出现在相册中（Android 9 及以下下载目录里的文件需要扫描）。 */
    fun ensureInGallery(context: Context, item: MediaItem) {
        val path = item.path ?: return
        MediaScannerConnection.scanFile(context, arrayOf(path), null, null)
    }
}
