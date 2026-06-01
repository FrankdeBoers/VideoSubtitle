package com.frank.videosubtitle.domain.engine

import kotlinx.coroutines.flow.Flow
import java.io.File

data class FfmpegProgress(
    val percent: Int,
    val timeMs: Long,
    val sizeBytes: Long,
    val speedMultiplier: Double,
)

interface FFmpegEngine {
    /**
     * Extract a 16 kHz mono PCM s16le WAV from [input] into [output]. Emits progress
     * percentages computed against [durationMs]. Flow completes on success and
     * fails with [FfmpegException] on non-zero return code or cancellation.
     */
    fun extractAudio(input: File, output: File, durationMs: Long): Flow<FfmpegProgress>
}

class FfmpegException(
    val returnCodeValue: Int,
    val logsTail: String,
    message: String = "FFmpeg failed (rc=$returnCodeValue): $logsTail",
) : RuntimeException(message)
