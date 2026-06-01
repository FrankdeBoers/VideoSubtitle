package com.frank.videosubtitle.ui.progress

import com.frank.videosubtitle.domain.model.TaskState

data class ProgressUiState(
    val task: TaskState? = null,
    val percent: Int = 0,
    val running: Boolean = false,
    val canStart: Boolean = false,
    val canCancel: Boolean = false,
    val model: ModelStatus = ModelStatus.Unknown,
)

sealed interface ModelStatus {
    data object Unknown : ModelStatus
    data class Missing(val modelName: String, val sizeBytes: Long) : ModelStatus
    data class Downloading(val percent: Int, val downloaded: Long, val total: Long) : ModelStatus
    data object Ready : ModelStatus
    data class Failed(val reason: String) : ModelStatus
}
