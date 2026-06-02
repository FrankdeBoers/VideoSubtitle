package com.frank.videosubtitle.domain.model

sealed interface TaskStage {
    data object Idle : TaskStage
    data class Extracting(val percent: Int) : TaskStage
    data class Transcribing(val percent: Int) : TaskStage
    data class Translating(val percent: Int) : TaskStage
    data object Editing : TaskStage
    data class Burning(val percent: Int) : TaskStage
    data class Done(val outputPath: String) : TaskStage
    data class Failed(val reason: String) : TaskStage
}

/**
 * True when the stage represents pipeline work currently advancing on a
 * background coroutine — the foreground service stays alive, the Home
 * cost-time ticker keeps refreshing, and Settings cache-clear is blocked.
 * Editing/Done/Failed/Idle are quiescent.
 */
fun TaskStage.isInProgress(): Boolean = when (this) {
    is TaskStage.Extracting,
    is TaskStage.Transcribing,
    is TaskStage.Translating,
    is TaskStage.Burning,
    -> true
    else -> false
}
