package com.frank.videosubtitle.ui.editor

import com.frank.videosubtitle.domain.model.SubtitleSegment

data class EditorUiState(
    val segments: List<SubtitleSegment> = emptyList(),
    val isDirty: Boolean = false,
    val originalAvailable: Boolean = false,
    val loaded: Boolean = false,
    val title: String = "",
)

sealed interface EditorEffect {
    data class Toast(val messageRes: Int, val arg: String? = null) : EditorEffect
    data object NavigateBack : EditorEffect
}
