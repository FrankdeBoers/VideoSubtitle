package com.frank.videosubtitle.data.repository

import android.net.Uri
import com.frank.videosubtitle.data.source.media.UriResolver
import com.frank.videosubtitle.domain.model.TaskStage
import com.frank.videosubtitle.domain.model.TaskState
import java.util.UUID

interface VideoRepository {
    suspend fun importVideo(uri: Uri): TaskState
}

class DefaultVideoRepository(
    private val uriResolver: UriResolver,
    private val taskRepository: TaskRepository,
) : VideoRepository {

    override suspend fun importVideo(uri: Uri): TaskState {
        val taskId = UUID.randomUUID().toString()
        val meta = uriResolver.importToCache(uri, taskId)
        val now = System.currentTimeMillis()
        val task = TaskState(
            id = taskId,
            video = meta,
            stage = TaskStage.Idle,
            createdAt = now,
            updatedAt = now,
        )
        taskRepository.insert(task)
        return task
    }
}
