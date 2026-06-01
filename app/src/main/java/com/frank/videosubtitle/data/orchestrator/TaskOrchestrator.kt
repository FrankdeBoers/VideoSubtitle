package com.frank.videosubtitle.data.orchestrator

import com.frank.videosubtitle.data.repository.ModelRepository
import com.frank.videosubtitle.data.repository.TaskRepository
import com.frank.videosubtitle.domain.engine.TranscribeEvent
import com.frank.videosubtitle.domain.engine.WhisperConfig
import com.frank.videosubtitle.domain.model.TaskStage
import com.frank.videosubtitle.domain.model.WhisperModel
import com.frank.videosubtitle.domain.usecase.ExtractAudioUseCase
import com.frank.videosubtitle.domain.usecase.TranscribeAudioUseCase
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
    private val modelRepository: ModelRepository,
    private val extractAudio: ExtractAudioUseCase,
    private val transcribeAudio: TranscribeAudioUseCase,
) {

    private val jobs = ConcurrentHashMap<String, Job>()

    fun start(taskId: String, model: WhisperModel = WhisperModel.Base) {
        if (jobs[taskId]?.isActive == true) {
            Timber.d("Pipeline already running for %s", taskId)
            return
        }
        jobs[taskId] = appScope.launch(dispatchers.io) {
            try {
                val task = taskRepository.find(taskId) ?: run {
                    Timber.w("start: task %s not found", taskId); return@launch
                }
                val source = File(task.video.cachedPath)
                if (!source.exists()) {
                    taskRepository.update(task.copy(stage = TaskStage.Failed("Source file missing: ${source.path}")))
                    return@launch
                }
                val taskDir = source.parentFile ?: error("Task source has no parent dir")
                val audioFile = File(taskDir, "audio.wav")
                val srtFile = File(taskDir, "subtitle.srt")

                if (!runExtraction(taskId, source, audioFile, task.video.durationMs)) return@launch

                val modelFile = modelRepository.fileFor(model)
                if (!modelRepository.isAvailable(model)) {
                    val current = taskRepository.find(taskId) ?: task
                    taskRepository.update(
                        current.copy(stage = TaskStage.Failed("Model ${model.fileName} not downloaded")),
                    )
                    return@launch
                }

                runTranscription(taskId, audioFile, srtFile, model, modelFile)
            } finally {
                jobs.remove(taskId)
            }
        }
    }

    fun cancel(taskId: String) {
        jobs.remove(taskId)?.cancel()
        appScope.launch(dispatchers.io) {
            val current = taskRepository.find(taskId) ?: return@launch
            when (current.stage) {
                is TaskStage.Extracting,
                is TaskStage.Transcribing,
                -> taskRepository.update(current.copy(stage = TaskStage.Failed("Cancelled")))
                else -> Unit
            }
        }
    }

    private suspend fun runExtraction(
        taskId: String,
        source: File,
        output: File,
        durationMs: Long,
    ): Boolean {
        var failed = false
        val current = taskRepository.find(taskId) ?: return false
        taskRepository.update(current.copy(stage = TaskStage.Extracting(0)))

        extractAudio(source, output, durationMs)
            .catch { t ->
                failed = true
                Timber.e(t, "extractAudio failed for %s", taskId)
                val now = taskRepository.find(taskId) ?: current
                taskRepository.update(now.copy(stage = TaskStage.Failed(t.message ?: t.javaClass.simpleName)))
            }
            .collect { progress ->
                val now = taskRepository.find(taskId) ?: return@collect
                if (now.stage is TaskStage.Failed) return@collect
                taskRepository.update(now.copy(stage = TaskStage.Extracting(progress.percent)))
            }
        return !failed
    }

    private suspend fun runTranscription(
        taskId: String,
        wav: File,
        srtOutput: File,
        model: WhisperModel,
        modelFile: File,
    ) {
        val current = taskRepository.find(taskId) ?: return
        taskRepository.update(current.copy(stage = TaskStage.Transcribing(0)))

        val config = WhisperConfig(
            model = model,
            modelFile = modelFile,
            // null = let whisper auto-detect; settings UI in Phase 7 will override.
            language = null,
            translate = false,
            initialPrompt = null,
            nThreads = 4,
        )

        transcribeAudio(wav, srtOutput, config)
            .catch { t ->
                Timber.e(t, "transcribeAudio failed for %s", taskId)
                val now = taskRepository.find(taskId) ?: current
                if (now.stage !is TaskStage.Failed) {
                    taskRepository.update(now.copy(stage = TaskStage.Failed(t.message ?: t.javaClass.simpleName)))
                }
            }
            .onCompletion { Timber.d("transcribe pipeline completed for %s", taskId) }
            .collect { event ->
                val now = taskRepository.find(taskId) ?: return@collect
                if (now.stage is TaskStage.Failed) return@collect
                when (event) {
                    is TranscribeEvent.Progress -> {
                        taskRepository.update(now.copy(stage = TaskStage.Transcribing(event.percent)))
                    }
                    is TranscribeEvent.Done -> {
                        // Phase 4 (editor) will hold this state; for now mark Editing.
                        taskRepository.update(now.copy(stage = TaskStage.Editing))
                    }
                }
            }
    }
}
