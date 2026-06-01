package com.frank.videosubtitle.data.repository

import com.frank.videosubtitle.data.source.local.SettingsDataStore
import com.frank.videosubtitle.domain.engine.BurnMode
import com.frank.videosubtitle.domain.engine.SubtitleAlignment
import com.frank.videosubtitle.domain.engine.SubtitleDisplay
import com.frank.videosubtitle.domain.model.AppSettings
import com.frank.videosubtitle.domain.model.LanguagePref
import com.frank.videosubtitle.domain.model.MediaBackend
import com.frank.videosubtitle.domain.model.SubtitleColor
import com.frank.videosubtitle.domain.model.TranslationProvider
import com.frank.videosubtitle.domain.model.VideoPreset
import com.frank.videosubtitle.domain.model.WhisperModel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first

interface SettingsRepository {
    fun observe(): Flow<AppSettings>
    suspend fun current(): AppSettings
    suspend fun setModel(model: WhisperModel)
    suspend fun setLanguage(language: LanguagePref)
    suspend fun setBurnMode(mode: BurnMode)
    suspend fun setPreset(preset: VideoPreset)
    suspend fun setFontSize(size: Int)
    suspend fun setFontColor(color: SubtitleColor)
    suspend fun setOutline(enabled: Boolean)
    suspend fun setFontSizeTranslated(size: Int)
    suspend fun setFontColorTranslated(color: SubtitleColor)
    suspend fun setOutlineTranslated(enabled: Boolean)
    suspend fun setAlignment(alignment: SubtitleAlignment)
    suspend fun setMarginV(value: Int)
    suspend fun setMarginH(value: Int)
    suspend fun setBackground(enabled: Boolean)
    suspend fun setBackgroundOpacity(value: Int)
    suspend fun setSubtitleDisplay(display: SubtitleDisplay)
    suspend fun setTranslateToChinese(enabled: Boolean)
    suspend fun setTranslationProvider(provider: TranslationProvider)
    suspend fun setMediaBackend(backend: MediaBackend)
}

class DefaultSettingsRepository(
    private val dataStore: SettingsDataStore,
) : SettingsRepository {

    override fun observe(): Flow<AppSettings> = dataStore.flow

    override suspend fun current(): AppSettings = dataStore.flow.first()

    override suspend fun setModel(model: WhisperModel) {
        dataStore.setModel(model)
    }
    override suspend fun setLanguage(language: LanguagePref) {
        dataStore.setLanguage(language)
    }
    override suspend fun setBurnMode(mode: BurnMode) {
        dataStore.setBurnMode(mode)
    }
    override suspend fun setPreset(preset: VideoPreset) {
        dataStore.setPreset(preset)
    }
    override suspend fun setFontSize(size: Int) {
        dataStore.setFontSize(size)
    }
    override suspend fun setFontColor(color: SubtitleColor) {
        dataStore.setFontColor(color)
    }
    override suspend fun setOutline(enabled: Boolean) {
        dataStore.setOutline(enabled)
    }
    override suspend fun setFontSizeTranslated(size: Int) {
        dataStore.setFontSizeTranslated(size)
    }
    override suspend fun setFontColorTranslated(color: SubtitleColor) {
        dataStore.setFontColorTranslated(color)
    }
    override suspend fun setOutlineTranslated(enabled: Boolean) {
        dataStore.setOutlineTranslated(enabled)
    }
    override suspend fun setAlignment(alignment: SubtitleAlignment) {
        dataStore.setAlignment(alignment)
    }
    override suspend fun setMarginV(value: Int) {
        dataStore.setMarginV(value)
    }
    override suspend fun setMarginH(value: Int) {
        dataStore.setMarginH(value)
    }
    override suspend fun setBackground(enabled: Boolean) {
        dataStore.setBackground(enabled)
    }
    override suspend fun setBackgroundOpacity(value: Int) {
        dataStore.setBackgroundOpacity(value)
    }
    override suspend fun setSubtitleDisplay(display: SubtitleDisplay) {
        dataStore.setSubtitleDisplay(display)
    }
    override suspend fun setTranslateToChinese(enabled: Boolean) {
        dataStore.setTranslateToChinese(enabled)
    }
    override suspend fun setTranslationProvider(provider: TranslationProvider) {
        dataStore.setTranslationProvider(provider)
    }
    override suspend fun setMediaBackend(backend: MediaBackend) {
        dataStore.setMediaBackend(backend)
    }
}
