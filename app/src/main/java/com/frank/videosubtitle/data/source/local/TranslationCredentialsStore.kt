package com.frank.videosubtitle.data.source.local

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import com.frank.videosubtitle.domain.model.TranslationProvider

data class BaiduCreds(val appId: String, val secret: String)
data class YoudaoCreds(val appKey: String, val appSecret: String)
data class TencentCreds(val secretId: String, val secretKey: String, val region: String)
data class MicrosoftCreds(val key: String, val region: String)

/**
 * Holds API keys for online translation providers in EncryptedSharedPreferences.
 * Lives outside [AppSettings] / [SettingsDataStore] so credentials never flow
 * through observable state and don't leak into logs or UI snapshots.
 *
 * Returns `null` (not blank-string-stuffed data classes) when a provider has
 * no credentials saved — callers use that for the "configured?" check.
 */
class TranslationCredentialsStore(context: Context) {

    private val prefs: SharedPreferences by lazy {
        val masterKey = MasterKey.Builder(context.applicationContext)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()
        EncryptedSharedPreferences.create(
            context.applicationContext,
            FILE_NAME,
            masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
        )
    }

    fun baidu(): BaiduCreds? {
        val appId = prefs.getString(K_BAIDU_APP_ID, null).orEmpty()
        val secret = prefs.getString(K_BAIDU_SECRET, null).orEmpty()
        return if (appId.isBlank() || secret.isBlank()) null else BaiduCreds(appId, secret)
    }

    fun setBaidu(creds: BaiduCreds) {
        prefs.edit()
            .putString(K_BAIDU_APP_ID, creds.appId.trim())
            .putString(K_BAIDU_SECRET, creds.secret.trim())
            .apply()
    }

    fun youdao(): YoudaoCreds? {
        val appKey = prefs.getString(K_YOUDAO_APP_KEY, null).orEmpty()
        val appSecret = prefs.getString(K_YOUDAO_APP_SECRET, null).orEmpty()
        return if (appKey.isBlank() || appSecret.isBlank()) null else YoudaoCreds(appKey, appSecret)
    }

    fun setYoudao(creds: YoudaoCreds) {
        prefs.edit()
            .putString(K_YOUDAO_APP_KEY, creds.appKey.trim())
            .putString(K_YOUDAO_APP_SECRET, creds.appSecret.trim())
            .apply()
    }

    fun tencent(): TencentCreds? {
        val secretId = prefs.getString(K_TENCENT_SECRET_ID, null).orEmpty()
        val secretKey = prefs.getString(K_TENCENT_SECRET_KEY, null).orEmpty()
        val region = prefs.getString(K_TENCENT_REGION, null).orEmpty().ifBlank { TENCENT_DEFAULT_REGION }
        return if (secretId.isBlank() || secretKey.isBlank()) null
        else TencentCreds(secretId, secretKey, region)
    }

    fun setTencent(creds: TencentCreds) {
        prefs.edit()
            .putString(K_TENCENT_SECRET_ID, creds.secretId.trim())
            .putString(K_TENCENT_SECRET_KEY, creds.secretKey.trim())
            .putString(K_TENCENT_REGION, creds.region.trim().ifBlank { TENCENT_DEFAULT_REGION })
            .apply()
    }

    fun microsoft(): MicrosoftCreds? {
        val key = prefs.getString(K_MS_KEY, null).orEmpty()
        val region = prefs.getString(K_MS_REGION, null).orEmpty().ifBlank { MS_DEFAULT_REGION }
        return if (key.isBlank()) null else MicrosoftCreds(key, region)
    }

    fun setMicrosoft(creds: MicrosoftCreds) {
        prefs.edit()
            .putString(K_MS_KEY, creds.key.trim())
            .putString(K_MS_REGION, creds.region.trim().ifBlank { MS_DEFAULT_REGION })
            .apply()
    }

    fun has(provider: TranslationProvider): Boolean = when (provider) {
        TranslationProvider.MlKit -> true
        TranslationProvider.Baidu -> baidu() != null
        TranslationProvider.Youdao -> youdao() != null
        TranslationProvider.Tencent -> tencent() != null
        TranslationProvider.Microsoft -> microsoft() != null
    }

    fun snapshot(): CredentialsSnapshot = CredentialsSnapshot(
        baidu = baidu(),
        youdao = youdao(),
        tencent = tencent(),
        microsoft = microsoft(),
    )

    companion object {
        const val TENCENT_DEFAULT_REGION = "ap-guangzhou"
        const val MS_DEFAULT_REGION = "global"

        private const val FILE_NAME = "translation_credentials"

        private const val K_BAIDU_APP_ID = "baidu_app_id"
        private const val K_BAIDU_SECRET = "baidu_secret"
        private const val K_YOUDAO_APP_KEY = "youdao_app_key"
        private const val K_YOUDAO_APP_SECRET = "youdao_app_secret"
        private const val K_TENCENT_SECRET_ID = "tencent_secret_id"
        private const val K_TENCENT_SECRET_KEY = "tencent_secret_key"
        private const val K_TENCENT_REGION = "tencent_region"
        private const val K_MS_KEY = "ms_key"
        private const val K_MS_REGION = "ms_region"
    }
}

data class CredentialsSnapshot(
    val baidu: BaiduCreds?,
    val youdao: YoudaoCreds?,
    val tencent: TencentCreds?,
    val microsoft: MicrosoftCreds?,
)
