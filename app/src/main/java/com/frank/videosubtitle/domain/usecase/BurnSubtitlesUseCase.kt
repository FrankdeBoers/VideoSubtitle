package com.frank.videosubtitle.domain.usecase

import com.frank.videosubtitle.domain.engine.BurnOptions
import com.frank.videosubtitle.domain.engine.FFmpegEngine
import com.frank.videosubtitle.domain.engine.FfmpegProgress
import kotlinx.coroutines.flow.Flow
import java.io.File

class BurnSubtitlesUseCase(private val engine: FFmpegEngine) {
    operator fun invoke(
        video: File,
        srt: File,
        output: File,
        durationMs: Long,
        options: BurnOptions,
    ): Flow<FfmpegProgress> = engine.burnSubtitles(video, srt, output, durationMs, options)
}
