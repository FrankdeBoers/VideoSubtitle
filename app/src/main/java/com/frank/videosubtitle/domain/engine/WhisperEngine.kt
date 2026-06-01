package com.frank.videosubtitle.domain.engine

import com.frank.videosubtitle.domain.model.Subtitle
import com.frank.videosubtitle.domain.model.WhisperModel
import kotlinx.coroutines.flow.Flow
import java.io.File

data class WhisperConfig(
    val model: WhisperModel,
    val modelFile: File,
    val language: String? = null,           // null = auto
    val translate: Boolean = false,
    val initialPrompt: String? = null,
    val nThreads: Int = 4,
)

sealed interface TranscribeEvent {
    data class Progress(val percent: Int) : TranscribeEvent
    data class Done(val subtitle: Subtitle) : TranscribeEvent
}

class WhisperException(message: String, cause: Throwable? = null) :
    RuntimeException(message, cause)

interface WhisperEngine {
    /**
     * Run whisper inference on [wav] (must be 16 kHz mono PCM s16le).
     * Emits [TranscribeEvent.Progress] periodically (driven by whisper.cpp's
     * progress_callback, polled every ~250ms) and finally a single
     * [TranscribeEvent.Done] before completing. Cancellation cleans up the
     * native context and stops the inference at the next abort_callback.
     */
    fun transcribe(wav: File, config: WhisperConfig): Flow<TranscribeEvent>
}
