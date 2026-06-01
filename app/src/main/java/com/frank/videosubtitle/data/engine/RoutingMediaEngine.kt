package com.frank.videosubtitle.data.engine

import androidx.media3.common.util.UnstableApi
import com.frank.videosubtitle.data.repository.SettingsRepository
import com.frank.videosubtitle.domain.engine.BurnOptions
import com.frank.videosubtitle.domain.engine.FFmpegEngine
import com.frank.videosubtitle.domain.engine.FfmpegProgress
import com.frank.videosubtitle.domain.model.MediaBackend
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import timber.log.Timber
import java.io.File

/**
 * Picks between [FFmpegKitEngine] (software, libass) and
 * [Media3TransformerEngine] (hardware, MediaCodec + OverlayEffect) per call,
 * based on the user's `mediaBackend` setting.
 *
 * Burn fallback rule: when the user picked AndroidMedia but the burn options
 * include distinct translated styling (libass-only inline overrides), this
 * engine silently falls back to the FFmpeg path for that single call. The
 * settings UI explains the rule via `settings_engine_android_media_desc`.
 */
@UnstableApi
class RoutingMediaEngine(
    private val ffmpeg: FFmpegKitEngine,
    private val media3: Media3TransformerEngine,
    private val settings: SettingsRepository,
) : FFmpegEngine {

    override fun extractAudio(
        input: File,
        output: File,
        durationMs: Long,
    ): Flow<FfmpegProgress> = flow {
        val backend = settings.current().mediaBackend
        val engine: FFmpegEngine = when (backend) {
            MediaBackend.Ffmpeg -> ffmpeg
            MediaBackend.AndroidMedia -> media3
        }
        Timber.d("extractAudio routed to %s", engine::class.simpleName)
        emitAll(engine.extractAudio(input, output, durationMs))
    }

    override fun burnSubtitles(
        video: File,
        srt: File,
        output: File,
        durationMs: Long,
        options: BurnOptions,
    ): Flow<FfmpegProgress> = flow {
        val backend = settings.current().mediaBackend
        val needsLibass = hasTranslatedOverrides(options)
        val engine: FFmpegEngine = when (backend) {
            MediaBackend.Ffmpeg -> ffmpeg
            MediaBackend.AndroidMedia -> if (needsLibass) {
                Timber.d("burnSubtitles: AndroidMedia requested but per-line overrides set; falling back to FFmpeg")
                ffmpeg
            } else {
                media3
            }
        }
        Timber.d("burnSubtitles routed to %s", engine::class.simpleName)
        emitAll(engine.burnSubtitles(video, srt, output, durationMs, options))
    }

    private fun hasTranslatedOverrides(o: BurnOptions): Boolean =
        o.fontSizeTranslated != null ||
            o.fontColorTranslatedArgb != null ||
            o.outlineWidthTranslated != null
}
