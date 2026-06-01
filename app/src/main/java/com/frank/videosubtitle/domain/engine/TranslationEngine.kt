package com.frank.videosubtitle.domain.engine

import kotlinx.coroutines.flow.Flow

sealed interface TranslateEvent {
    data class Progress(val percent: Int) : TranslateEvent
    data class Done(val translations: List<String>) : TranslateEvent
}

class TranslationException(message: String, cause: Throwable? = null) :
    RuntimeException(message, cause)

interface TranslationEngine {
    /**
     * Translate a list of source strings into [targetLanguage]. Implementations
     * should batch where possible and emit [TranslateEvent.Progress] as
     * segments complete (0..100). On completion, emit [TranslateEvent.Done]
     * with translations aligned 1:1 with the input list (empty string when a
     * segment cannot be translated).
     *
     * [sourceLanguage] is a BCP-47-ish hint (e.g. "en", "ja", "auto"). When
     * null or "auto", the engine should detect per-batch.
     *
     * Implementations are responsible for downloading/caching their own
     * model assets — the first call may block for tens of seconds on a fresh
     * device.
     */
    fun translate(
        sources: List<String>,
        sourceLanguage: String?,
        targetLanguage: String,
    ): Flow<TranslateEvent>
}
