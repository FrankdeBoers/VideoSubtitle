package com.frank.videosubtitle.domain.model

/**
 * Quantized GGML models published by ggerganov/whisper.cpp on HuggingFace.
 * SHA-256 values are pinned to the v1 release of each file (the canonical hash
 * in the HuggingFace `x-linked-etag` header for `main`); a mismatch on
 * download means the file was tampered or the upstream re-released — fail
 * loud rather than feed corrupt weights to whisper.cpp.
 */
enum class WhisperModel(
    val fileName: String,
    val sizeBytes: Long,
    val sha256: String,
) {
    Base(
        fileName = "ggml-base.bin",
        sizeBytes = 147_951_465L,
        sha256 = "60ed5bc3dd14eea856493d334349b405782ddcaf0028d4b5df4088345fba2efe",
    );

    val downloadUrl: String
        get() = "https://huggingface.co/ggerganov/whisper.cpp/resolve/main/$fileName"
}
