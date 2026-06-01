package com.frank.videosubtitle.data.repository

import com.frank.videosubtitle.data.source.local.TaskDao
import com.frank.videosubtitle.data.source.local.toEntity
import com.frank.videosubtitle.data.source.local.toState
import com.frank.videosubtitle.domain.model.TaskState
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

interface TaskRepository {
    fun observeAll(): Flow<List<TaskState>>
    fun observe(id: String): Flow<TaskState?>
    suspend fun find(id: String): TaskState?
    suspend fun insert(task: TaskState)
    suspend fun update(task: TaskState)
    suspend fun delete(id: String)
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
}
