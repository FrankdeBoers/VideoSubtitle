package com.frank.videosubtitle.data.engine

import com.frank.videosubtitle.data.source.local.TencentCreds
import com.frank.videosubtitle.data.source.local.TranslationCredentialsStore
import com.frank.videosubtitle.domain.engine.TranslateEvent
import com.frank.videosubtitle.domain.engine.TranslationEngine
import com.frank.videosubtitle.domain.engine.TranslationException
import com.frank.videosubtitle.util.DispatcherProvider
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * Tencent Cloud Machine Translation (TMT). Free tier: 5 M chars/month.
 *   POST https://tmt.tencentcloudapi.com/   action=TextTranslate, version=2018-03-21
 *   Request body: { SourceText, Source, Target, ProjectId }
 *   Auth: TC3-HMAC-SHA256 (canonical req → string-to-sign → 4-step key derivation).
 *   Response: { Response: { TargetText, Source, Target, RequestId } }
 *           or error { Response: { Error: { Code, Message }, RequestId } }
 *
 * Reference: https://cloud.tencent.com/document/api/551/15619 (signature v3 spec).
 */
class TencentTranslationEngine(
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
        val creds = credentials.tencent()
            ?: throw TranslationException("Tencent API credentials not configured")

        val source = sourceLanguage?.takeIf { it.isNotBlank() && it != "auto" }?.let { mapLang(it) } ?: "auto"
        val target = mapLang(targetLanguage)

        val results = ArrayList<String>(sources.size)
        sources.forEachIndexed { i, src ->
            val out = if (src.isBlank()) "" else callOnce(creds, src, source, target)
            results += out
            val pct = ((i + 1) * 100 / sources.size).coerceIn(0, 100)
            emit(TranslateEvent.Progress(pct))
        }
        emit(TranslateEvent.Done(results))
    }.flowOn(dispatchers.io)

    private suspend fun callOnce(creds: TencentCreds, text: String, source: String, target: String): String {
        val payload = JSONObject().apply {
            put("SourceText", text)
            put("Source", source)
            put("Target", target)
            put("ProjectId", 0)
        }.toString()

        val timestamp = System.currentTimeMillis() / 1000L
        val date = utcDate(timestamp)
        val authorization = buildAuthorizationHeader(
            secretId = creds.secretId,
            secretKey = creds.secretKey,
            payload = payload,
            timestamp = timestamp,
            date = date,
            region = creds.region,
        )

        val request = Request.Builder()
            .url(ENDPOINT)
            .post(payload.toRequestBody(JSON_TYPE))
            .header("Authorization", authorization)
            .header("Content-Type", "application/json; charset=utf-8")
            .header("Host", HOST)
            .header("X-TC-Action", ACTION)
            .header("X-TC-Timestamp", timestamp.toString())
            .header("X-TC-Version", VERSION)
            .header("X-TC-Region", creds.region)
            .build()

        client.newCall(request).awaitResponse().use { response ->
            val raw = response.body?.string().orEmpty()
            if (!response.isSuccessful) {
                throw TranslationException("Tencent HTTP ${response.code}: ${raw.take(200)}")
            }
            val outer = runCatching { JSONObject(raw) }.getOrElse {
                throw TranslationException("Tencent: malformed response: ${raw.take(200)}")
            }
            val resp = outer.optJSONObject("Response")
                ?: throw TranslationException("Tencent: missing Response: ${raw.take(200)}")
            resp.optJSONObject("Error")?.let { err ->
                throw TranslationException(
                    "Tencent API error ${err.optString("Code")}: ${err.optString("Message")}",
                )
            }
            return resp.optString("TargetText").also {
                if (it.isEmpty()) {
                    throw TranslationException("Tencent: empty TargetText: ${raw.take(200)}")
                }
            }
        }
    }

    private fun buildAuthorizationHeader(
        secretId: String,
        secretKey: String,
        payload: String,
        timestamp: Long,
        date: String,
        region: String,
    ): String {
        // Step 1: canonical request
        val canonicalUri = "/"
        val canonicalQueryString = ""
        val canonicalHeaders =
            "content-type:application/json; charset=utf-8\nhost:$HOST\nx-tc-action:${ACTION.lowercase()}\n"
        val signedHeaders = "content-type;host;x-tc-action"
        val hashedRequestPayload = sha256Hex(payload)
        val canonicalRequest = listOf(
            "POST",
            canonicalUri,
            canonicalQueryString,
            canonicalHeaders,
            signedHeaders,
            hashedRequestPayload,
        ).joinToString("\n")

        // Step 2: string to sign
        val credentialScope = "$date/$SERVICE/tc3_request"
        val hashedCanonicalRequest = sha256Hex(canonicalRequest)
        val stringToSign = listOf(
            "TC3-HMAC-SHA256",
            timestamp.toString(),
            credentialScope,
            hashedCanonicalRequest,
        ).joinToString("\n")

        // Step 3: derive signing key (4-step HMAC chain)
        val secretDate = hmacSha256(("TC3$secretKey").toByteArray(Charsets.UTF_8), date)
        val secretService = hmacSha256(secretDate, SERVICE)
        val secretSigning = hmacSha256(secretService, "tc3_request")
        val signature = hmacSha256(secretSigning, stringToSign).toHexLower()

        // Step 4: assemble the Authorization header
        return "TC3-HMAC-SHA256 Credential=$secretId/$credentialScope, " +
            "SignedHeaders=$signedHeaders, Signature=$signature"
    }

    private fun mapLang(tag: String): String = when (tag.substringBefore('-').lowercase()) {
        "zh" -> "zh"
        "en" -> "en"
        "ja" -> "ja"
        "ko" -> "ko"
        "fr" -> "fr"
        "es" -> "es"
        else -> tag.substringBefore('-').lowercase()
    }

    private fun utcDate(epochSeconds: Long): String {
        val fmt = SimpleDateFormat("yyyy-MM-dd", Locale.US).apply {
            timeZone = TimeZone.getTimeZone("UTC")
        }
        return fmt.format(Date(epochSeconds * 1000L))
    }

    private fun hmacSha256(key: ByteArray, data: String): ByteArray {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(key, "HmacSHA256"))
        return mac.doFinal(data.toByteArray(Charsets.UTF_8))
    }

    private companion object {
        const val ENDPOINT = "https://tmt.tencentcloudapi.com/"
        const val HOST = "tmt.tencentcloudapi.com"
        const val SERVICE = "tmt"
        const val ACTION = "TextTranslate"
        const val VERSION = "2018-03-21"
        val JSON_TYPE = "application/json; charset=utf-8".toMediaType()
    }
}
