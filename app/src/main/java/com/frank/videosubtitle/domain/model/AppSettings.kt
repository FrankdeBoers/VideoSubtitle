package com.frank.videosubtitle.domain.model

import com.frank.videosubtitle.domain.engine.BurnMode
import com.frank.videosubtitle.domain.engine.ComputeMode
import com.frank.videosubtitle.domain.engine.SubtitleAlignment
import com.frank.videosubtitle.domain.engine.SubtitleDisplay

/**
 * Persisted user preferences. Lives behind [SettingsRepository]; readers should
 * sample at the start of a pipeline run rather than holding a long-lived
 * reference. All fields have defaults so a fresh install has working behaviour
 * without a first-run tour.
 */
data class AppSettings(
    val model: WhisperModel = WhisperModel.Tiny,
    val language: LanguagePref = LanguagePref.Auto,
    val burnMode: BurnMode = BurnMode.HARD,
    val preset: VideoPreset = VideoPreset.Medium,
    val fontSize: Int = DEFAULT_FONT_SIZE,
    val fontColor: SubtitleColor = SubtitleColor.White,
    val outline: Boolean = true,
    val fontSizeTranslated: Int = DEFAULT_FONT_SIZE,
    val fontColorTranslated: SubtitleColor = SubtitleColor.Yellow,
    val outlineTranslated: Boolean = true,
    val alignment: SubtitleAlignment = SubtitleAlignment.BottomCenter,
    val marginV: Int = DEFAULT_MARGIN_V,
    val marginH: Int = 0,
    val background: Boolean = false,
    val backgroundOpacity: Int = DEFAULT_BG_OPACITY,
    val subtitleDisplay: SubtitleDisplay = SubtitleDisplay.Both,
    val translateToChinese: Boolean = true,
    val translationProvider: TranslationProvider = TranslationProvider.MlKit,
    val mediaBackend: MediaBackend = MediaBackend.Ffmpeg,
    /**
     * Whisper transcription thread count. [THREAD_COUNT_AUTO] means "use every
     * online CPU core at task kick-off"; a positive value is clamped at the
     * orchestrator to `[1, availableProcessors()]`.
     */
    val threadCount: Int = THREAD_COUNT_AUTO,
    /**
     * Whether Whisper inference runs on the CPU, GPU, or picks at task start.
     * Auto resolves to GPU when [com.whispercpp.whisper.WhisperLib.gpuAvailable]
     * reports a usable backend, otherwise CPU. See `docs/GPU_SUPPORT_PLAN.md`.
     */
    val computeMode: ComputeMode = ComputeMode.Auto,
) {
    companion object {
        const val MIN_FONT_SIZE = 16
        const val MAX_FONT_SIZE = 60
        const val DEFAULT_FONT_SIZE = 24
        const val MIN_MARGIN_V = 0
        const val MAX_MARGIN_V = 200
        const val DEFAULT_MARGIN_V = 24
        const val MIN_MARGIN_H = -200
        const val MAX_MARGIN_H = 200
        const val MIN_BG_OPACITY = 0
        const val MAX_BG_OPACITY = 100
        const val DEFAULT_BG_OPACITY = 50
        const val THREAD_COUNT_AUTO = 0
    }
}

/**
 * Subset of whisper.cpp language codes exposed in the UI. `Auto` lets whisper
 * detect language from the audio. Each preset carries an optional initial
 * prompt — empirically this nudges the decoder toward the expected script
 * (full-width Chinese punctuation for Zh, conventional capitalization for En).
 */
enum class LanguagePref(val whisperCode: String?, val initialPrompt: String?) {
    Auto(whisperCode = null, initialPrompt = null),
    ZhCn(whisperCode = "zh", initialPrompt = "以下是普通话的句子，使用全角标点。"),
    En(whisperCode = "en", initialPrompt = null),
    Ja(whisperCode = "ja", initialPrompt = null),
    Ko(whisperCode = "ko", initialPrompt = null),
}

/**
 * x264 preset trading encoding speed for compression efficiency.
 */
enum class VideoPreset(val ffmpegPreset: String) {
    Ultrafast("ultrafast"),
    Fast("fast"),
    Medium("medium"),
    Slow("slow"),
}

/**
 * Restricted palette for burned subtitles — keeping it small avoids a full
 * color picker in v1. ARGB values are passed through to [BurnOptions].
 */
enum class SubtitleColor(val argb: Int) {
    White(0xFFFFFFFF.toInt()),
    Yellow(0xFFFFFF00.toInt()),
    LimeGreen(0xFF00FF00.toInt()),
}
