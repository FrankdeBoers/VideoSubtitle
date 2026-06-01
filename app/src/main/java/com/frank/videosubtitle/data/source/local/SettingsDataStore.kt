package com.frank.videosubtitle.data.source.local

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.frank.videosubtitle.domain.engine.BurnMode
import com.frank.videosubtitle.domain.engine.SubtitleAlignment
import com.frank.videosubtitle.domain.model.AppSettings
import com.frank.videosubtitle.domain.model.LanguagePref
import com.frank.videosubtitle.domain.model.SubtitleColor
import com.frank.videosubtitle.domain.model.TranslationProvider
import com.frank.videosubtitle.domain.model.VideoPreset
import com.frank.videosubtitle.domain.model.WhisperModel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.settingsDataStore: DataStore<Preferences> by preferencesDataStore(name = "settings")

/**
 * Bridges the typed [AppSettings] domain model and the Preferences-DataStore
 * key-value soup. New fields land here: pick a key, add a getter (with default
 * fallback), add a setter. Don't change existing key strings without a
 * migration — DataStore stores them verbatim on disk.
 */
class SettingsDataStore(context: Context) {

    private val store: DataStore<Preferences> = context.applicationContext.settingsDataStore

    val flow: Flow<AppSettings> = store.data.map { prefs -> prefs.toAppSettings() }

    suspend fun setModel(model: WhisperModel) = store.edit { it[KEY_MODEL] = model.name }
    suspend fun setLanguage(language: LanguagePref) = store.edit { it[KEY_LANGUAGE] = language.name }
    suspend fun setBurnMode(mode: BurnMode) = store.edit { it[KEY_BURN_MODE] = mode.name }
    suspend fun setPreset(preset: VideoPreset) = store.edit { it[KEY_PRESET] = preset.name }
    suspend fun setFontSize(size: Int) = store.edit {
        it[KEY_FONT_SIZE] = size.coerceIn(AppSettings.MIN_FONT_SIZE, AppSettings.MAX_FONT_SIZE)
    }
    suspend fun setFontColor(color: SubtitleColor) = store.edit { it[KEY_FONT_COLOR] = color.name }
    suspend fun setOutline(enabled: Boolean) = store.edit { it[KEY_OUTLINE] = enabled }
    suspend fun setAlignment(alignment: SubtitleAlignment) = store.edit {
        it[KEY_ALIGNMENT] = alignment.name
    }
    suspend fun setTranslateToChinese(enabled: Boolean) = store.edit {
        it[KEY_TRANSLATE_ZH] = enabled
    }
    suspend fun setTranslationProvider(provider: TranslationProvider) = store.edit {
        it[KEY_TRANSLATION_PROVIDER] = provider.name
    }

    private fun Preferences.toAppSettings(): AppSettings = AppSettings(
        model = readEnum(KEY_MODEL, WhisperModel.Base),
        language = readEnum(KEY_LANGUAGE, LanguagePref.Auto),
        burnMode = readEnum(KEY_BURN_MODE, BurnMode.HARD),
        preset = readEnum(KEY_PRESET, VideoPreset.Medium),
        fontSize = (this[KEY_FONT_SIZE] ?: AppSettings.DEFAULT_FONT_SIZE)
            .coerceIn(AppSettings.MIN_FONT_SIZE, AppSettings.MAX_FONT_SIZE),
        fontColor = readEnum(KEY_FONT_COLOR, SubtitleColor.White),
        outline = this[KEY_OUTLINE] ?: true,
        alignment = readEnum(KEY_ALIGNMENT, SubtitleAlignment.BottomCenter),
        translateToChinese = this[KEY_TRANSLATE_ZH] ?: true,
        translationProvider = readEnum(KEY_TRANSLATION_PROVIDER, TranslationProvider.MlKit),
    )

    private inline fun <reified T : Enum<T>> Preferences.readEnum(
        key: Preferences.Key<String>,
        default: T,
    ): T {
        val raw = this[key] ?: return default
        return runCatching { enumValueOf<T>(raw) }.getOrDefault(default)
    }

    companion object {
        private val KEY_MODEL = stringPreferencesKey("model")
        private val KEY_LANGUAGE = stringPreferencesKey("language")
        private val KEY_BURN_MODE = stringPreferencesKey("burn_mode")
        private val KEY_PRESET = stringPreferencesKey("preset")
        private val KEY_FONT_SIZE = intPreferencesKey("font_size")
        private val KEY_FONT_COLOR = stringPreferencesKey("font_color")
        private val KEY_OUTLINE = booleanPreferencesKey("outline")
        private val KEY_ALIGNMENT = stringPreferencesKey("alignment")
        private val KEY_TRANSLATE_ZH = booleanPreferencesKey("translate_to_chinese")
        private val KEY_TRANSLATION_PROVIDER = stringPreferencesKey("translation_provider")
    }
}
