package com.frank.videosubtitle.ui.home

import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.frank.videosubtitle.data.orchestrator.TaskOrchestrator
import com.frank.videosubtitle.data.repository.TaskRepository
import com.frank.videosubtitle.data.repository.VideoRepository
import com.frank.videosubtitle.domain.model.TaskStage
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import timber.log.Timber
import java.io.File

class HomeViewModel(
    private val taskRepository: TaskRepository,
    private val videoRepository: VideoRepository,
    private val orchestrator: TaskOrchestrator,
) : ViewModel() {

    private val _uiState = MutableStateFlow(HomeUiState())
    val uiState: StateFlow<HomeUiState> = _uiState.asStateFlow()

    init {
        viewModelScope.launch {
            taskRepository.observeAll().collect { tasks ->
                _uiState.update { state ->
                    val livingIds = tasks.mapTo(mutableSetOf()) { it.id }
                    val pruned = state.selectedIds.intersect(livingIds)
                    state.copy(
                        tasks = tasks,
                        selectedIds = pruned,
                        selectionMode = state.selectionMode && pruned.isNotEmpty(),
                    )
                }
            }
        }
    }

    fun importVideo(uri: Uri) {
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true, errorMessage = null) }
            try {
                videoRepository.importVideo(uri)
                _uiState.update { it.copy(isLoading = false) }
            } catch (t: Throwable) {
                Timber.e(t, "importVideo failed")
                _uiState.update { it.copy(isLoading = false, errorMessage = t.message ?: t.javaClass.simpleName) }
            }
        }
    }

    fun consumeError() {
        _uiState.update { it.copy(errorMessage = null) }
    }

    fun enterSelection(taskId: String) {
        _uiState.update { it.copy(selectionMode = true, selectedIds = it.selectedIds + taskId) }
    }

    fun toggleSelection(taskId: String) {
        _uiState.update { state ->
            val next = if (taskId in state.selectedIds) state.selectedIds - taskId else state.selectedIds + taskId
            state.copy(selectedIds = next, selectionMode = next.isNotEmpty())
        }
    }

    fun selectAll() {
        _uiState.update { state ->
            state.copy(
                selectionMode = state.tasks.isNotEmpty(),
                selectedIds = state.tasks.mapTo(mutableSetOf()) { it.id },
            )
        }
    }

    fun clearSelection() {
        _uiState.update { it.copy(selectionMode = false, selectedIds = emptySet()) }
    }

    fun startSelected() {
        val ids = _uiState.value.selectedIds.toList()
        if (ids.isEmpty()) return
        viewModelScope.launch {
            for (id in ids) {
                val task = taskRepository.find(id) ?: continue
                when (task.stage) {
                    TaskStage.Idle, is TaskStage.Failed -> orchestrator.start(id, autoBurn = true)
                    TaskStage.Editing -> orchestrator.startBurn(id)
                    is TaskStage.Extracting,
                    is TaskStage.Transcribing,
                    is TaskStage.Burning,
                    is TaskStage.Done,
                    -> Unit
                }
            }
            _uiState.update { it.copy(selectionMode = false, selectedIds = emptySet()) }
        }
    }

    fun deleteSelected() {
        val ids = _uiState.value.selectedIds.toList()
        if (ids.isEmpty()) return
        viewModelScope.launch {
            for (id in ids) {
                val task = taskRepository.find(id)
                orchestrator.cancel(id)
                taskRepository.delete(id)
                val taskDir = task?.video?.cachedPath?.let { File(it).parentFile }
                if (taskDir != null && taskDir.exists()) {
                    runCatching { taskDir.deleteRecursively() }
                        .onFailure { Timber.w(it, "Failed to wipe task dir %s", taskDir.path) }
                }
            }
            _uiState.update { it.copy(selectionMode = false, selectedIds = emptySet()) }
        }
    }
}
