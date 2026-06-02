package com.frank.videosubtitle.ui.common

import android.content.Context
import com.frank.videosubtitle.R
import com.frank.videosubtitle.domain.model.TaskStage

/**
 * Default localized label for a [TaskStage]. Used by the home list adapter and
 * the foreground-service notification. The Progress screen has two extra
 * overrides (Extracting@100 → audio-ready, Editing → subtitle-ready) and
 * handles those at the call site before falling back to this.
 */
fun TaskStage.label(context: Context): String = when (this) {
    TaskStage.Idle -> context.getString(R.string.task_stage_idle)
    is TaskStage.Extracting -> context.getString(R.string.task_stage_extracting, percent)
    is TaskStage.Transcribing -> context.getString(R.string.task_stage_transcribing, percent)
    is TaskStage.Translating -> context.getString(R.string.task_stage_translating, percent)
    TaskStage.Editing -> context.getString(R.string.task_stage_editing)
    is TaskStage.Burning -> context.getString(R.string.task_stage_burning, percent)
    is TaskStage.Done -> context.getString(R.string.task_stage_done)
    is TaskStage.Failed -> context.getString(R.string.task_stage_failed, reason)
}
