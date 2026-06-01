package com.frank.videosubtitle.data.repository

import android.content.Context
import com.frank.videosubtitle.domain.model.WhisperModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.isActive
import okhttp3.OkHttpClient
import okhttp3.Request
import timber.log.Timber
import java.io.File
import java.io.RandomAccessFile
import java.security.MessageDigest
import java.util.concurrent.TimeUnit

class DefaultModelRepository(
    context: Context,
    private val client: OkHttpClient = defaultClient(),
) : ModelRepository {

    private val modelsDir: File = File(context.filesDir, "models").apply { mkdirs() }

    override fun fileFor(model: WhisperModel): File = File(modelsDir, model.fileName)

    override fun isAvailable(model: WhisperModel): Boolean {
        val f = fileFor(model)
        return f.exists() && f.length() == model.sizeBytes
    }

    override fun download(model: WhisperModel): Flow<ModelDownloadEvent> = callbackFlow {
        val finalFile = fileFor(model)
        if (isAvailable(model)) {
            trySend(ModelDownloadEvent.Progress(model.sizeBytes, model.sizeBytes))
            trySend(ModelDownloadEvent.Done(finalFile))
            close(); return@callbackFlow
        }

        val partFile = File(modelsDir, "${model.fileName}.part")
        val resumeFrom = if (partFile.exists()) partFile.length() else 0L
        val req = Request.Builder()
            .url(model.downloadUrl)
            .apply { if (resumeFrom > 0) header("Range", "bytes=$resumeFrom-") }
            .build()

        Timber.i("Downloading %s from %s (resume=%d)", model.fileName, model.downloadUrl, resumeFrom)

        val call = client.newCall(req)
        val response = runCatching { call.execute() }.getOrElse { t ->
            close(t); return@callbackFlow
        }
        if (!response.isSuccessful) {
            response.close()
            close(RuntimeException("HTTP ${response.code} fetching ${model.fileName}"))
            return@callbackFlow
        }

        val body = response.body ?: run {
            response.close()
            close(RuntimeException("Empty body for ${model.fileName}"))
            return@callbackFlow
        }

        val total = if (response.code == 206) {
            // Server honored Range — Content-Length is remaining bytes.
            resumeFrom + (body.contentLength().takeIf { it > 0 } ?: (model.sizeBytes - resumeFrom))
        } else {
            // Server didn't honor Range; restart from zero.
            partFile.delete()
            body.contentLength().takeIf { it > 0 } ?: model.sizeBytes
        }

        try {
            body.byteStream().use { input ->
                RandomAccessFile(partFile, "rw").use { raf ->
                    val startOffset = if (response.code == 206) resumeFrom else 0L
                    raf.seek(startOffset)
                    val buf = ByteArray(64 * 1024)
                    var read: Int
                    var written = startOffset
                    var lastEmitted = startOffset
                    while (input.read(buf).also { read = it } != -1) {
                        if (!isActive) throw kotlinx.coroutines.CancellationException("download cancelled")
                        raf.write(buf, 0, read)
                        written += read
                        if (written - lastEmitted >= 256 * 1024) {
                            trySend(ModelDownloadEvent.Progress(written, total))
                            lastEmitted = written
                        }
                    }
                    raf.fd.sync()
                    trySend(ModelDownloadEvent.Progress(written, total))
                }
            }
        } catch (t: Throwable) {
            close(t); return@callbackFlow
        } finally {
            response.close()
        }

        if (partFile.length() != model.sizeBytes) {
            close(RuntimeException("Size mismatch: got ${partFile.length()}, expected ${model.sizeBytes}"))
            return@callbackFlow
        }

        val sha = sha256(partFile)
        if (!sha.equals(model.sha256, ignoreCase = true)) {
            partFile.delete()
            close(ModelChecksumException("SHA-256 mismatch for ${model.fileName}: got $sha, expected ${model.sha256}"))
            return@callbackFlow
        }

        if (!partFile.renameTo(finalFile)) {
            close(RuntimeException("Failed to finalize ${model.fileName}"))
            return@callbackFlow
        }
        Timber.i("Downloaded %s (%d bytes), sha256 verified", model.fileName, finalFile.length())
        trySend(ModelDownloadEvent.Done(finalFile))
        close()
        awaitClose { call.cancel() }
    }.flowOn(Dispatchers.IO)

    private fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buf = ByteArray(64 * 1024)
            var read: Int
            while (input.read(buf).also { read = it } != -1) {
                digest.update(buf, 0, read)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    companion object {
        private fun defaultClient(): OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            .callTimeout(0, TimeUnit.SECONDS)
            .build()
    }
}
