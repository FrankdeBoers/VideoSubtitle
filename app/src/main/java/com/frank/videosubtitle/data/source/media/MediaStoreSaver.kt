package com.frank.videosubtitle.data.source.media

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import androidx.core.content.FileProvider
import com.frank.videosubtitle.BuildConfig
import java.io.File
import java.io.FileInputStream

/**
 * Persists a finished video file into the public Movies/VideoSubtitle/ folder
 * and returns a stable content:// URI suitable for ACTION_VIEW.
 */
class MediaStoreSaver(private val context: Context) {

    /**
     * Copies [source] to public storage as [displayName] (must end with the
     * correct extension). Removes [source] on success. Returns the URI.
     */
    fun saveToMovies(source: File, displayName: String, mimeType: String): Uri {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            saveViaMediaStore(source, displayName, mimeType)
        } else {
            saveToLegacyStorage(source, displayName)
        }
    }

    private fun saveViaMediaStore(source: File, displayName: String, mimeType: String): Uri {
        val resolver = context.contentResolver
        val collection = MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        val values = ContentValues().apply {
            put(MediaStore.Video.Media.DISPLAY_NAME, displayName)
            put(MediaStore.Video.Media.MIME_TYPE, mimeType)
            put(MediaStore.Video.Media.RELATIVE_PATH, "Movies/${RELATIVE_FOLDER}")
            put(MediaStore.Video.Media.IS_PENDING, 1)
        }
        val uri = resolver.insert(collection, values)
            ?: error("MediaStore insert returned null")
        try {
            resolver.openOutputStream(uri, "w")?.use { out ->
                FileInputStream(source).use { it.copyTo(out) }
            } ?: error("Could not open MediaStore output stream")
            val finalize = ContentValues().apply { put(MediaStore.Video.Media.IS_PENDING, 0) }
            resolver.update(uri, finalize, null, null)
            source.delete()
            return uri
        } catch (t: Throwable) {
            runCatching { resolver.delete(uri, null, null) }
            throw t
        }
    }

    private fun saveToLegacyStorage(source: File, displayName: String): Uri {
        @Suppress("DEPRECATION")
        val moviesDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MOVIES)
        val target = File(moviesDir, "$RELATIVE_FOLDER/$displayName")
        target.parentFile?.mkdirs()
        source.copyTo(target, overwrite = true)
        source.delete()
        return FileProvider.getUriForFile(context, "${BuildConfig.APPLICATION_ID}.fileprovider", target)
    }

    companion object {
        const val RELATIVE_FOLDER = "VideoSubtitle"
    }
}
