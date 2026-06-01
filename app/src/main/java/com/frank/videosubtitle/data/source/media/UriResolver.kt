package com.frank.videosubtitle.data.source.media

import android.content.ContentResolver
import android.content.Context
import android.graphics.Bitmap
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.provider.OpenableColumns
import com.frank.videosubtitle.domain.model.VideoMeta
import com.frank.videosubtitle.util.DispatcherProvider
import kotlinx.coroutines.withContext
import timber.log.Timber
import java.io.File
import java.io.FileOutputStream

class UriResolver(
    private val context: Context,
    private val dispatchers: DispatcherProvider,
) {

    suspend fun importToCache(uri: Uri, taskId: String): VideoMeta = withContext(dispatchers.io) {
        val resolver = context.contentResolver
        val (displayName, sizeFromQuery) = queryNameAndSize(resolver, uri)
        val taskDir = File(context.cacheDir, "tasks/$taskId").apply { mkdirs() }
        val ext = displayName.substringAfterLast('.', missingDelimiterValue = "mp4").take(8)
        val sourceFile = File(taskDir, "source.$ext")

        resolver.openInputStream(uri)?.use { input ->
            FileOutputStream(sourceFile).use { output -> input.copyTo(output, bufferSize = 256 * 1024) }
        } ?: error("Cannot open input stream for uri=$uri")

        val sizeBytes = if (sizeFromQuery > 0) sizeFromQuery else sourceFile.length()
        probe(
            file = sourceFile,
            taskDir = taskDir,
            displayName = displayName,
            sourceUri = uri.toString(),
            sizeBytes = sizeBytes,
        )
    }

    private fun probe(
        file: File,
        taskDir: File,
        displayName: String,
        sourceUri: String,
        sizeBytes: Long,
    ): VideoMeta {
        val retriever = MediaMetadataRetriever()
        return try {
            retriever.setDataSource(file.absolutePath)
            val durationMs = retriever.metaInt(MediaMetadataRetriever.METADATA_KEY_DURATION).toLong()
            val width = retriever.metaInt(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)
            val height = retriever.metaInt(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)
            val bitrate = retriever.metaInt(MediaMetadataRetriever.METADATA_KEY_BITRATE).toLong()
            val sampleRate = runCatching {
                retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_SAMPLERATE)?.toInt() ?: 0
            }.getOrDefault(0)

            val thumbnailPath = extractThumbnail(retriever, taskDir)

            VideoMeta(
                displayName = displayName,
                sourceUri = sourceUri,
                cachedPath = file.absolutePath,
                thumbnailPath = thumbnailPath,
                durationMs = durationMs,
                width = width,
                height = height,
                bitrate = bitrate,
                audioSampleRate = sampleRate,
                videoCodec = null,
                audioCodec = null,
                sizeBytes = sizeBytes,
            )
        } catch (t: Throwable) {
            Timber.w(t, "MediaMetadataRetriever failed for ${file.absolutePath}; returning partial meta")
            VideoMeta(
                displayName = displayName,
                sourceUri = sourceUri,
                cachedPath = file.absolutePath,
                thumbnailPath = null,
                durationMs = 0,
                width = 0,
                height = 0,
                bitrate = 0,
                audioSampleRate = 0,
                videoCodec = null,
                audioCodec = null,
                sizeBytes = sizeBytes,
            )
        } finally {
            runCatching { retriever.release() }
        }
    }

    private fun extractThumbnail(retriever: MediaMetadataRetriever, taskDir: File): String? {
        val frame: Bitmap = retriever.frameAtTime ?: return null
        val out = File(taskDir, "thumb.jpg")
        return try {
            FileOutputStream(out).use { os ->
                frame.compress(Bitmap.CompressFormat.JPEG, 80, os)
            }
            out.absolutePath
        } catch (t: Throwable) {
            Timber.w(t, "thumbnail write failed")
            null
        } finally {
            frame.recycle()
        }
    }

    private fun queryNameAndSize(resolver: ContentResolver, uri: Uri): Pair<String, Long> {
        val projection = arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE)
        resolver.query(uri, projection, null, null, null)?.use { c ->
            if (c.moveToFirst()) {
                val nameIdx = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                val sizeIdx = c.getColumnIndex(OpenableColumns.SIZE)
                val name = if (nameIdx >= 0) c.getString(nameIdx) else null
                val size = if (sizeIdx >= 0) c.getLong(sizeIdx) else 0L
                return (name ?: uri.lastPathSegment ?: "video.mp4") to size
            }
        }
        return (uri.lastPathSegment ?: "video.mp4") to 0L
    }

    private fun MediaMetadataRetriever.metaInt(key: Int): Int =
        runCatching { extractMetadata(key)?.toInt() ?: 0 }.getOrDefault(0)
}
