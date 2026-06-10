package com.frank.videosubtitle.ui.editor

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.frank.videosubtitle.R
import com.frank.videosubtitle.data.orchestrator.TaskOrchestrator
import com.frank.videosubtitle.data.repository.TaskRepository
import com.frank.videosubtitle.data.source.local.SrtSerializer
import com.frank.videosubtitle.domain.model.SubtitleSegment
import com.frank.videosubtitle.domain.usecase.TranscribeAudioUseCase
import com.frank.videosubtitle.util.DispatcherProvider
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import timber.log.Timber
import java.io.File

class EditorViewModel(
    private val taskId: String,
    private val taskRepository: TaskRepository,
    private val dispatchers: DispatcherProvider,
    private val orchestrator: TaskOrchestrator,
) : ViewModel() {

    private val _uiState = MutableStateFlow(EditorUiState())
    val uiState: StateFlow<EditorUiState> = _uiState.asStateFlow()

    private val _effects = Channel<EditorEffect>(Channel.BUFFERED)
    val effects = _effects.receiveAsFlow()

    private var srtFile: File? = null
    private var originalSrtFile: File? = null

    init {
        viewModelScope.launch { load() }
    }

    private suspend fun load() {
        val task = taskRepository.find(taskId) ?: run {
            _effects.trySend(EditorEffect.NavigateBack)
            return
        }
        val taskDir = File(task.video.cachedPath).parentFile ?: run {
            _effects.trySend(EditorEffect.NavigateBack)
            return
        }
        val srt = File(taskDir, "subtitle.srt")
        val original = File(taskDir, TranscribeAudioUseCase.ORIGINAL_SRT_NAME)
        srtFile = srt
        originalSrtFile = original

        val segments = withContext(dispatchers.io) {
            if (srt.exists() && srt.length() > 0) {
                runCatching { SrtSerializer.readSrt(srt).segments }.getOrDefault(emptyList())
            } else emptyList()
        }
        _uiState.update {
            it.copy(
                segments = segments,
                isDirty = false,
                originalAvailable = original.exists() && original.length() > 0,
                loaded = true,
                title = task.video.displayName,
            )
        }
        if (segments.isEmpty()) {
            _effects.trySend(EditorEffect.Toast(R.string.editor_empty))
        }
    }

    fun updateText(index: Int, newText: String) {
        val state = _uiState.value
        val seg = state.segments.getOrNull(index) ?: return
        val cleaned = newText.trim()
        if (cleaned == seg.text) return
        val previous = state.segments
        val updated = previous.toMutableList().apply {
            this[index] = seg.copy(text = cleaned)
        }
        // The user has actively engaged with the subtitles — supersede any
        // pending auto-burn timer the orchestrator armed when this task
        // entered Editing, otherwise their edits would be discarded by a
        // burn run reading the on-disk SRT before [save] runs.
        orchestrator.cancelAutoBurn(taskId)
        _uiState.update { it.copy(segments = updated, isDirty = true) }
        _effects.trySend(EditorEffect.UndoSnackbar(R.string.editor_edit_undo, previous))
    }

    /** Returns null on success, or a string-res id describing why the edit was rejected. */
    fun updateTime(index: Int, startMs: Long, endMs: Long): Int? {
        val state = _uiState.value
        val seg = state.segments.getOrNull(index) ?: return R.string.editor_validation_time_format
        if (startMs >= endMs) return R.string.editor_validation_time_range
        val prev = state.segments.getOrNull(index - 1)
        val next = state.segments.getOrNull(index + 1)
        if (prev != null && startMs < prev.endMs) return R.string.editor_validation_time_overlap
        if (next != null && endMs > next.startMs) return R.string.editor_validation_time_overlap
        if (seg.startMs == startMs && seg.endMs == endMs) return null
        val previous = state.segments
        val updated = previous.toMutableList().apply {
            this[index] = seg.copy(startMs = startMs, endMs = endMs)
        }
        // See updateText: edits supersede the auto-burn grace timer.
        orchestrator.cancelAutoBurn(taskId)
        _uiState.update { it.copy(segments = updated, isDirty = true) }
        _effects.trySend(EditorEffect.UndoSnackbar(R.string.editor_edit_undo, previous))
        return null
    }

    fun delete(index: Int) {
        val state = _uiState.value
        if (index !in state.segments.indices) return
        val previous = state.segments
        val updated = previous.toMutableList().apply { removeAt(index) }
        orchestrator.cancelAutoBurn(taskId)
        _uiState.update { it.copy(segments = updated, isDirty = true) }
        _effects.trySend(EditorEffect.UndoSnackbar(R.string.editor_delete_undo, previous))
    }

    fun moveSegment(from: Int, to: Int) {
        val state = _uiState.value
        if (from == to || from !in state.segments.indices || to !in state.segments.indices) return
        val previous = state.segments
        val updated = previous.toMutableList().apply {
            val seg = removeAt(from)
            add(to, seg)
        }
        orchestrator.cancelAutoBurn(taskId)
        _uiState.update { it.copy(segments = updated, isDirty = true) }
        _effects.trySend(EditorEffect.UndoSnackbar(R.string.editor_reorder_undo, previous))
    }

    /** Restore a snapshot captured before a mutation — invoked from the Undo Snackbar action. */
    fun restoreSnapshot(snapshot: List<SubtitleSegment>) {
        _uiState.update { it.copy(segments = snapshot, isDirty = true) }
    }

    fun save(onComplete: (saved: Boolean) -> Unit = {}) {
        val state = _uiState.value
        val out = srtFile ?: run { onComplete(false); return }
        viewModelScope.launch {
            val ok = withContext(dispatchers.io) {
                runCatching {
                    SrtSerializer.writeSrt(
                        com.frank.videosubtitle.domain.model.Subtitle(
                            segments = renumber(state.segments),
                            language = null,
                        ),
                        out,
                    )
                }.onFailure { Timber.e(it, "save failed") }.isSuccess
            }
            if (ok) {
                _uiState.update { it.copy(isDirty = false) }
                _effects.trySend(EditorEffect.Toast(R.string.editor_save_success))
            } else {
                _effects.trySend(EditorEffect.Toast(R.string.editor_save_failure, "I/O"))
            }
            onComplete(ok)
        }
    }

    fun export() {
        val state = _uiState.value
        if (state.segments.isEmpty()) {
            _effects.trySend(EditorEffect.Toast(R.string.editor_empty))
            return
        }
        val proceed: () -> Unit = {
            orchestrator.startBurn(taskId)
            _effects.trySend(EditorEffect.NavigateBack)
        }
        if (state.isDirty) {
            save { ok -> if (ok) proceed() }
        } else {
            proceed()
        }
    }

    fun restoreOriginal() {
        val original = originalSrtFile?.takeIf { it.exists() && it.length() > 0 } ?: run {
            _effects.trySend(EditorEffect.Toast(R.string.editor_restore_unavailable))
            return
        }
        // Loading the original mid-grace-period is also "user is engaged".
        orchestrator.cancelAutoBurn(taskId)
        viewModelScope.launch {
            val segments = withContext(dispatchers.io) {
                runCatching { SrtSerializer.readSrt(original).segments }.getOrDefault(emptyList())
            }
            _uiState.update { it.copy(segments = segments, isDirty = true) }
            _effects.trySend(EditorEffect.Toast(R.string.editor_restore_success))
        }
    }

    private fun renumber(segments: List<SubtitleSegment>): List<SubtitleSegment> =
        segments.mapIndexed { i, seg -> if (seg.index == i + 1) seg else seg.copy(index = i + 1) }
}
