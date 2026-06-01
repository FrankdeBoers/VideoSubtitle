package com.frank.videosubtitle.data.repository

import com.frank.videosubtitle.data.source.local.TaskDao
import com.frank.videosubtitle.data.source.local.toEntity
import com.frank.videosubtitle.data.source.local.toState
import com.frank.videosubtitle.domain.model.TaskStage
import com.frank.videosubtitle.domain.model.TaskState
import com.frank.videosubtitle.domain.usecase.TranscribeAudioUseCase
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import timber.log.Timber
import java.io.File

interface TaskRepository {
    fun observeAll(): Flow<List<TaskState>>
    fun observe(id: String): Flow<TaskState?>
    suspend fun find(id: String): TaskState?
    suspend fun insert(task: TaskState)
    suspend fun update(task: TaskState)
    suspend fun delete(id: String)

    /**
     * Sweep tasks left in an in-progress stage by a process kill. Inspect the
     * task directory and rewind to the latest completed step:
     * - subtitle.srt present → Editing
     * - audio.wav present → Idle (user can re-run; transcribe is idempotent)
     * - otherwise → Idle
     */
    suspend fun recoverInterrupted()
}

class DefaultTaskRepository(
    private val dao: TaskDao,
) : TaskRepository {

    override fun observeAll(): Flow<List<TaskState>> =
        dao.observeAll().map { list -> list.map { it.toState() } }

    override fun observe(id: String): Flow<TaskState?> =
        dao.observeById(id).map { it?.toState() }

    override suspend fun find(id: String): TaskState? = dao.findById(id)?.toState()

    override suspend fun insert(task: TaskState) {
        dao.insert(task.toEntity())
    }

    override suspend fun update(task: TaskState) {
        dao.update(task.copy(updatedAt = System.currentTimeMillis()).toEntity())
    }

    override suspend fun delete(id: String) {
        dao.deleteById(id)
    }

    override suspend fun recoverInterrupted() {
        val all = dao.findAll()
        for (entity in all) {
            val state = entity.toState()
            val stage = state.stage
            val needsRewind = stage is TaskStage.Extracting ||
                stage is TaskStage.Transcribing ||
                stage is TaskStage.Burning
            if (!needsRewind) continue
            val taskDir = File(state.video.cachedPath).parentFile
            val srt = taskDir?.let { File(it, "subtitle.srt") }
            val originalSrt = taskDir?.let { File(it, TranscribeAudioUseCase.ORIGINAL_SRT_NAME) }
            val audio = taskDir?.let { File(it, "audio.wav") }
            val newStage: TaskStage = when {
                srt != null && srt.exists() && srt.length() > 0 -> TaskStage.Editing
                originalSrt != null && originalSrt.exists() && originalSrt.length() > 0 -> TaskStage.Editing
                audio != null && audio.exists() && audio.length() > 0 -> TaskStage.Idle
                else -> TaskStage.Idle
            }
            Timber.i("Recovery: task %s %s → %s", state.id, stage::class.simpleName, newStage::class.simpleName)
            update(state.copy(stage = newStage))
        }
    }
}
