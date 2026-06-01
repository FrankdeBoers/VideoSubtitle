package com.frank.videosubtitle.domain.model

data class VideoMeta(
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
)
