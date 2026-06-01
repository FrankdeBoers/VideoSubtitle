package com.frank.videosubtitle.data.engine

import com.frank.videosubtitle.data.repository.SettingsRepository
import com.frank.videosubtitle.data.source.local.TranslationCredentialsStore
import com.frank.videosubtitle.domain.engine.TranslateEvent
import com.frank.videosubtitle.domain.engine.TranslationEngine
import com.frank.videosubtitle.domain.engine.TranslationException
import com.frank.videosubtitle.domain.model.TranslationProvider
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow

/**
 * Reads the active [TranslationProvider] from [SettingsRepository] on each
 * call (no caching — the user can switch providers mid-session) and dispatches
 * to the matching engine. For online providers, fails fast with a friendly
 * message when credentials are missing rather than letting the underlying
 * engine raise a less obvious error.
 */
class RouterTranslationEngine(
    private val settings: SettingsRepository,
    private val credentials: TranslationCredentialsStore,
    private val mlkit: MlKitTranslationEngine,
    private val baidu: BaiduTranslationEngine,
    private val youdao: YoudaoTranslationEngine,
    private val tencent: TencentTranslationEngine,
    private val microsoft: MicrosoftTranslationEngine,
) : TranslationEngine {

    override fun translate(
        sources: List<String>,
        sourceLanguage: String?,
        targetLanguage: String,
    ): Flow<TranslateEvent> = flow {
        val provider = settings.current().translationProvider
        if (provider != TranslationProvider.MlKit && !credentials.has(provider)) {
            throw TranslationException("API key not configured for ${provider.name}")
        }
        val engine: TranslationEngine = when (provider) {
            TranslationProvider.MlKit -> mlkit
            TranslationProvider.Baidu -> baidu
            TranslationProvider.Youdao -> youdao
            TranslationProvider.Tencent -> tencent
            TranslationProvider.Microsoft -> microsoft
        }
        emitAll(engine.translate(sources, sourceLanguage, targetLanguage))
    }
}
