package com.frank.videosubtitle.ui.settings

import android.content.Context
import com.frank.videosubtitle.R
import com.frank.videosubtitle.domain.engine.SubtitleDisplay
import com.frank.videosubtitle.domain.model.LanguagePref
import com.frank.videosubtitle.domain.model.TranslationProvider
import com.frank.videosubtitle.domain.model.VideoPreset
import com.frank.videosubtitle.domain.model.WhisperModel

internal fun Context.languageLabel(pref: LanguagePref): String = getString(
    when (pref) {
        LanguagePref.Auto -> R.string.settings_lang_auto
        LanguagePref.ZhCn -> R.string.settings_lang_zh
        LanguagePref.En -> R.string.settings_lang_en
        LanguagePref.Ja -> R.string.settings_lang_ja
        LanguagePref.Ko -> R.string.settings_lang_ko
    },
)

internal fun Context.displayLabel(mode: SubtitleDisplay): String = getString(
    when (mode) {
        SubtitleDisplay.Both -> R.string.settings_display_both
        SubtitleDisplay.MainOnly -> R.string.settings_display_main
        SubtitleDisplay.TranslatedOnly -> R.string.settings_display_translated
    },
)

internal fun Context.presetLabel(preset: VideoPreset): String = getString(
    when (preset) {
        VideoPreset.Ultrafast -> R.string.settings_preset_ultrafast
        VideoPreset.Fast -> R.string.settings_preset_fast
        VideoPreset.Medium -> R.string.settings_preset_medium
        VideoPreset.Slow -> R.string.settings_preset_slow
    },
)

internal fun Context.providerLabel(provider: TranslationProvider): String = getString(
    when (provider) {
        TranslationProvider.MlKit -> R.string.settings_translate_provider_mlkit
        TranslationProvider.Baidu -> R.string.settings_translate_provider_baidu
        TranslationProvider.Youdao -> R.string.settings_translate_provider_youdao
        TranslationProvider.Tencent -> R.string.settings_translate_provider_tencent
        TranslationProvider.Microsoft -> R.string.settings_translate_provider_microsoft
    },
)

internal fun providerHintRes(provider: TranslationProvider): Int = when (provider) {
    TranslationProvider.MlKit -> R.string.settings_translate_provider_mlkit_hint
    TranslationProvider.Baidu -> R.string.settings_translate_provider_baidu_hint
    TranslationProvider.Youdao -> R.string.settings_translate_provider_youdao_hint
    TranslationProvider.Tencent -> R.string.settings_translate_provider_tencent_hint
    TranslationProvider.Microsoft -> R.string.settings_translate_provider_microsoft_hint
}

internal fun modelNameRes(model: WhisperModel): Int = when (model) {
    WhisperModel.Tiny -> R.string.settings_model_tiny_name
    WhisperModel.Base -> R.string.settings_model_base_name
    WhisperModel.Small -> R.string.settings_model_small_name
}

internal fun modelDescRes(model: WhisperModel): Int = when (model) {
    WhisperModel.Tiny -> R.string.settings_model_tiny_desc
    WhisperModel.Base -> R.string.settings_model_base_desc
    WhisperModel.Small -> R.string.settings_model_small_desc
}

internal fun dots(filled: Int, on: String, off: String, total: Int = 3): String =
    on.repeat(filled.coerceIn(0, total)) + off.repeat((total - filled).coerceAtLeast(0))

internal fun formatBytes(bytes: Long): String {
    if (bytes <= 0) return "0 B"
    val units = arrayOf("B", "KB", "MB", "GB")
    var v = bytes.toDouble()
    var i = 0
    while (v >= 1024 && i < units.lastIndex) { v /= 1024; i++ }
    return if (i == 0) "$bytes B" else "%.1f %s".format(v, units[i])
}
