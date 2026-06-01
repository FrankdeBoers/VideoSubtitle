package com.frank.videosubtitle.domain.usecase

import com.frank.videosubtitle.domain.engine.FFmpegEngine
import com.frank.videosubtitle.domain.engine.FfmpegProgress
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import java.io.File

class ExtractAudioUseCase(
    private val engine: FFmpegEngine,
) {
    operator fun invoke(
        input: File,
        output: File,
        durationMs: Long,
    ): Flow<FfmpegProgress> {
        if (output.exists() && output.length() > MIN_VALID_WAV_BYTES) {
            return flowOf(FfmpegProgress(100, durationMs, output.length(), 1.0))
        }
        return flow {
            engine.extractAudio(input, output, durationMs).collect { emit(it) }
        }
    }

    private companion object {
        // 44-byte WAV header + a few PCM frames; smaller means truncated.
        const val MIN_VALID_WAV_BYTES = 1024L
    }
}
