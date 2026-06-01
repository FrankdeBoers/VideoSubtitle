package com.whispercpp.whisper

/**
 * Thin Kotlin facade over libwhisper.so. Method bindings live on the class
 * itself (not a Companion) so JNI symbols stay simple — see whisper_jni.c.
 *
 * Callers should not touch this class directly — use WhisperJniEngine in
 * com.frank.videosubtitle.data.engine which owns lifetime, threading and
 * coroutine integration.
 */
internal object WhisperLib {

    init {
        System.loadLibrary("whisper")
    }

    external fun initContext(modelPath: String): Long
    external fun freeContext(contextPtr: Long)

    external fun newState(): Long
    external fun freeState(statePtr: Long)
    external fun readProgress(statePtr: Long): Int
    external fun setAbort(statePtr: Long, abort: Boolean)

    /**
     * Run whisper_full synchronously. Returns 0 on success, -1 if cancelled
     * via [setAbort], or whisper.cpp's non-zero return code on failure.
     */
    external fun fullTranscribe(
        contextPtr: Long,
        statePtr: Long,
        numThreads: Int,
        language: String?,
        prompt: String?,
        translate: Boolean,
        audioData: FloatArray,
    ): Int

    external fun getTextSegmentCount(contextPtr: Long): Int
    external fun getTextSegment(contextPtr: Long, index: Int): String
    external fun getTextSegmentT0(contextPtr: Long, index: Int): Long
    external fun getTextSegmentT1(contextPtr: Long, index: Int): Long

    external fun getSystemInfo(): String
}
