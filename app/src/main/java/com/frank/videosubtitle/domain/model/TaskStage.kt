package com.frank.videosubtitle.domain.model

sealed interface TaskStage {
    data object Idle : TaskStage
    data class Extracting(val percent: Int) : TaskStage
    data class Transcribing(val percent: Int) : TaskStage
    data object Editing : TaskStage
    data class Burning(val percent: Int) : TaskStage
    data class Done(val outputPath: String) : TaskStage
    data class Failed(val reason: String) : TaskStage
}
