package com.frank.videosubtitle.ui.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.frank.videosubtitle.data.repository.ModelDownloadEvent
import com.frank.videosubtitle.data.repository.ModelRepository
import com.frank.videosubtitle.data.repository.SettingsRepository
import com.frank.videosubtitle.data.repository.TaskRepository
import com.frank.videosubtitle.data.source.local.BaiduCreds
import com.frank.videosubtitle.data.source.local.CredentialsSnapshot
import com.frank.videosubtitle.data.source.local.MicrosoftCreds
import com.frank.videosubtitle.data.source.local.TencentCreds
import com.frank.videosubtitle.data.source.local.TranslationCredentialsStore
import com.frank.videosubtitle.data.source.local.YoudaoCreds
import com.frank.videosubtitle.domain.engine.BurnMode
import com.frank.videosubtitle.domain.engine.SubtitleAlignment
import com.frank.videosubtitle.domain.engine.SubtitleDisplay
import com.frank.videosubtitle.domain.model.AppSettings
import com.frank.videosubtitle.domain.model.LanguagePref
import com.frank.videosubtitle.domain.model.MediaBackend
import com.frank.videosubtitle.domain.model.SubtitleColor
import com.frank.videosubtitle.domain.model.TranslationProvider
import com.frank.videosubtitle.domain.model.isInProgress
import com.frank.videosubtitle.domain.model.VideoPreset
import com.frank.videosubtitle.domain.model.WhisperModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import timber.log.Timber
import java.io.File

class SettingsViewModel(
    private val settings: SettingsRepository,
    private val taskRepository: TaskRepository,
    private val modelRepository: ModelRepository,
    private val credentialsStore: TranslationCredentialsStore,
    private val cacheDir: File,
) : ViewModel() {

    val state: StateFlow<AppSettings> = settings.observe()
        .stateIn(viewModelScope, SharingStarted.Eagerly, AppSettings())

    private val _credentials = MutableStateFlow(credentialsStore.snapshot())
    val credentials: StateFlow<CredentialsSnapshot> = _credentials.asStateFlow()

    val anyTaskRunning: StateFlow<Boolean> = taskRepository.observeAll()
        .map { tasks -> tasks.any { it.stage.isInProgress() } }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), false)

    private val _modelStatus = MutableStateFlow(initialStatus())
    val modelStatus: StateFlow<Map<WhisperModel, ModelCardStatus>> = _modelStatus.asStateFlow()

    private val downloadJobs = mutableMapOf<WhisperModel, Job>()

    private val _effects = Channel<Effect>(Channel.BUFFERED)
    val effects = _effects.receiveAsFlow()

    fun setModel(model: WhisperModel) = viewModelScope.launch { settings.setModel(model) }
    fun setLanguage(language: LanguagePref) = viewModelScope.launch { settings.setLanguage(language) }
    fun setBurnMode(mode: BurnMode) = viewModelScope.launch { settings.setBurnMode(mode) }
    fun setPreset(preset: VideoPreset) = viewModelScope.launch { settings.setPreset(preset) }
    fun setFontSize(size: Int) = viewModelScope.launch { settings.setFontSize(size) }
    fun setFontColor(color: SubtitleColor) = viewModelScope.launch { settings.setFontColor(color) }
    fun setOutline(enabled: Boolean) = viewModelScope.launch { settings.setOutline(enabled) }
    fun setFontSizeTranslated(size: Int) = viewModelScope.launch { settings.setFontSizeTranslated(size) }
    fun setFontColorTranslated(color: SubtitleColor) =
        viewModelScope.launch { settings.setFontColorTranslated(color) }
    fun setOutlineTranslated(enabled: Boolean) =
        viewModelScope.launch { settings.setOutlineTranslated(enabled) }
    fun setAlignment(alignment: SubtitleAlignment) = viewModelScope.launch { settings.setAlignment(alignment) }
    fun setMarginV(value: Int) = viewModelScope.launch { settings.setMarginV(value) }
    fun setMarginH(value: Int) = viewModelScope.launch { settings.setMarginH(value) }
    fun setBackground(enabled: Boolean) = viewModelScope.launch { settings.setBackground(enabled) }
    fun setBackgroundOpacity(value: Int) = viewModelScope.launch { settings.setBackgroundOpacity(value) }
    fun setSubtitleDisplay(display: SubtitleDisplay) =
        viewModelScope.launch { settings.setSubtitleDisplay(display) }
    fun setTranslateToChinese(enabled: Boolean) = viewModelScope.launch { settings.setTranslateToChinese(enabled) }
    fun setTranslationProvider(provider: TranslationProvider) =
        viewModelScope.launch { settings.setTranslationProvider(provider) }
    fun setMediaBackend(backend: MediaBackend) =
        viewModelScope.launch { settings.setMediaBackend(backend) }
    fun setThreadCount(value: Int) = viewModelScope.launch { settings.setThreadCount(value) }

    fun setBaiduCreds(creds: BaiduCreds) {
        credentialsStore.setBaidu(creds)
        _credentials.value = credentialsStore.snapshot()
    }
    fun setYoudaoCreds(creds: YoudaoCreds) {
        credentialsStore.setYoudao(creds)
        _credentials.value = credentialsStore.snapshot()
    }
    fun setTencentCreds(creds: TencentCreds) {
        credentialsStore.setTencent(creds)
        _credentials.value = credentialsStore.snapshot()
    }
    fun setMicrosoftCreds(creds: MicrosoftCreds) {
        credentialsStore.setMicrosoft(creds)
        _credentials.value = credentialsStore.snapshot()
    }

    fun startDownload(model: WhisperModel) {
        if (downloadJobs[model]?.isActive == true) return
        if (modelRepository.isAvailable(model)) {
            _modelStatus.update { it + (model to ModelCardStatus.Ready) }
            return
        }
        _modelStatus.update {
            it + (model to ModelCardStatus.Downloading(0, 0, model.sizeBytes))
        }
        downloadJobs[model] = viewModelScope.launch {
            modelRepository.download(model)
                .catch { t ->
                    Timber.e(t, "model download failed: %s", model.fileName)
                    _modelStatus.update {
                        it + (model to ModelCardStatus.Failed(t.message ?: t.javaClass.simpleName))
                    }
                }
                .collect { event ->
                    when (event) {
                        is ModelDownloadEvent.Progress -> _modelStatus.update {
                            it + (model to ModelCardStatus.Downloading(
                                percent = event.percent,
                                downloaded = event.downloaded,
                                total = event.total,
                            ))
                        }
                        is ModelDownloadEvent.Done -> _modelStatus.update {
                            it + (model to ModelCardStatus.Ready)
                        }
                    }
                }
            downloadJobs.remove(model)
        }
    }

    fun cancelDownload(model: WhisperModel) {
        downloadJobs.remove(model)?.cancel()
        val nextStatus = if (modelRepository.isAvailable(model)) {
            ModelCardStatus.Ready
        } else {
            ModelCardStatus.Missing
        }
        _modelStatus.update { it + (model to nextStatus) }
    }

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

    private fun initialStatus(): Map<WhisperModel, ModelCardStatus> =
        WhisperModel.entries.associateWith {
            if (modelRepository.isAvailable(it)) ModelCardStatus.Ready else ModelCardStatus.Missing
        }

    sealed class Effect {
        data object CacheCleared : Effect()
        data object CacheBlocked : Effect()
    }
}

sealed interface ModelCardStatus {
    data object Missing : ModelCardStatus
    data class Downloading(val percent: Int, val downloaded: Long, val total: Long) : ModelCardStatus
    data object Ready : ModelCardStatus
    data class Failed(val reason: String) : ModelCardStatus
}
