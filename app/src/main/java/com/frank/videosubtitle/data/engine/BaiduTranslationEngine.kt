package com.frank.videosubtitle.data.engine

import com.frank.videosubtitle.data.source.local.BaiduCreds
import com.frank.videosubtitle.data.source.local.TranslationCredentialsStore
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
import org.json.JSONObject

/**
 * Baidu Translate (通用翻译 API). Free tier: ~50K chars/month, 1 QPS.
 *   POST https://fanyi-api.baidu.com/api/trans/vip/translate
 *   Form fields: q, from, to, appid, salt, sign
 *   sign = MD5(appid + q + salt + secret)  (lowercase hex)
 *   Response (success): { from, to, trans_result: [ { src, dst }, ... ] }
 *   Response (error):   { error_code, error_msg }
 */
class BaiduTranslationEngine(
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
        val creds = credentials.baidu()
            ?: throw TranslationException("Baidu API credentials not configured")

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

    private suspend fun callOnce(creds: BaiduCreds, q: String, from: String, to: String): String {
        val salt = System.currentTimeMillis().toString()
        val sign = md5Hex(creds.appId + q + salt + creds.secret)
        val body = FormBody.Builder()
            .add("q", q)
            .add("from", from)
            .add("to", to)
            .add("appid", creds.appId)
            .add("salt", salt)
            .add("sign", sign)
            .build()
        val request = Request.Builder().url(ENDPOINT).post(body).build()
        client.newCall(request).awaitResponse().use { response ->
            val raw = response.body?.string().orEmpty()
            if (!response.isSuccessful) {
                throw TranslationException("Baidu HTTP ${response.code}: ${raw.take(200)}")
            }
            val obj = runCatching { JSONObject(raw) }.getOrElse {
                throw TranslationException("Baidu: malformed response: ${raw.take(200)}")
            }
            val errCode = obj.optString("error_code", "")
            if (errCode.isNotEmpty() && errCode != "52000") {
                throw TranslationException("Baidu API error $errCode: ${obj.optString("error_msg")}")
            }
            val arr = obj.optJSONArray("trans_result")
                ?: throw TranslationException("Baidu: missing trans_result")
            if (arr.length() == 0) throw TranslationException("Baidu: empty trans_result")
            val sb = StringBuilder()
            for (i in 0 until arr.length()) {
                if (i > 0) sb.append('\n')
                sb.append(arr.getJSONObject(i).optString("dst"))
            }
            return sb.toString()
        }
    }

    private fun mapLang(tag: String): String = when (tag.substringBefore('-').lowercase()) {
        "zh" -> "zh"
        "en" -> "en"
        "ja" -> "jp"
        "ko" -> "kor"
        "fr" -> "fra"
        "es" -> "spa"
        else -> tag.substringBefore('-').lowercase()
    }

    private companion object {
        const val ENDPOINT = "https://fanyi-api.baidu.com/api/trans/vip/translate"
    }
}
