package com.frank.videosubtitle.domain.usecase

import com.frank.videosubtitle.data.source.local.SrtSerializer
import com.frank.videosubtitle.domain.engine.TranscribeEvent
import com.frank.videosubtitle.domain.engine.WhisperConfig
import com.frank.videosubtitle.domain.engine.WhisperEngine
import com.frank.videosubtitle.domain.model.Subtitle
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import java.io.File

/**
 * Transcribe [wav] into an SRT file at [srtOutput] using Whisper.
 *
 * Idempotent: if [srtOutput] already exists and parses to a non-empty
 * subtitle, we skip inference and emit a single Done event so the
 * orchestrator can short-circuit on resume.
 */
class TranscribeAudioUseCase(
    private val engine: WhisperEngine,
) {
    operator fun invoke(
        wav: File,
        srtOutput: File,
        config: WhisperConfig,
    ): Flow<TranscribeEvent> {
        if (srtOutput.exists() && srtOutput.length() > 0) {
            val cached = runCatching { SrtSerializer.readSrt(srtOutput) }.getOrNull()
            if (cached != null && cached.segments.isNotEmpty()) {
                return flowOf(TranscribeEvent.Progress(100), TranscribeEvent.Done(cached))
            }
        }
        return flow {
            engine.transcribe(wav, config).collect { event ->
                if (event is TranscribeEvent.Done) {
                    persistSrt(event.subtitle, srtOutput)
                    // Snapshot the unedited transcription so the editor's
                    // "restore original" can revert later edits.
                    val original = File(srtOutput.parentFile, ORIGINAL_SRT_NAME)
                    if (!original.exists()) {
                        SrtSerializer.writeSrt(event.subtitle, original)
                    }
                }
                emit(event)
            }
        }
    }

    private fun persistSrt(subtitle: Subtitle, out: File) {
        SrtSerializer.writeSrt(subtitle, out)
    }

    companion object {
        const val ORIGINAL_SRT_NAME = "subtitle.original.srt"
    }
}
