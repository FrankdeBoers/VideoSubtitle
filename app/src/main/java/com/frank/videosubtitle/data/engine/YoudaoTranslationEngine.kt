package com.frank.videosubtitle.data.engine

import com.frank.videosubtitle.data.source.local.TranslationCredentialsStore
import com.frank.videosubtitle.data.source.local.YoudaoCreds
import com.frank.videosubtitle.domain.engine.TranslateEvent
import com.frank.videosubtitle.domain.engine.TranslationEngine
import com.frank.videosubtitle.domain.engine.TranslationException
import com.frank.videosubtitle.util.DispatcherProvider
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import okhttp3.FormBody
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.security.MessageDigest
import java.util.UUID

/**
 * Youdao Translation (有道智云). Free tier: 100 yuan credit on signup.
 *   POST https://openapi.youdao.com/api
 *   Fields: q, from, to, appKey, salt, curtime, sign, signType=v3
 *   input = if (q.length <= 20) q else q.take(10) + q.length + q.takeLast(10)
 *   sign  = SHA256(appKey + input + salt + curtime + appSecret)  (lowercase hex)
 *   Response: { errorCode, translation: [ "..." ] } — errorCode "0" = OK.
 */
class YoudaoTranslationEngine(
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
        val creds = credentials.youdao()
            ?: throw TranslationException("Youdao API credentials not configured")

        val from = sourceLanguage?.takeIf { it.isNotBlank() && it != "auto" }?.let { mapLang(it) } ?: "auto"
        val to = mapLang(targetLanguage)

        val results = ArrayList<String>(sources.size)
        sources.forEachIndexed { i, src ->
            val out = if (src.isBlank()) "" else callOnce(creds, src, from, to)
            results += out
            val pct = ((i + 1) * 100 / sources.size).coerceIn(0, 100)
            emit(TranslateEvent.Progress(pct))
        }
        emit(TranslateEvent.Done(results))
    }.flowOn(dispatchers.io)

    private suspend fun callOnce(creds: YoudaoCreds, q: String, from: String, to: String): String {
        val salt = UUID.randomUUID().toString()
        val curtime = (System.currentTimeMillis() / 1000L).toString()
        val signInput = creds.appKey + truncate(q) + salt + curtime + creds.appSecret
        val sign = sha256Hex(signInput)

        val body = FormBody.Builder()
            .add("q", q)
            .add("from", from)
            .add("to", to)
            .add("appKey", creds.appKey)
            .add("salt", salt)
            .add("curtime", curtime)
            .add("sign", sign)
            .add("signType", "v3")
            .build()
        val request = Request.Builder().url(ENDPOINT).post(body).build()
        client.newCall(request).awaitResponse().use { response ->
            val raw = response.body?.string().orEmpty()
            if (!response.isSuccessful) {
                throw TranslationException("Youdao HTTP ${response.code}: ${raw.take(200)}")
            }
            val obj = runCatching { JSONObject(raw) }.getOrElse {
                throw TranslationException("Youdao: malformed response: ${raw.take(200)}")
            }
            val errorCode = obj.optString("errorCode")
            if (errorCode != "0") {
                throw TranslationException("Youdao API error $errorCode: ${obj.optString("msg", "(no msg)")}")
            }
            val translations: JSONArray = obj.optJSONArray("translation")
                ?: throw TranslationException("Youdao: missing translation array")
            if (translations.length() == 0) throw TranslationException("Youdao: empty translation array")
            val sb = StringBuilder()
            for (i in 0 until translations.length()) {
                if (i > 0) sb.append('\n')
                sb.append(translations.getString(i))
            }
            return sb.toString()
        }
    }

    private fun truncate(q: String): String {
        return if (q.length <= 20) q else q.take(10) + q.length + q.takeLast(10)
    }

    private fun mapLang(tag: String): String = when (tag.substringBefore('-').lowercase()) {
        "zh" -> "zh-CHS"
        "en" -> "en"
        "ja" -> "ja"
        "ko" -> "ko"
        "fr" -> "fr"
        "es" -> "es"
        else -> tag.substringBefore('-').lowercase()
    }

    private fun sha256Hex(input: String): String {
        val md = MessageDigest.getInstance("SHA-256").digest(input.toByteArray(Charsets.UTF_8))
        val sb = StringBuilder(md.size * 2)
        for (b in md) {
            sb.append(HEX[(b.toInt() ushr 4) and 0x0f])
            sb.append(HEX[b.toInt() and 0x0f])
        }
        return sb.toString()
    }

    private companion object {
        const val ENDPOINT = "https://openapi.youdao.com/api"
        val HEX = "0123456789abcdef".toCharArray()
    }
}
