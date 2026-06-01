package com.frank.videosubtitle.ui.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.frank.videosubtitle.data.repository.SettingsRepository
import com.frank.videosubtitle.data.repository.TaskRepository
import com.frank.videosubtitle.domain.engine.BurnMode
import com.frank.videosubtitle.domain.engine.SubtitleAlignment
import com.frank.videosubtitle.domain.model.AppSettings
import com.frank.videosubtitle.domain.model.LanguagePref
import com.frank.videosubtitle.domain.model.SubtitleColor
import com.frank.videosubtitle.domain.model.TaskStage
import com.frank.videosubtitle.domain.model.VideoPreset
import com.frank.videosubtitle.domain.model.WhisperModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import timber.log.Timber
import java.io.File

class SettingsViewModel(
    private val settings: SettingsRepository,
    private val taskRepository: TaskRepository,
    private val cacheDir: File,
) : ViewModel() {

    val state: StateFlow<AppSettings> = settings.observe()
        .stateIn(viewModelScope, SharingStarted.Eagerly, AppSettings())

    val anyTaskRunning: StateFlow<Boolean> = taskRepository.observeAll()
        .map { tasks -> tasks.any { it.stage.isInProgress() } }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), false)

    private val _effects = Channel<Effect>(Channel.BUFFERED)
    val effects = _effects.receiveAsFlow()

    fun setModel(model: WhisperModel) = viewModelScope.launch { settings.setModel(model) }
    fun setLanguage(language: LanguagePref) = viewModelScope.launch { settings.setLanguage(language) }
    fun setBurnMode(mode: BurnMode) = viewModelScope.launch { settings.setBurnMode(mode) }
    fun setPreset(preset: VideoPreset) = viewModelScope.launch { settings.setPreset(preset) }
    fun setFontSize(size: Int) = viewModelScope.launch { settings.setFontSize(size) }
    fun setFontColor(color: SubtitleColor) = viewModelScope.launch { settings.setFontColor(color) }
    fun setOutline(enabled: Boolean) = viewModelScope.launch { settings.setOutline(enabled) }
    fun setAlignment(alignment: SubtitleAlignment) = viewModelScope.launch { settings.setAlignment(alignment) }

    fun clearCache() {
        if (anyTaskRunning.value) {
            _effects.trySend(Effect.CacheBlocked)
            return
        }
        viewModelScope.launch {
            withContext(Dispatchers.IO) {
                runCatching { File(cacheDir, "tasks").deleteRecursively() }
                    .onFailure { Timber.e(it, "clearCache failed") }
            }
            _effects.trySend(Effect.CacheCleared)
        }
    }

    sealed class Effect {
        data object CacheCleared : Effect()
        data object CacheBlocked : Effect()
    }

    private fun TaskStage.isInProgress(): Boolean = when (this) {
        is TaskStage.Extracting,
        is TaskStage.Transcribing,
        is TaskStage.Burning,
        -> true
        else -> false
    }
}
