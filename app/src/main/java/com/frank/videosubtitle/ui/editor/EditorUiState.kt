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

    /**
     * One-shot snackbar requesting that the previous segments list be restored.
     * Emitted after any reversible mutation (text/time edit, delete, reorder).
     */
    data class UndoSnackbar(
        val messageRes: Int,
        val previous: List<SubtitleSegment>,
    ) : EditorEffect
}
