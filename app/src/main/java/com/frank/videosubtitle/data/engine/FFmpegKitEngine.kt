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
        // If the user has configured a distinct translated-line style, rewrite
        // the SRT in place with ASS inline overrides on the second line of each
        // cue (libass parses `{\...}` tags inside SRT text). The on-disk
        // subtitle.srt is left untouched — editor opens the original.
        val safeSrt = File(output.parentFile ?: srt.parentFile, "subs.srt")
        if (options.mode == BurnMode.HARD && hasTranslatedOverrides(options)) {
            writeStyledBilingualSrt(srt, safeSrt, options)
        } else if (safeSrt.absolutePath != srt.absolutePath) {
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
        // BorderStyle=1 → outline only. BorderStyle=3 → outline + opaque box
        // whose color is BackColour. Use BackColour to express the user-chosen
        // background opacity (alpha is inverted in ASS: 00=opaque, FF=clear).
        val borderStyle = if (o.background) 3 else 1
        val parts = mutableListOf(
            "Fontsize=${o.fontSize}",
            "PrimaryColour=$primary",
            "OutlineColour=$outline",
            "Outline=${o.outlineWidth}",
            "BorderStyle=$borderStyle",
            "Alignment=${o.alignment.assValue}",
            "MarginV=${o.marginV}",
            "MarginL=${maxOf(0, o.marginH)}",
            "MarginR=${maxOf(0, -o.marginH)}",
        )
        if (o.background) {
            val bgArgb = (o.backgroundAlpha.coerceIn(0, 255) shl 24) // black box
            parts += "BackColour=${argbToAssBgr(bgArgb)}"
        }
        return parts.joinToString(",")
    }

    private fun hasTranslatedOverrides(o: BurnOptions): Boolean =
        o.fontSizeTranslated != null ||
            o.fontColorTranslatedArgb != null ||
            o.outlineWidthTranslated != null

    /**
     * Read [src] (a SubRip file produced by the translation step, where each
     * cue text is "original\ntranslated") and write [dst] with ASS inline
     * override tags prepended to the translated line. libass honors `{\...}`
     * tags inside SRT cues, so this lets us style the two lines independently
     * without converting on-disk artifacts to .ass.
     */
    private fun writeStyledBilingualSrt(src: File, dst: File, o: BurnOptions) {
        val translatedTag = buildString {
            append('{')
            o.fontSizeTranslated?.let { append("\\fs").append(it) }
            o.fontColorTranslatedArgb?.let { append("\\c").append(argbToAssBgr(it)) }
            o.outlineWidthTranslated?.let { append("\\bord").append(it) }
            append('}')
        }
        val text = src.readText()
        // SRT cues are separated by blank lines. For each cue, the body is
        // everything after the timing line. Inject the override tag at the
        // start of every line after the first body line.
        val rebuilt = StringBuilder(text.length + 64)
        val cues = text.split(Regex("\\r?\\n\\r?\\n"))
        cues.forEachIndexed { idx, cue ->
            val trimmed = cue.trim('\r', '\n')
            if (trimmed.isEmpty()) return@forEachIndexed
            val lines = trimmed.split(Regex("\\r?\\n"))
            // Find the timing line ("00:00:01,000 --> 00:00:02,000"). Header
            // lines (sequence number) precede it; body lines follow.
            val timingIdx = lines.indexOfFirst { it.contains("-->") }
            if (timingIdx < 0) {
                rebuilt.append(trimmed)
            } else {
                lines.subList(0, timingIdx + 1).forEach { rebuilt.append(it).append('\n') }
                lines.subList(timingIdx + 1, lines.size).forEachIndexed { bodyIdx, line ->
                    if (bodyIdx == 0) {
                        rebuilt.append(line).append('\n')
                    } else {
                        rebuilt.append(translatedTag).append(line).append('\n')
                    }
                }
            }
            if (idx != cues.size - 1) rebuilt.append('\n')
        }
        dst.writeText(rebuilt.toString())
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
