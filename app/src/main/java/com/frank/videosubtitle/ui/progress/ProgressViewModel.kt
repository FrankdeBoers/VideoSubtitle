package com.frank.videosubtitle.ui.progress

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.frank.videosubtitle.data.orchestrator.TaskOrchestrator
import com.frank.videosubtitle.data.repository.TaskRepository
import com.frank.videosubtitle.domain.model.TaskStage
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

class ProgressViewModel(
    private val taskId: String,
    private val taskRepository: TaskRepository,
    private val orchestrator: TaskOrchestrator,
) : ViewModel() {

    private val _uiState = MutableStateFlow(ProgressUiState())
    val uiState: StateFlow<ProgressUiState> = _uiState.asStateFlow()

    init {
        viewModelScope.launch {
            taskRepository.observe(taskId).collect { task ->
                if (task == null) {
                    _uiState.update { ProgressUiState() }
                    return@collect
                }
                val (percent, running) = when (val s = task.stage) {
                    is TaskStage.Extracting -> s.percent to (s.percent < 100)
                    is TaskStage.Transcribing -> s.percent to true
                    is TaskStage.Burning -> s.percent to true
                    TaskStage.Editing -> 100 to false
                    is TaskStage.Done -> 100 to false
                    is TaskStage.Failed -> 0 to false
                    TaskStage.Idle -> 0 to false
                }
                val canStart = !running && task.stage is TaskStage.Idle
                _uiState.update {
                    ProgressUiState(
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

    fun startExtraction() {
        orchestrator.startAudioExtraction(taskId)
    }

    fun cancel() {
        orchestrator.cancel(taskId)
    }
}
