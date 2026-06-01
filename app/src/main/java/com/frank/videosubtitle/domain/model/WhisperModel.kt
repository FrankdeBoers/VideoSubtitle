package com.frank.videosubtitle.domain.model

/**
 * Quantized GGML models published by ggerganov/whisper.cpp on HuggingFace.
 * Sizes and SHA-256 hashes pulled from the HF API
 * (`/api/models/ggerganov/whisper.cpp/tree/main` -> `lfs.oid`). A mismatch on
 * download means the file was tampered with or upstream re-released — fail
 * loud rather than feed corrupt weights to whisper.cpp.
 */
enum class WhisperModel(
    val fileName: String,
    val sizeBytes: Long,
    val sha256: String,
) {
    Tiny(
        fileName = "ggml-tiny.bin",
        sizeBytes = 77_691_713L,
        sha256 = "be07e048e1e599ad46341c8d2a135645097a538221678b7acdd1b1919c6e1b21",
    ),
    Base(
        fileName = "ggml-base.bin",
        sizeBytes = 147_951_465L,
        sha256 = "60ed5bc3dd14eea856493d334349b405782ddcaf0028d4b5df4088345fba2efe",
    ),
    Small(
        fileName = "ggml-small.bin",
        sizeBytes = 487_601_967L,
        sha256 = "1be3a9b2063867b937e64e2ec7483364a79917e157fa98c5d94b5c1fffea987b",
    );

    val downloadUrl: String
        get() = "https://huggingface.co/ggerganov/whisper.cpp/resolve/main/$fileName"
}
