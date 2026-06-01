package com.frank.videosubtitle.domain.usecase

import com.frank.videosubtitle.data.source.local.SrtSerializer
import com.frank.videosubtitle.domain.engine.TranslateEvent
import com.frank.videosubtitle.domain.engine.TranslationEngine
import com.frank.videosubtitle.domain.model.Subtitle
import com.frank.videosubtitle.domain.model.SubtitleSegment
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import java.io.File

/**
 * Translate the SRT at [srtFile] into [targetLanguage] and rewrite it as a
 * bilingual cue list (original line on top, translated line below). The
 * [TranscribeAudioUseCase.ORIGINAL_SRT_NAME] backup is preserved untouched.
 *
 * Idempotency: if [srtFile] already contains bilingual cues (any segment text
 * has more than one line), we skip translation and emit Done immediately. The
 * orchestrator can therefore re-run this step safely after a process kill.
 */
class TranslateSubtitleUseCase(
    private val engine: TranslationEngine,
) {
    operator fun invoke(
        srtFile: File,
        sourceLanguage: String?,
        targetLanguage: String,
    ): Flow<TranslateEvent> = flow {
        val current = SrtSerializer.readSrt(srtFile)
        if (current.segments.isEmpty()) {
            emit(TranslateEvent.Progress(100))
            emit(TranslateEvent.Done(emptyList()))
            return@flow
        }
        if (current.segments.any { it.text.contains('\n') }) {
            emit(TranslateEvent.Progress(100))
            emit(TranslateEvent.Done(current.segments.map { it.text }))
            return@flow
        }

        val sources = current.segments.map { it.text }
        val translations = ArrayList<String>(sources.size)

        engine.translate(sources, sourceLanguage, targetLanguage).collect { event ->
            when (event) {
                is TranslateEvent.Progress -> emit(event)
                is TranslateEvent.Done -> {
                    translations.clear()
                    translations += event.translations
                }
            }
        }

        if (translations.size == sources.size && translations.any { it.isNotBlank() }) {
            val bilingual = current.segments.mapIndexed { i, seg ->
                val tr = translations[i].trim()
                val text = if (tr.isBlank() || tr == seg.text) seg.text else "${seg.text}\n$tr"
                SubtitleSegment(
                    index = seg.index,
                    startMs = seg.startMs,
                    endMs = seg.endMs,
                    text = text,
                )
            }
            SrtSerializer.writeSrt(
                Subtitle(segments = bilingual, language = current.language),
                srtFile,
            )
        }
        emit(TranslateEvent.Done(translations))
    }
}
