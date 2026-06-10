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
import com.frank.videosubtitle.domain.engine.ComputeMode
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
    suspend fun setFontSizeTranslated(size: Int) = store.edit {
        it[KEY_FONT_SIZE_TR] = size.coerceIn(AppSettings.MIN_FONT_SIZE, AppSettings.MAX_FONT_SIZE)
    }
    suspend fun setFontColorTranslated(color: SubtitleColor) = store.edit {
        it[KEY_FONT_COLOR_TR] = color.name
    }
    suspend fun setOutlineTranslated(enabled: Boolean) = store.edit { it[KEY_OUTLINE_TR] = enabled }
    suspend fun setAlignment(alignment: SubtitleAlignment) = store.edit {
        it[KEY_ALIGNMENT] = alignment.name
    }
    suspend fun setMarginV(value: Int) = store.edit {
        it[KEY_MARGIN_V] = value.coerceIn(AppSettings.MIN_MARGIN_V, AppSettings.MAX_MARGIN_V)
    }
    suspend fun setMarginH(value: Int) = store.edit {
        it[KEY_MARGIN_H] = value.coerceIn(AppSettings.MIN_MARGIN_H, AppSettings.MAX_MARGIN_H)
    }
    suspend fun setBackground(enabled: Boolean) = store.edit { it[KEY_BACKGROUND] = enabled }
    suspend fun setBackgroundOpacity(value: Int) = store.edit {
        it[KEY_BG_OPACITY] = value.coerceIn(AppSettings.MIN_BG_OPACITY, AppSettings.MAX_BG_OPACITY)
    }
    suspend fun setSubtitleDisplay(display: SubtitleDisplay) = store.edit {
        it[KEY_SUBTITLE_DISPLAY] = display.name
    }
    suspend fun setTranslateToChinese(enabled: Boolean) = store.edit {
        it[KEY_TRANSLATE_ZH] = enabled
    }
    suspend fun setTranslationProvider(provider: TranslationProvider) = store.edit {
        it[KEY_TRANSLATION_PROVIDER] = provider.name
    }
    suspend fun setMediaBackend(backend: MediaBackend) = store.edit {
        it[KEY_MEDIA_BACKEND] = backend.name
    }
    suspend fun setThreadCount(value: Int) = store.edit {
        // 0 = Auto. Positive values are upper-bounded at runtime against the
        // device's actual core count, so we don't pin a max here.
        it[KEY_THREAD_COUNT] = value.coerceAtLeast(0)
    }
    suspend fun setComputeMode(mode: ComputeMode) = store.edit { it[KEY_COMPUTE_MODE] = mode.name }

    private fun Preferences.toAppSettings(): AppSettings = AppSettings(
        model = readEnum(KEY_MODEL, WhisperModel.Tiny),
        language = readEnum(KEY_LANGUAGE, LanguagePref.Auto),
        burnMode = readEnum(KEY_BURN_MODE, BurnMode.HARD),
        preset = readEnum(KEY_PRESET, VideoPreset.Medium),
        fontSize = (this[KEY_FONT_SIZE] ?: AppSettings.DEFAULT_FONT_SIZE)
            .coerceIn(AppSettings.MIN_FONT_SIZE, AppSettings.MAX_FONT_SIZE),
        fontColor = readEnum(KEY_FONT_COLOR, SubtitleColor.White),
        outline = this[KEY_OUTLINE] ?: true,
        fontSizeTranslated = (this[KEY_FONT_SIZE_TR] ?: AppSettings.DEFAULT_FONT_SIZE)
            .coerceIn(AppSettings.MIN_FONT_SIZE, AppSettings.MAX_FONT_SIZE),
        fontColorTranslated = readEnum(KEY_FONT_COLOR_TR, SubtitleColor.Yellow),
        outlineTranslated = this[KEY_OUTLINE_TR] ?: true,
        alignment = readEnum(KEY_ALIGNMENT, SubtitleAlignment.BottomCenter),
        marginV = (this[KEY_MARGIN_V] ?: AppSettings.DEFAULT_MARGIN_V)
            .coerceIn(AppSettings.MIN_MARGIN_V, AppSettings.MAX_MARGIN_V),
        marginH = (this[KEY_MARGIN_H] ?: 0)
            .coerceIn(AppSettings.MIN_MARGIN_H, AppSettings.MAX_MARGIN_H),
        background = this[KEY_BACKGROUND] ?: false,
        backgroundOpacity = (this[KEY_BG_OPACITY] ?: AppSettings.DEFAULT_BG_OPACITY)
            .coerceIn(AppSettings.MIN_BG_OPACITY, AppSettings.MAX_BG_OPACITY),
        subtitleDisplay = readEnum(KEY_SUBTITLE_DISPLAY, SubtitleDisplay.Both),
        translateToChinese = this[KEY_TRANSLATE_ZH] ?: true,
        translationProvider = readEnum(KEY_TRANSLATION_PROVIDER, TranslationProvider.MlKit),
        mediaBackend = readEnum(KEY_MEDIA_BACKEND, MediaBackend.Ffmpeg),
        threadCount = (this[KEY_THREAD_COUNT] ?: AppSettings.THREAD_COUNT_AUTO).coerceAtLeast(0),
        computeMode = readEnum(KEY_COMPUTE_MODE, ComputeMode.Auto),
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
        private val KEY_FONT_SIZE_TR = intPreferencesKey("font_size_translated")
        private val KEY_FONT_COLOR_TR = stringPreferencesKey("font_color_translated")
        private val KEY_OUTLINE_TR = booleanPreferencesKey("outline_translated")
        private val KEY_ALIGNMENT = stringPreferencesKey("alignment")
        private val KEY_MARGIN_V = intPreferencesKey("margin_v")
        private val KEY_MARGIN_H = intPreferencesKey("margin_h")
        private val KEY_BACKGROUND = booleanPreferencesKey("background")
        private val KEY_BG_OPACITY = intPreferencesKey("background_opacity")
        private val KEY_SUBTITLE_DISPLAY = stringPreferencesKey("subtitle_display")
        private val KEY_TRANSLATE_ZH = booleanPreferencesKey("translate_to_chinese")
        private val KEY_TRANSLATION_PROVIDER = stringPreferencesKey("translation_provider")
        private val KEY_MEDIA_BACKEND = stringPreferencesKey("media_backend")
        private val KEY_THREAD_COUNT = intPreferencesKey("thread_count")
        private val KEY_COMPUTE_MODE = stringPreferencesKey("compute_mode")
    }
}
