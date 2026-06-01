package com.frank.videosubtitle.domain.model

data class TaskState(
    val id: String,
    val video: VideoMeta,
    val stage: TaskStage,
    val createdAt: Long,
    val updatedAt: Long,
    val processingStartedAt: Long? = null,
)
