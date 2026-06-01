package com.frank.videosubtitle.domain.engine

import kotlinx.coroutines.flow.Flow
import java.io.File

data class FfmpegProgress(
    val percent: Int,
    val timeMs: Long,
    val sizeBytes: Long,
    val speedMultiplier: Double,
)

enum class BurnMode { HARD, SOFT }

enum class SubtitleAlignment(val assValue: Int) {
    BottomCenter(2),
    TopCenter(8),
    MiddleCenter(5),
}

data class BurnOptions(
    val mode: BurnMode = BurnMode.HARD,
    val crf: Int = 23,
    val preset: String = "medium",
    val fontSize: Int = 24,
    /** AABBGGRR 32-bit ARGB color; alpha is the high byte. */
    val fontColorArgb: Int = 0xFFFFFFFF.toInt(),
    val outlineColorArgb: Int = 0xFF000000.toInt(),
    val outlineWidth: Int = 2,
    val alignment: SubtitleAlignment = SubtitleAlignment.BottomCenter,
)

interface FFmpegEngine {
    /**
     * Extract a 16 kHz mono PCM s16le WAV from [input] into [output]. Emits progress
     * percentages computed against [durationMs]. Flow completes on success and
     * fails with [FfmpegException] on non-zero return code or cancellation.
     */
    fun extractAudio(input: File, output: File, durationMs: Long): Flow<FfmpegProgress>

    /**
     * Burn [srt] into [video] writing to [output]. HARD = re-encode video with
     * subtitles=... filter; SOFT = mov_text muxed (only mp4/m4v/mov containers).
     * Caller is responsible for choosing an output suffix compatible with [options.mode].
     */
    fun burnSubtitles(
        video: File,
        srt: File,
        output: File,
        durationMs: Long,
        options: BurnOptions,
    ): Flow<FfmpegProgress>
}

class FfmpegException(
    val returnCodeValue: Int,
    val logsTail: String,
    message: String = "FFmpeg failed (rc=$returnCodeValue): $logsTail",
) : RuntimeException(message)
