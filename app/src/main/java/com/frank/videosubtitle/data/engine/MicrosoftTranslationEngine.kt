package com.frank.videosubtitle.data.engine

import com.frank.videosubtitle.data.source.local.TranslationCredentialsStore
import com.frank.videosubtitle.domain.engine.TranslateEvent
import com.frank.videosubtitle.domain.engine.TranslationEngine
import com.frank.videosubtitle.domain.engine.TranslationException
import com.frank.videosubtitle.util.DispatcherProvider
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject

/**
 * Azure AI Translator. Free tier: 2 M characters/month.
 *   POST https://api.cognitive.microsofttranslator.com/translate?api-version=3.0&to=<target>[&from=<src>]
 *   Headers: Ocp-Apim-Subscription-Key, Ocp-Apim-Subscription-Region, Content-Type
 *   Body: JSON array of { "Text": "..." }
 *   Response: array aligned with body, each entry has { translations: [{ text, to }] }
 *
 * Sends one segment per request to mirror ML Kit's progress granularity.
 */
class MicrosoftTranslationEngine(
    private val dispatchers: DispatcherProvider,
    private val client: OkHttpClient,
    private val credentials: TranslationCredentialsStore,
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
        val creds = credentials.microsoft()
            ?: throw TranslationException("Microsoft Translator API key not configured")

        val target = mapTarget(targetLanguage)
        val from = sourceLanguage?.takeIf { it.isNotBlank() && it != "auto" }?.let { mapSource(it) }

        val results = ArrayList<String>(sources.size)
        sources.forEachIndexed { i, src ->
            val translated = if (src.isBlank()) "" else callOnce(creds, src, from, target)
            results += translated
            val pct = ((i + 1) * 100 / sources.size).coerceIn(0, 100)
            emit(TranslateEvent.Progress(pct))
        }
        emit(TranslateEvent.Done(results))
    }.flowOn(dispatchers.io)

    private suspend fun callOnce(
        creds: com.frank.videosubtitle.data.source.local.MicrosoftCreds,
        text: String,
        from: String?,
        to: String,
    ): String {
        val urlBuilder = ENDPOINT.toHttpUrl().newBuilder()
            .setQueryParameter("api-version", "3.0")
            .setQueryParameter("to", to)
        if (from != null) urlBuilder.setQueryParameter("from", from)

        val body = JSONArray().put(JSONObject().put("Text", text))
            .toString()
            .toRequestBody(JSON_TYPE)

        val request = Request.Builder()
            .url(urlBuilder.build())
            .post(body)
            .header("Ocp-Apim-Subscription-Key", creds.key)
            .header("Ocp-Apim-Subscription-Region", creds.region)
            .header("Content-Type", "application/json")
            .build()

        client.newCall(request).awaitResponse().use { response ->
            val raw = response.body?.string().orEmpty()
            if (!response.isSuccessful) {
                throw TranslationException("Microsoft HTTP ${response.code}: ${parseError(raw, response.code)}")
            }
            return parseTranslation(raw)
        }
    }

    private fun parseTranslation(raw: String): String {
        val arr = runCatching { JSONArray(raw) }.getOrElse {
            throw TranslationException("Microsoft: malformed response: ${raw.take(200)}")
        }
        if (arr.length() == 0) throw TranslationException("Microsoft: empty response")
        val translations = arr.getJSONObject(0).optJSONArray("translations")
            ?: throw TranslationException("Microsoft: missing translations array")
        if (translations.length() == 0) throw TranslationException("Microsoft: empty translations array")
        return translations.getJSONObject(0).optString("text")
    }

    private fun parseError(raw: String, code: Int): String {
        val obj = runCatching { JSONObject(raw) }.getOrNull() ?: return "HTTP $code"
        val err = obj.optJSONObject("error") ?: return raw.take(200)
        return "${err.optInt("code", code)}: ${err.optString("message")}"
    }

    private fun mapTarget(tag: String): String = when (tag.substringBefore('-').lowercase()) {
        "zh" -> "zh-Hans"
        else -> tag
    }

    private fun mapSource(tag: String): String = when (tag.substringBefore('-').lowercase()) {
        "zh" -> "zh-Hans"
        else -> tag.substringBefore('-')
    }

    private companion object {
        const val ENDPOINT = "https://api.cognitive.microsofttranslator.com/translate"
        val JSON_TYPE = "application/json; charset=utf-8".toMediaType()
    }
}
