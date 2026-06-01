package com.frank.videosubtitle.ui.home

import com.frank.videosubtitle.domain.model.TaskState

data class HomeUiState(
    val tasks: List<TaskState> = emptyList(),
    val isLoading: Boolean = false,
    val errorMessage: String? = null,
)
