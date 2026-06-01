package com.frank.videosubtitle.domain.model

/**
 * Backend used to translate the transcribed SRT into Chinese.
 * MlKit is the offline default; the others are online APIs whose
 * credentials live in [com.frank.videosubtitle.data.source.local.TranslationCredentialsStore].
 */
enum class TranslationProvider {
    MlKit,
    Baidu,
    Youdao,
    Tencent,
    Microsoft,
}
