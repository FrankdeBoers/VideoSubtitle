package com.frank.videosubtitle.data.engine

import com.arthenica.ffmpegkit.FFmpegKit
import com.arthenica.ffmpegkit.FFmpegKitConfig
import com.arthenica.ffmpegkit.Level
import com.arthenica.ffmpegkit.LogCallback
import com.arthenica.ffmpegkit.ReturnCode
import com.arthenica.ffmpegkit.Statistics
import com.arthenica.ffmpegkit.StatisticsCallback
import com.frank.videosubtitle.domain.engine.FFmpegEngine
import com.frank.videosubtitle.domain.engine.FfmpegException
import com.frank.videosubtitle.domain.engine.FfmpegProgress
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import timber.log.Timber
import java.io.File
import java.util.concurrent.ConcurrentLinkedDeque

class FFmpegKitEngine : FFmpegEngine {

    init {
        // Quiet FFmpeg's own log spam; we forward what we need via LogCallback.
        FFmpegKitConfig.setLogLevel(Level.AV_LOG_WARNING)
    }

    override fun extractAudio(
        input: File,
        output: File,
        durationMs: Long,
    ): Flow<FfmpegProgress> = callbackFlow {
        output.parentFile?.mkdirs()
        // Mirrors VideoCaptioner's whisper preprocessing: mono 16kHz s16le WAV.
        val cmd = arrayOf(
            "-y",
            "-i", input.absolutePath,
            "-map", "0:a:0",
            "-vn",
            "-ac", "1",
            "-ar", "16000",
            "-f", "wav",
            output.absolutePath,
        )

        val logTail = ConcurrentLinkedDeque<String>()
        val maxLogLines = 40

        val logCallback = LogCallback { msg ->
            if (msg.level.value <= Level.AV_LOG_WARNING.value) {
                logTail.addLast(msg.message)
                while (logTail.size > maxLogLines) logTail.pollFirst()
            }
        }

        val statsCallback = StatisticsCallback { s: Statistics ->
            val percent = if (durationMs > 0) {
                ((s.time.toLong().coerceAtLeast(0) * 100) / durationMs)
                    .toInt()
                    .coerceIn(0, 100)
            } else 0
            trySend(
                FfmpegProgress(
                    percent = percent,
                    timeMs = s.time.toLong(),
                    sizeBytes = s.size,
                    speedMultiplier = s.speed,
                ),
            )
        }

        val session = FFmpegKit.executeAsync(
            cmd.joinToString(" ") { quoteIfNeeded(it) },
            { completed ->
                val rc = completed.returnCode
                when {
                    ReturnCode.isSuccess(rc) -> {
                        trySend(FfmpegProgress(100, durationMs, output.length(), 1.0))
                        close()
                    }
                    ReturnCode.isCancel(rc) -> close(kotlinx.coroutines.CancellationException("ffmpeg cancelled"))
                    else -> close(FfmpegException(rc.value, logTail.joinToString("\n")))
                }
            },
            logCallback,
            statsCallback,
        )
        Timber.d("FFmpegKit session %d started: %s", session.sessionId, cmd.joinToString(" "))

        awaitClose {
            FFmpegKit.cancel(session.sessionId)
            // Best-effort cleanup of partial output if cancelled before success.
            if (!ReturnCode.isSuccess(session.returnCode) && output.exists()) {
                runCatching { output.delete() }
            }
        }
    }

    private fun quoteIfNeeded(arg: String): String =
        if (arg.any { it.isWhitespace() || it == '\'' }) {
            "'" + arg.replace("'", "'\\''") + "'"
        } else arg
}
