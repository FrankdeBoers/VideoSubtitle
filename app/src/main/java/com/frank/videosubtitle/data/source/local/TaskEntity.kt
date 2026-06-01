package com.frank.videosubtitle.data.source.local

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "tasks")
data class TaskEntity(
    @PrimaryKey val id: String,
    val displayName: String,
    val sourceUri: String,
    val cachedPath: String,
    val thumbnailPath: String?,
    val durationMs: Long,
    val width: Int,
    val height: Int,
    val bitrate: Long,
    val audioSampleRate: Int,
    val videoCodec: String?,
    val audioCodec: String?,
    val sizeBytes: Long,
    val stageKind: String,
    val stagePercent: Int,
    val stageMessage: String?,
    val createdAt: Long,
    val updatedAt: Long,
)
