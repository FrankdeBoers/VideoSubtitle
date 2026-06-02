package com.frank.videosubtitle.data.orchestrator

import android.content.Context
import android.os.StatFs
import com.frank.videosubtitle.data.repository.ModelRepository
import com.frank.videosubtitle.data.repository.SettingsRepository
import com.frank.videosubtitle.data.repository.TaskRepository
import com.frank.videosubtitle.data.source.media.MediaStoreSaver
import com.frank.videosubtitle.domain.engine.BurnOptions
import com.frank.videosubtitle.domain.engine.TranscribeEvent
import com.frank.videosubtitle.domain.engine.TranslateEvent
import com.frank.videosubtitle.domain.engine.WhisperConfig
import com.frank.videosubtitle.domain.model.AppSettings
import com.frank.videosubtitle.domain.model.LanguagePref
import com.frank.videosubtitle.domain.model.TaskStage
import com.frank.videosubtitle.domain.model.WhisperModel
import com.frank.videosubtitle.domain.usecase.BurnSubtitlesUseCase
import com.frank.videosubtitle.domain.usecase.ExtractAudioUseCase
import com.frank.videosubtitle.domain.usecase.TranscribeAudioUseCase
import com.frank.videosubtitle.domain.usecase.TranslateSubtitleUseCase
import com.frank.videosubtitle.service.VideoProcessingService
import com.frank.videosubtitle.util.DispatcherProvider
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.onCompletion
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
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
    private val translateSubtitle: TranslateSubtitleUseCase,
    private val burnSubtitles: BurnSubtitlesUseCase,
    private val mediaStoreSaver: MediaStoreSaver,
    private val settingsRepository: SettingsRepository,
) {

    private val jobs = ConcurrentHashMap<String, Job>()

    // Whisper inference is CPU-bound and uses ~4 internal threads per call;
    // each open context also keeps a full copy of the model (75–466 MB) in
    // RAM. Running it concurrently across tasks oversubscribes the CPU,
    // triggers thermal throttling, and risks LMK on phones — net wall-clock
    // is worse than back-to-back. Extract/burn still run in parallel; only
    // the transcribe stage queues on this gate.
    private val transcriptionGate = Mutex()

    private companion object {
        const val DEFAULT_OUTLINE_WIDTH = 2
        const val TARGET_LANG_CHINESE = "zh"
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

                taskRepository.find(taskId)?.let { fresh ->
                    taskRepository.update(fresh.copy(processingStartedAt = System.currentTimeMillis()))
                }
                if (!runExtraction(taskId, source, audioFile, task.video.durationMs)) return@launch

                val modelFile = modelRepository.fileFor(model)
                if (!modelRepository.isAvailable(model)) {
                    val current = taskRepository.find(taskId) ?: task
                    taskRepository.update(
                        current.copy(stage = TaskStage.Failed("Model ${model.fileName} not downloaded")),
                    )
                    return@launch
                }

                if (!runTranscription(taskId, audioFile, srtFile, model, modelFile, settings)) return@launch
                if (!runTranslation(taskId, srtFile, settings)) return@launch

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
                taskRepository.update(task.copy(processingStartedAt = System.currentTimeMillis()))
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
                is TaskStage.Translating,
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

        // mp4 is the only supported output container — HARD re-encodes via libx264 and
        // SOFT requires mov_text which is mp4-only.
        val baseName = displayName.substringBeforeLast('.', displayName)
            .ifBlank { "subtitled_${taskId.take(8)}" }
        val intermediate = File(taskDir, "output.mp4")

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

        // MediaStore on Android Q+ auto-suffixes display-name collisions ((1), (2), …);
        // pre-Q falls back to FileProvider over a path that includes the taskId so it's unique by construction.
        val finalName = "${baseName}_subtitled.mp4"
        val saved = runCatching { mediaStoreSaver.saveToMovies(intermediate, finalName, "video/mp4") }
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

    private fun AppSettings.toBurnOptions(): BurnOptions {
        val mainOutline = if (outline) DEFAULT_OUTLINE_WIDTH else 0
        val trOutline = if (outlineTranslated) DEFAULT_OUTLINE_WIDTH else 0
        val translatedDiffers = fontSizeTranslated != fontSize ||
            fontColorTranslated != fontColor ||
            outlineTranslated != outline
        // Map percent (0..100) → ASS alpha byte (0=transparent, 255=opaque).
        val bgAlpha = (backgroundOpacity.coerceIn(0, 100) * 255 / 100)
        return BurnOptions(
            mode = burnMode,
            preset = preset.ffmpegPreset,
            fontSize = fontSize,
            fontColorArgb = fontColor.argb,
            outlineWidth = mainOutline,
            fontSizeTranslated = if (translatedDiffers) fontSizeTranslated else null,
            fontColorTranslatedArgb = if (translatedDiffers) fontColorTranslated.argb else null,
            outlineWidthTranslated = if (translatedDiffers) trOutline else null,
            alignment = alignment,
            marginV = marginV,
            marginH = marginH,
            background = background,
            backgroundAlpha = bgAlpha,
            displayMode = subtitleDisplay,
        )
    }

    // Use every online core the device exposes for whisper. ggml is fully
    // compute-bound and scales near-linearly with threads on big.LITTLE arm64
    // up to the physical core count; oversubscription beyond availableProcessors
    // hurts. availableProcessors() reflects currently-online cores at call time,
    // which is what we want — read once when transcription kicks off.
    private fun whisperThreadCount(): Int =
        Runtime.getRuntime().availableProcessors().coerceAtLeast(1)

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
    ): Boolean {
        val current = taskRepository.find(taskId) ?: return false
        taskRepository.update(current.copy(stage = TaskStage.Transcribing(0)))

        val config = WhisperConfig(
            model = model,
            modelFile = modelFile,
            language = settings.language.whisperCode,
            translate = false,
            initialPrompt = settings.language.initialPrompt,
            nThreads = whisperThreadCount(),
        )

        var failed = false
        transcriptionGate.withLock {
            transcribeAudio(wav, srtOutput, config)
                .catch { t ->
                    failed = true
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
                    if (event is TranscribeEvent.Progress) {
                        taskRepository.update(now.copy(stage = TaskStage.Transcribing(event.percent)))
                    }
                    // Done is consumed by the orchestrator after the next pipeline
                    // step (translate) decides whether to advance to Editing.
                }
        }
        return !failed
    }

    /**
     * Translate [srtFile] in place into a bilingual cue list (original on top,
     * Chinese below). Skips when the user has translation off, when the Whisper
     * source language is already Chinese, or when the SRT is empty. Marks the
     * task [TaskStage.Editing] on success so the user can review.
     */
    private suspend fun runTranslation(
        taskId: String,
        srtFile: File,
        settings: AppSettings,
    ): Boolean {
        val current = taskRepository.find(taskId) ?: return false
        if (!srtFile.exists() || srtFile.length() == 0L) {
            taskRepository.update(current.copy(stage = TaskStage.Failed("Subtitle file missing after transcription")))
            return false
        }
        val skip = !settings.translateToChinese ||
            settings.language == LanguagePref.ZhCn
        if (skip) {
            taskRepository.update(current.copy(stage = TaskStage.Editing))
            return true
        }

        taskRepository.update(current.copy(stage = TaskStage.Translating(0)))
        var failed = false
        translateSubtitle(
            srtFile = srtFile,
            sourceLanguage = settings.language.whisperCode,
            targetLanguage = TARGET_LANG_CHINESE,
        )
            .catch { t ->
                failed = true
                Timber.e(t, "translateSubtitle failed for %s", taskId)
                val now = taskRepository.find(taskId) ?: current
                if (now.stage !is TaskStage.Failed) {
                    taskRepository.update(now.copy(stage = TaskStage.Failed(t.message ?: t.javaClass.simpleName)))
                }
            }
            .collect { event ->
                val now = taskRepository.find(taskId) ?: return@collect
                if (now.stage is TaskStage.Failed) return@collect
                when (event) {
                    is TranslateEvent.Progress -> taskRepository.update(
                        now.copy(stage = TaskStage.Translating(event.percent)),
                    )
                    is TranslateEvent.Done -> taskRepository.update(
                        now.copy(stage = TaskStage.Editing),
                    )
                }
            }
        return !failed
    }
}
