package com.frank.videosubtitle.data.source.local

import com.frank.videosubtitle.domain.model.TaskStage
import com.frank.videosubtitle.domain.model.TaskState
import com.frank.videosubtitle.domain.model.VideoMeta

internal object StageKind {
    const val IDLE = "idle"
    const val EXTRACTING = "extracting"
    const val TRANSCRIBING = "transcribing"
    const val TRANSLATING = "translating"
    const val EDITING = "editing"
    const val BURNING = "burning"
    const val DONE = "done"
    const val FAILED = "failed"
}

fun TaskState.toEntity(): TaskEntity {
    val (kind, percent, message) = when (val s = stage) {
        TaskStage.Idle -> Triple(StageKind.IDLE, 0, null)
        is TaskStage.Extracting -> Triple(StageKind.EXTRACTING, s.percent, null)
        is TaskStage.Transcribing -> Triple(StageKind.TRANSCRIBING, s.percent, null)
        is TaskStage.Translating -> Triple(StageKind.TRANSLATING, s.percent, null)
        TaskStage.Editing -> Triple(StageKind.EDITING, 0, null)
        is TaskStage.Burning -> Triple(StageKind.BURNING, s.percent, null)
        is TaskStage.Done -> Triple(StageKind.DONE, 100, s.outputPath)
        is TaskStage.Failed -> Triple(StageKind.FAILED, 0, s.reason)
    }
    return TaskEntity(
        id = id,
        displayName = video.displayName,
        sourceUri = video.sourceUri,
        cachedPath = video.cachedPath,
        thumbnailPath = video.thumbnailPath,
        durationMs = video.durationMs,
        width = video.width,
        height = video.height,
        bitrate = video.bitrate,
        audioSampleRate = video.audioSampleRate,
        videoCodec = video.videoCodec,
        audioCodec = video.audioCodec,
        sizeBytes = video.sizeBytes,
        stageKind = kind,
        stagePercent = percent,
        stageMessage = message,
        createdAt = createdAt,
        updatedAt = updatedAt,
        processingStartedAt = processingStartedAt,
    )
}

fun TaskEntity.toState(): TaskState {
    val stage = when (stageKind) {
        StageKind.IDLE -> TaskStage.Idle
        StageKind.EXTRACTING -> TaskStage.Extracting(stagePercent)
        StageKind.TRANSCRIBING -> TaskStage.Transcribing(stagePercent)
        StageKind.TRANSLATING -> TaskStage.Translating(stagePercent)
        StageKind.EDITING -> TaskStage.Editing
        StageKind.BURNING -> TaskStage.Burning(stagePercent)
        StageKind.DONE -> TaskStage.Done(stageMessage.orEmpty())
        StageKind.FAILED -> TaskStage.Failed(stageMessage.orEmpty())
        else -> TaskStage.Failed("Unknown stage: $stageKind")
    }
    val video = VideoMeta(
        displayName = displayName,
        sourceUri = sourceUri,
        cachedPath = cachedPath,
        thumbnailPath = thumbnailPath,
        durationMs = durationMs,
        width = width,
        height = height,
        bitrate = bitrate,
        audioSampleRate = audioSampleRate,
        videoCodec = videoCodec,
        audioCodec = audioCodec,
        sizeBytes = sizeBytes,
    )
    return TaskState(
        id = id,
        video = video,
        stage = stage,
        createdAt = createdAt,
        updatedAt = updatedAt,
        processingStartedAt = processingStartedAt,
    )
}
