package com.frank.videosubtitle.data.source.local

import com.frank.videosubtitle.domain.model.TaskStage
import com.frank.videosubtitle.domain.model.TaskState
import com.frank.videosubtitle.domain.model.VideoMeta
import org.junit.Assert.assertEquals
import org.junit.Test

class TaskMappersTest {

    private fun sampleVideo() = VideoMeta(
        displayName = "clip.mp4",
        sourceUri = "content://media/external/video/1",
        cachedPath = "/data/cache/tasks/abc/source.mp4",
        thumbnailPath = "/data/cache/tasks/abc/thumb.jpg",
        durationMs = 12_345L,
        width = 1920,
        height = 1080,
        bitrate = 8_000_000L,
        audioSampleRate = 48_000,
        videoCodec = null,
        audioCodec = null,
        sizeBytes = 100_000_000L,
    )

    private fun stateWith(stage: TaskStage) = TaskState(
        id = "task-1",
        video = sampleVideo(),
        stage = stage,
        createdAt = 1_000L,
        updatedAt = 2_000L,
    )

    @Test fun roundTrip_idle() {
        val original = stateWith(TaskStage.Idle)
        assertEquals(original, original.toEntity().toState())
    }

    @Test fun roundTrip_extracting_preservesPercent() {
        val original = stateWith(TaskStage.Extracting(percent = 42))
        assertEquals(original, original.toEntity().toState())
    }

    @Test fun roundTrip_transcribing_preservesPercent() {
        val original = stateWith(TaskStage.Transcribing(percent = 73))
        assertEquals(original, original.toEntity().toState())
    }

    @Test fun roundTrip_editing() {
        val original = stateWith(TaskStage.Editing)
        assertEquals(original, original.toEntity().toState())
    }

    @Test fun roundTrip_burning_preservesPercent() {
        val original = stateWith(TaskStage.Burning(percent = 5))
        assertEquals(original, original.toEntity().toState())
    }

    @Test fun roundTrip_done_preservesOutputPath() {
        val original = stateWith(TaskStage.Done(outputPath = "/sdcard/Movies/VideoSubtitle/clip.mp4"))
        assertEquals(original, original.toEntity().toState())
    }

    @Test fun roundTrip_failed_preservesReason() {
        val original = stateWith(TaskStage.Failed(reason = "Whisper OOM"))
        assertEquals(original, original.toEntity().toState())
    }

    @Test fun stageKind_isStableString() {
        // Stage kinds are persisted; test pins them so a rename triggers a failed test, not a silent migration.
        assertEquals("idle", stateWith(TaskStage.Idle).toEntity().stageKind)
        assertEquals("extracting", stateWith(TaskStage.Extracting(0)).toEntity().stageKind)
        assertEquals("transcribing", stateWith(TaskStage.Transcribing(0)).toEntity().stageKind)
        assertEquals("editing", stateWith(TaskStage.Editing).toEntity().stageKind)
        assertEquals("burning", stateWith(TaskStage.Burning(0)).toEntity().stageKind)
        assertEquals("done", stateWith(TaskStage.Done("")).toEntity().stageKind)
        assertEquals("failed", stateWith(TaskStage.Failed("")).toEntity().stageKind)
    }
}
