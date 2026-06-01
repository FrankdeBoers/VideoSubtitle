package com.frank.videosubtitle.ui.progress

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.frank.videosubtitle.data.orchestrator.TaskOrchestrator
import com.frank.videosubtitle.data.repository.ModelDownloadEvent
import com.frank.videosubtitle.data.repository.ModelRepository
import com.frank.videosubtitle.data.repository.SettingsRepository
import com.frank.videosubtitle.data.repository.TaskRepository
import com.frank.videosubtitle.domain.model.TaskStage
import com.frank.videosubtitle.domain.model.WhisperModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import timber.log.Timber
import java.io.File

class ProgressViewModel(
    private val taskId: String,
    private val taskRepository: TaskRepository,
    private val modelRepository: ModelRepository,
    private val orchestrator: TaskOrchestrator,
    private val settingsRepository: SettingsRepository,
) : ViewModel() {

    private val _uiState = MutableStateFlow(ProgressUiState())
    val uiState: StateFlow<ProgressUiState> = _uiState.asStateFlow()

    private val activeModel: StateFlow<WhisperModel> = settingsRepository.observe()
        .map { it.model }
        .distinctUntilChanged()
        .stateIn(viewModelScope, SharingStarted.Eagerly, WhisperModel.Base)

    private var downloadJob: Job? = null

    init {
        viewModelScope.launch {
            activeModel.collect { refreshModelStatus(it) }
        }
        viewModelScope.launch {
            taskRepository.observe(taskId).collect { task ->
                if (task == null) {
                    _uiState.update { it.copy(task = null) }
                    return@collect
                }
                val (percent, running) = when (val s = task.stage) {
                    is TaskStage.Extracting -> s.percent to (s.percent < 100)
                    is TaskStage.Transcribing -> s.percent to true
                    is TaskStage.Burning -> s.percent to (s.percent < 100)
                    TaskStage.Editing -> 100 to false
                    is TaskStage.Done -> 100 to false
                    is TaskStage.Failed -> 0 to false
                    TaskStage.Idle -> 0 to false
                }
                _uiState.update { current ->
                    val modelReady = current.model is ModelStatus.Ready
                    val canStart = !running &&
                        (task.stage is TaskStage.Idle || task.stage is TaskStage.Failed) &&
                        modelReady
                    current.copy(
                        task = task,
                        percent = percent,
                        running = running,
                        canStart = canStart,
                        canCancel = running,
                    )
                }
            }
        }
    }

    fun startPipeline() {
        if (_uiState.value.model !is ModelStatus.Ready) return
        orchestrator.start(taskId)
    }

    fun cancel() {
        orchestrator.cancel(taskId)
    }

    fun retry() {
        val task = _uiState.value.task ?: return
        val taskDir = File(task.video.cachedPath).parentFile ?: return
        val srt = File(taskDir, "subtitle.srt")
        if (srt.exists() && srt.length() > 0) {
            orchestrator.startBurn(taskId)
        } else {
            startPipeline()
        }
    }

    fun downloadModel() {
        if (downloadJob?.isActive == true) return
        val model = activeModel.value
        downloadJob = viewModelScope.launch {
            _uiState.update { it.copy(model = ModelStatus.Downloading(0, 0, model.sizeBytes)) }
            modelRepository.download(model)
                .catch { t ->
                    Timber.e(t, "model download failed")
                    _uiState.update {
                        it.copy(model = ModelStatus.Failed(t.message ?: t.javaClass.simpleName))
                    }
                }
                .collect { event ->
                    when (event) {
                        is ModelDownloadEvent.Progress -> _uiState.update {
                            it.copy(
                                model = ModelStatus.Downloading(
                                    percent = event.percent,
                                    downloaded = event.downloaded,
                                    total = event.total,
                                ),
                            )
                        }
                        is ModelDownloadEvent.Done -> _uiState.update { current ->
                            val task = current.task
                            val canStart = task != null && !current.running &&
                                (task.stage is TaskStage.Idle || task.stage is TaskStage.Failed)
                            current.copy(model = ModelStatus.Ready, canStart = canStart)
                        }
                    }
                }
        }
    }

    private fun refreshModelStatus(model: WhisperModel) {
        // Cancel any in-flight download for the previous model — its status no
        // longer matches the user's selection.
        downloadJob?.cancel()
        downloadJob = null
        val ready = modelRepository.isAvailable(model)
        _uiState.update {
            it.copy(
                model = if (ready) ModelStatus.Ready
                else ModelStatus.Missing(model.fileName, model.sizeBytes),
            )
        }
    }
}
