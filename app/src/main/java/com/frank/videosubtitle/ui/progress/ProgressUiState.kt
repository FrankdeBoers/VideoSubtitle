package com.frank.videosubtitle.ui.progress

import com.frank.videosubtitle.domain.model.TaskState

data class ProgressUiState(
    val task: TaskState? = null,
    val percent: Int = 0,
    val running: Boolean = false,
    val canStart: Boolean = false,
    val canCancel: Boolean = false,
)
