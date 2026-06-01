package com.frank.videosubtitle.domain.model

data class SubtitleSegment(
    val index: Int,
    val startMs: Long,
    val endMs: Long,
    val text: String,
)

data class Subtitle(
    val segments: List<SubtitleSegment>,
    val language: String?,
)
