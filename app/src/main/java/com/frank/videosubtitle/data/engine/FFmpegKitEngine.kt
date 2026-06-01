package com.frank.videosubtitle.data.engine

import com.arthenica.ffmpegkit.FFmpegKit
import com.arthenica.ffmpegkit.FFmpegKitConfig
import com.arthenica.ffmpegkit.Level
import com.arthenica.ffmpegkit.LogCallback
import com.arthenica.ffmpegkit.ReturnCode
import com.arthenica.ffmpegkit.Statistics
import com.arthenica.ffmpegkit.StatisticsCallback
import com.frank.videosubtitle.domain.engine.BurnMode
import com.frank.videosubtitle.domain.engine.BurnOptions
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
        runFFmpegSession(
            cmd = cmd.joinToString(" ") { quoteIfNeeded(it) },
            durationMs = durationMs,
            output = output,
            cleanupPartialOnFail = true,
        )
    }

    override fun burnSubtitles(
        video: File,
        srt: File,
        output: File,
        durationMs: Long,
        options: BurnOptions,
    ): Flow<FfmpegProgress> = callbackFlow {
        output.parentFile?.mkdirs()
        // libavfilter's `subtitles=` filter parses `:` / `,` / `\\` specially —
        // copy the SRT to an ASCII-only path under the task dir so we only
        // need the lavfi level of escaping below. The cache dir is ASCII by
        // construction (UUID-based) so this is cheap belt-and-braces.
        val safeSrt = File(output.parentFile ?: srt.parentFile, "subs.srt")
        if (safeSrt.absolutePath != srt.absolutePath) {
            srt.copyTo(safeSrt, overwrite = true)
        }

        val cmd = when (options.mode) {
            BurnMode.HARD -> hardBurnCmd(video, safeSrt, output, options)
            BurnMode.SOFT -> softMuxCmd(video, safeSrt, output)
        }
        runFFmpegSession(
            cmd = cmd,
            durationMs = durationMs,
            output = output,
            cleanupPartialOnFail = true,
        )
    }

    private fun hardBurnCmd(
        video: File,
        srt: File,
        output: File,
        options: BurnOptions,
    ): String {
        val styleArg = buildForceStyle(options)
        val escapedSrt = escapeForSubtitlesFilter(srt.absolutePath)
        // Wrap the whole filter in single quotes; lavfi sees the SRT path
        // and force_style as separate options separated by `:`.
        val filter = "subtitles=$escapedSrt:force_style='$styleArg'"
        val parts = listOf(
            "-y",
            "-i", quotePath(video.absolutePath),
            "-vf", "\"$filter\"",
            "-c:v", "libx264",
            "-preset", options.preset,
            "-crf", options.crf.toString(),
            "-c:a", "copy",
            "-movflags", "+faststart",
            quotePath(output.absolutePath),
        )
        return parts.joinToString(" ")
    }

    private fun softMuxCmd(video: File, srt: File, output: File): String {
        val parts = listOf(
            "-y",
            "-i", quotePath(video.absolutePath),
            "-i", quotePath(srt.absolutePath),
            "-map", "0:v:0",
            "-map", "0:a:0?",
            "-map", "1:0",
            "-c:v", "copy",
            "-c:a", "copy",
            "-c:s", "mov_text",
            "-metadata:s:s:0", "language=und",
            quotePath(output.absolutePath),
        )
        return parts.joinToString(" ")
    }

    private fun buildForceStyle(o: BurnOptions): String {
        // ASS uses &HAABBGGRR with alpha inverted: 00=opaque, FF=transparent.
        val primary = argbToAssBgr(o.fontColorArgb)
        val outline = argbToAssBgr(o.outlineColorArgb)
        return listOf(
            "Fontsize=${o.fontSize}",
            "PrimaryColour=$primary",
            "OutlineColour=$outline",
            "Outline=${o.outlineWidth}",
            "BorderStyle=1",
            "Alignment=${o.alignment.assValue}",
            "MarginV=24",
        ).joinToString(",")
    }

    private fun argbToAssBgr(argb: Int): String {
        val a = (argb ushr 24) and 0xFF
        val r = (argb ushr 16) and 0xFF
        val g = (argb ushr 8) and 0xFF
        val b = argb and 0xFF
        val assAlpha = 0xFF - a
        return "&H%02X%02X%02X%02X".format(assAlpha, b, g, r)
    }

    /**
     * Inside a `subtitles=...` filter argument we need to escape `\\`, `:`, `'`,
     * `,` and `[` `]` so lavfi's parser treats the path as a single string.
     * After this we wrap the result in single quotes in the filter string.
     */
    private fun escapeForSubtitlesFilter(path: String): String {
        val escaped = path
            .replace("\\", "\\\\")
            .replace(":", "\\:")
            .replace("'", "\\'")
            .replace(",", "\\,")
            .replace("[", "\\[")
            .replace("]", "\\]")
        return "'$escaped'"
    }

    private fun quotePath(p: String): String =
        if (p.any { it.isWhitespace() || it == '\'' }) "'${p.replace("'", "'\\''")}'" else p

    private suspend fun kotlinx.coroutines.channels.ProducerScope<FfmpegProgress>.runFFmpegSession(
        cmd: String,
        durationMs: Long,
        output: File,
        cleanupPartialOnFail: Boolean,
    ) {
        val logTail = ConcurrentLinkedDeque<String>()
        val maxLogLines = 60

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
            cmd,
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
        Timber.d("FFmpegKit session %d started: %s", session.sessionId, cmd)

        awaitClose {
            FFmpegKit.cancel(session.sessionId)
            if (cleanupPartialOnFail &&
                !ReturnCode.isSuccess(session.returnCode) &&
                output.exists()
            ) {
                runCatching { output.delete() }
            }
        }
    }

    private fun quoteIfNeeded(arg: String): String =
        if (arg.any { it.isWhitespace() || it == '\'' }) {
            "'" + arg.replace("'", "'\\''") + "'"
        } else arg
}
