package com.frank.videosubtitle.util

sealed interface AppError {
    val cause: Throwable?

    data class ModelMissing(override val cause: Throwable? = null) : AppError
    data class AudioExtractFailed(val message: String, override val cause: Throwable? = null) : AppError
    data class TranscribeFailed(val message: String, override val cause: Throwable? = null) : AppError
    data class BurnFailed(val message: String, override val cause: Throwable? = null) : AppError
    data object Cancelled : AppError {
        override val cause: Throwable? = null
    }
    data class Unknown(override val cause: Throwable? = null) : AppError
}
