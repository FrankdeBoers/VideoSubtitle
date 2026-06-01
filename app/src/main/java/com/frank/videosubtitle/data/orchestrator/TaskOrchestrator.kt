package com.frank.videosubtitle.data.orchestrator

import com.frank.videosubtitle.data.repository.TaskRepository
import com.frank.videosubtitle.domain.model.TaskStage
import com.frank.videosubtitle.domain.usecase.ExtractAudioUseCase
import com.frank.videosubtitle.util.DispatcherProvider
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.onCompletion
import kotlinx.coroutines.launch
import timber.log.Timber
import java.io.File
import java.util.concurrent.ConcurrentHashMap

/**
 * Drives long-running pipeline steps from an application-scoped coroutine
 * so they survive ViewModel recreation. Phase 6 will move the actual
 * execution into a foreground Service; for now the orchestrator stays in
 * process and writes progress to [TaskRepository] which is observable via
 * Room.
 */
class TaskOrchestrator(
    private val appScope: CoroutineScope,
    private val dispatchers: DispatcherProvider,
    private val taskRepository: TaskRepository,
    private val extractAudio: ExtractAudioUseCase,
) {

    private val jobs = ConcurrentHashMap<String, Job>()

    fun startAudioExtraction(taskId: String) {
        if (jobs[taskId]?.isActive == true) {
            Timber.d("Audio extraction already running for %s", taskId)
            return
        }
        jobs[taskId] = appScope.launch(dispatchers.io) {
            val task = taskRepository.find(taskId) ?: run {
                Timber.w("startAudioExtraction: task %s not found", taskId); return@launch
            }
            val source = File(task.video.cachedPath)
            if (!source.exists()) {
                taskRepository.update(task.copy(stage = TaskStage.Failed("Source file missing: ${source.path}")))
                return@launch
            }
            val output = File(source.parentFile, "audio.wav")
            taskRepository.update(task.copy(stage = TaskStage.Extracting(0)))

            extractAudio(source, output, task.video.durationMs)
                .catch { t ->
                    Timber.e(t, "extractAudio failed for %s", taskId)
                    val current = taskRepository.find(taskId) ?: task
                    taskRepository.update(current.copy(stage = TaskStage.Failed(t.message ?: t.javaClass.simpleName)))
                }
                .onCompletion { jobs.remove(taskId) }
                .collect { progress ->
                    val current = taskRepository.find(taskId) ?: return@collect
                    if (current.stage is TaskStage.Failed) return@collect
                    taskRepository.update(current.copy(stage = TaskStage.Extracting(progress.percent)))
                }
        }
    }

    fun cancel(taskId: String) {
        jobs.remove(taskId)?.cancel()
        appScope.launch(dispatchers.io) {
            val current = taskRepository.find(taskId) ?: return@launch
            if (current.stage is TaskStage.Extracting) {
                taskRepository.update(current.copy(stage = TaskStage.Failed("Cancelled")))
            }
        }
    }
}
