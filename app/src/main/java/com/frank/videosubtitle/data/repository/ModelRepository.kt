package com.frank.videosubtitle.data.repository

import com.frank.videosubtitle.domain.model.WhisperModel
import kotlinx.coroutines.flow.Flow
import java.io.File

sealed interface ModelDownloadEvent {
    data class Progress(val downloaded: Long, val total: Long) : ModelDownloadEvent {
        val percent: Int get() = if (total > 0) ((downloaded * 100) / total).toInt().coerceIn(0, 100) else 0
    }
    data class Done(val file: File) : ModelDownloadEvent
}

class ModelChecksumException(message: String) : RuntimeException(message)

interface ModelRepository {
    /** Path the model is/should-be stored at (filesDir/models/<name>). */
    fun fileFor(model: WhisperModel): File

    /** True iff the model file exists and matches its pinned size. */
    fun isAvailable(model: WhisperModel): Boolean

    /**
     * Stream the model from HuggingFace into [fileFor]. Resumes from the
     * `.part` file if present (HTTP Range). Verifies SHA-256 on completion;
     * a mismatch deletes the file and throws [ModelChecksumException].
     */
    fun download(model: WhisperModel): Flow<ModelDownloadEvent>
}
