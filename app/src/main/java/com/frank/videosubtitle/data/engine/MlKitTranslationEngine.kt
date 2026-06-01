package com.frank.videosubtitle.data.engine

import com.frank.videosubtitle.domain.engine.TranslateEvent
import com.frank.videosubtitle.domain.engine.TranslationEngine
import com.frank.videosubtitle.domain.engine.TranslationException
import com.frank.videosubtitle.util.DispatcherProvider
import com.google.android.gms.tasks.Task
import com.google.mlkit.common.model.DownloadConditions
import com.google.mlkit.nl.languageid.LanguageIdentification
import com.google.mlkit.nl.translate.TranslateLanguage
import com.google.mlkit.nl.translate.Translation
import com.google.mlkit.nl.translate.TranslatorOptions
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.suspendCancellableCoroutine
import timber.log.Timber
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * ML Kit-backed [TranslationEngine]. Uses Google's on-device NMT models
 * (Apache 2.0 SDK; ~30 MB per language pair, downloaded on first use and
 * cached by Play Services). Falls back to ML Kit Language Identification
 * when [sourceLanguage] is null/"auto".
 *
 * Cancellation: ML Kit's Task API is not coroutine-aware, so we bridge with
 * [suspendCancellableCoroutine]. If the coroutine is cancelled mid-batch the
 * in-flight Task is left to complete on its own (cheap), and no further
 * segments are submitted.
 */
class MlKitTranslationEngine(
    private val dispatchers: DispatcherProvider,
) : TranslationEngine {

    override fun translate(
        sources: List<String>,
        sourceLanguage: String?,
        targetLanguage: String,
    ): Flow<TranslateEvent> = flow {
        if (sources.isEmpty()) {
            emit(TranslateEvent.Done(emptyList()))
            return@flow
        }
        val target = resolveTranslateLanguage(targetLanguage)
            ?: throw TranslationException("Unsupported target language: $targetLanguage")

        val resolvedSource = sourceLanguage?.takeIf { it.isNotBlank() && it != "auto" }
            ?: identifyLanguage(sources)
        val source = resolveTranslateLanguage(resolvedSource)
            ?: throw TranslationException("Unsupported source language: $resolvedSource")

        if (source == target) {
            // Already in target language — pass through unchanged.
            emit(TranslateEvent.Progress(100))
            emit(TranslateEvent.Done(sources))
            return@flow
        }

        val options = TranslatorOptions.Builder()
            .setSourceLanguage(source)
            .setTargetLanguage(target)
            .build()
        val translator = Translation.getClient(options)
        try {
            translator.downloadModelIfNeeded(DownloadConditions.Builder().build()).await()
            Timber.i("Translate model ready: %s -> %s (%d segments)", source, target, sources.size)

            val results = ArrayList<String>(sources.size)
            sources.forEachIndexed { i, src ->
                val txt = if (src.isBlank()) "" else translator.translate(src).await()
                results += txt
                val pct = ((i + 1) * 100 / sources.size).coerceIn(0, 100)
                emit(TranslateEvent.Progress(pct))
            }
            emit(TranslateEvent.Done(results))
        } finally {
            translator.close()
        }
    }.flowOn(dispatchers.io)

    private suspend fun identifyLanguage(sources: List<String>): String {
        val sample = sources.asSequence()
            .filter { it.isNotBlank() }
            .take(SAMPLE_SEGMENTS)
            .joinToString(separator = "\n")
            .take(SAMPLE_CHAR_LIMIT)
        if (sample.isBlank()) return DEFAULT_FALLBACK_LANG
        val client = LanguageIdentification.getClient()
        return try {
            val tag = client.identifyLanguage(sample).await()
            // ML Kit returns "und" when confidence is too low — fall back.
            if (tag == "und") DEFAULT_FALLBACK_LANG else tag
        } catch (t: Throwable) {
            Timber.w(t, "language id failed, falling back to %s", DEFAULT_FALLBACK_LANG)
            DEFAULT_FALLBACK_LANG
        } finally {
            client.close()
        }
    }

    /**
     * Map a BCP-47-ish tag (e.g. "zh", "zh-CN", "en", "ja") to ML Kit's
     * [TranslateLanguage] string. Returns null when the tag isn't supported.
     * ML Kit only ships one Chinese model — both "zh" and "zh-CN" land on
     * [TranslateLanguage.CHINESE].
     */
    private fun resolveTranslateLanguage(tag: String): String? {
        val primary = tag.substringBefore('-').lowercase()
        return TranslateLanguage.fromLanguageTag(primary)
    }

    private suspend fun <T> Task<T>.await(): T = suspendCancellableCoroutine { cont ->
        addOnSuccessListener { cont.resume(it) }
        addOnFailureListener { cont.resumeWithException(it) }
        addOnCanceledListener { cont.cancel() }
    }

    private companion object {
        const val SAMPLE_SEGMENTS = 5
        const val SAMPLE_CHAR_LIMIT = 1024
        const val DEFAULT_FALLBACK_LANG = "en"
    }
}
