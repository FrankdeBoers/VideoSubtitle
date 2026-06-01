package com.frank.videosubtitle.data.orchestrator

import android.content.Context
import android.os.StatFs
import com.frank.videosubtitle.data.repository.ModelRepository
import com.frank.videosubtitle.data.repository.SettingsRepository
import com.frank.videosubtitle.data.repository.TaskRepository
import com.frank.videosubtitle.data.source.media.MediaStoreSaver
import com.frank.videosubtitle.domain.engine.BurnOptions
import com.frank.videosubtitle.domain.engine.TranscribeEvent
import com.frank.videosubtitle.domain.engine.WhisperConfig
import com.frank.videosubtitle.domain.model.AppSettings
import com.frank.videosubtitle.domain.model.TaskStage
import com.frank.videosubtitle.domain.model.WhisperModel
import com.frank.videosubtitle.domain.usecase.BurnSubtitlesUseCase
import com.frank.videosubtitle.domain.usecase.ExtractAudioUseCase
import com.frank.videosubtitle.domain.usecase.TranscribeAudioUseCase
import com.frank.videosubtitle.service.VideoProcessingService
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
    private val context: Context,
    private val appScope: CoroutineScope,
    private val dispatchers: DispatcherProvider,
    private val taskRepository: TaskRepository,
    private val modelRepository: ModelRepository,
    private val extractAudio: ExtractAudioUseCase,
    private val transcribeAudio: TranscribeAudioUseCase,
    private val burnSubtitles: BurnSubtitlesUseCase,
    private val mediaStoreSaver: MediaStoreSaver,
    private val settingsRepository: SettingsRepository,
) {

    private val jobs = ConcurrentHashMap<String, Job>()

    private companion object {
        const val DEFAULT_OUTLINE_WIDTH = 2
    }

    fun start(taskId: String, autoBurn: Boolean = false) {
        if (jobs[taskId]?.isActive == true) {
            Timber.d("Pipeline already running for %s", taskId)
            return
        }
        VideoProcessingService.start(context)
        jobs[taskId] = appScope.launch(dispatchers.io) {
            try {
                val settings = settingsRepository.current()
                val model = settings.model
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

                runTranscription(taskId, audioFile, srtFile, model, modelFile, settings)

                if (autoBurn) {
                    val afterTranscribe = taskRepository.find(taskId) ?: return@launch
                    if (afterTranscribe.stage is TaskStage.Editing &&
                        srtFile.exists() && srtFile.length() > 0L
                    ) {
                        runBurn(
                            taskId = taskId,
                            source = source,
                            srt = srtFile,
                            taskDir = taskDir,
                            displayName = task.video.displayName,
                            durationMs = task.video.durationMs,
                            options = settings.toBurnOptions(),
                        )
                    }
                }
            } finally {
                jobs.remove(taskId)
            }
        }
    }

    fun startBurn(taskId: String) {
        if (jobs[taskId]?.isActive == true) {
            Timber.d("Pipeline already running for %s", taskId)
            return
        }
        VideoProcessingService.start(context)
        jobs[taskId] = appScope.launch(dispatchers.io) {
            try {
                val settings = settingsRepository.current()
                val task = taskRepository.find(taskId) ?: return@launch
                val source = File(task.video.cachedPath)
                val taskDir = source.parentFile ?: error("Task source has no parent dir")
                val srtFile = File(taskDir, "subtitle.srt")
                if (!srtFile.exists() || srtFile.length() == 0L) {
                    taskRepository.update(task.copy(stage = TaskStage.Failed("Subtitle file missing")))
                    return@launch
                }
                val options = settings.toBurnOptions()
                runBurn(taskId, source, srtFile, taskDir, task.video.displayName, task.video.durationMs, options)
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
                is TaskStage.Burning,
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
        // Audio is roughly 32 KB/s of duration but we keep 2x video size as a
        // generous floor so the WAV + later mp4 burn fit on disk.
        val needed = source.length() * 2
        if (!hasEnoughSpace(output.parentFile, needed)) {
            taskRepository.update(
                current.copy(stage = TaskStage.Failed("Insufficient storage (need ~${needed / 1024 / 1024} MB)")),
            )
            return false
        }
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

    private suspend fun runBurn(
        taskId: String,
        source: File,
        srt: File,
        taskDir: File,
        displayName: String,
        durationMs: Long,
        options: BurnOptions,
    ) {
        val current = taskRepository.find(taskId) ?: return
        val needed = source.length() * 2
        if (!hasEnoughSpace(taskDir, needed)) {
            taskRepository.update(
                current.copy(stage = TaskStage.Failed("Insufficient storage (need ~${needed / 1024 / 1024} MB)")),
            )
            return
        }
        taskRepository.update(current.copy(stage = TaskStage.Burning(0)))

        // For HARD burn, force mp4 output. For SOFT, keep mp4 only — softMux requires mov_text.
        val ext = "mp4"
        val baseName = displayName.substringBeforeLast('.', displayName)
            .ifBlank { "subtitled_${taskId.take(8)}" }
        val intermediate = File(taskDir, "output.$ext")

        var failed = false
        burnSubtitles(source, srt, intermediate, durationMs, options)
            .catch { t ->
                failed = true
                Timber.e(t, "burnSubtitles failed for %s", taskId)
                val now = taskRepository.find(taskId) ?: current
                taskRepository.update(now.copy(stage = TaskStage.Failed(t.message ?: t.javaClass.simpleName)))
            }
            .collect { progress ->
                val now = taskRepository.find(taskId) ?: return@collect
                if (now.stage is TaskStage.Failed) return@collect
                taskRepository.update(now.copy(stage = TaskStage.Burning(progress.percent)))
            }
        if (failed) return

        val finalName = uniqueDisplayName("${baseName}_subtitled.$ext")
        val mime = if (ext == "mp4" || ext == "m4v") "video/mp4" else "video/${ext}"
        val saved = runCatching { mediaStoreSaver.saveToMovies(intermediate, finalName, mime) }
            .onFailure { Timber.e(it, "MediaStore save failed") }
        if (saved.isSuccess) {
            val uri = saved.getOrThrow()
            val now = taskRepository.find(taskId) ?: return
            taskRepository.update(now.copy(stage = TaskStage.Done(uri.toString())))
        } else {
            val now = taskRepository.find(taskId) ?: return
            val msg = saved.exceptionOrNull()?.message ?: "MediaStore save failed"
            taskRepository.update(now.copy(stage = TaskStage.Failed(msg)))
        }
    }

    private fun uniqueDisplayName(name: String): String = name

    private fun AppSettings.toBurnOptions(): BurnOptions = BurnOptions(
        mode = burnMode,
        preset = preset.ffmpegPreset,
        fontSize = fontSize,
        fontColorArgb = fontColor.argb,
        outlineWidth = if (outline) DEFAULT_OUTLINE_WIDTH else 0,
        alignment = alignment,
    )

    private fun hasEnoughSpace(dir: File?, neededBytes: Long): Boolean {
        val target = dir ?: return true
        return runCatching {
            val stat = StatFs(target.absolutePath)
            stat.availableBytes >= neededBytes
        }.getOrDefault(true)
    }

    private suspend fun runTranscription(
        taskId: String,
        wav: File,
        srtOutput: File,
        model: WhisperModel,
        modelFile: File,
        settings: AppSettings,
    ) {
        val current = taskRepository.find(taskId) ?: return
        taskRepository.update(current.copy(stage = TaskStage.Transcribing(0)))

        val config = WhisperConfig(
            model = model,
            modelFile = modelFile,
            language = settings.language.whisperCode,
            translate = false,
            initialPrompt = settings.language.initialPrompt,
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
