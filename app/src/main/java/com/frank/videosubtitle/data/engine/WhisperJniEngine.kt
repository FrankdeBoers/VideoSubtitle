package com.frank.videosubtitle.data.engine

import com.frank.videosubtitle.domain.engine.TranscribeEvent
import com.frank.videosubtitle.domain.engine.WhisperConfig
import com.frank.videosubtitle.domain.engine.WhisperEngine
import com.frank.videosubtitle.domain.engine.WhisperException
import com.frank.videosubtitle.domain.model.Subtitle
import com.frank.videosubtitle.domain.model.SubtitleSegment
import com.frank.videosubtitle.util.DispatcherProvider
import com.whispercpp.whisper.WhisperLib
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import timber.log.Timber
import java.io.File

/**
 * WhisperEngine on top of libwhisper.so (vendored whisper.cpp v1.7.5).
 *
 * Threading: whisper_full is blocking and CPU-heavy — runs on the IO
 * dispatcher. A second coroutine polls native progress every 250ms and
 * emits [TranscribeEvent.Progress]; on cancellation we flip the native
 * abort flag, which whisper_full picks up at its next abort_callback.
 */
class WhisperJniEngine(
    private val dispatchers: DispatcherProvider,
) : WhisperEngine {

    override fun transcribe(wav: File, config: WhisperConfig): Flow<TranscribeEvent> = callbackFlow {
        if (!config.modelFile.exists()) {
            close(WhisperException("Model file missing: ${config.modelFile.absolutePath}"))
            return@callbackFlow
        }

        val scope = CoroutineScope(SupervisorJob() + dispatchers.io)
        val ctxPtr = WhisperLib.initContext(config.modelFile.absolutePath)
        if (ctxPtr == 0L) {
            close(WhisperException("whisper_init_from_file failed for ${config.modelFile.name}"))
            return@callbackFlow
        }
        val statePtr = WhisperLib.newState()

        Timber.i(
            "Whisper start: model=%s lang=%s threads=%d translate=%s wav=%s (%d bytes)",
            config.model.fileName, config.language ?: "auto", config.nThreads,
            config.translate, wav.name, wav.length(),
        )

        val progressJob: Job = scope.launch {
            // whisper.cpp emits native progress in coarse jumps (e.g. 36% then
            // 69%) once each internal chunk completes, so the bar appears
            // frozen for tens of seconds. We smooth it: between native bumps,
            // tick the displayed value up by 1% every 5s, capped at
            // lastNative + 10 (and 99) so we never overshoot the next real jump
            // by much and never claim "done".
            var lastNative = -1
            var displayed = 0
            var ticksSinceBump = 0
            val ticksPerNudge = 20 // 20 * 250ms = 5s
            val nudgeHeadroom = 10
            while (isActive) {
                val p = WhisperLib.readProgress(statePtr).coerceIn(0, 100)
                if (p > lastNative) {
                    lastNative = p
                    ticksSinceBump = 0
                    if (p > displayed) {
                        displayed = p
                        trySend(TranscribeEvent.Progress(displayed))
                    }
                } else {
                    ticksSinceBump++
                    val cap = (lastNative + nudgeHeadroom).coerceAtMost(99)
                    if (ticksSinceBump >= ticksPerNudge && displayed < cap) {
                        displayed++
                        ticksSinceBump = 0
                        trySend(TranscribeEvent.Progress(displayed))
                    }
                }
                delay(250)
            }
        }

        val transcribeJob: Job = scope.launch {
            try {
                val samples = WavDecoder.decodeMono16kHzPcm(wav)
                Timber.d("Whisper decoded %d samples (%.2fs)", samples.size, samples.size / 16_000.0)

                val rc = WhisperLib.fullTranscribe(
                    contextPtr = ctxPtr,
                    statePtr = statePtr,
                    numThreads = config.nThreads,
                    language = config.language,
                    prompt = config.initialPrompt,
                    translate = config.translate,
                    audioData = samples,
                )

                when (rc) {
                    0 -> {
                        progressJob.cancel()
                        trySend(TranscribeEvent.Progress(100))
                        val subtitle = readSegments(ctxPtr, config.language)
                        Timber.i("Whisper done: %d segments", subtitle.segments.size)
                        trySend(TranscribeEvent.Done(subtitle))
                        close()
                    }
                    -1 -> close(kotlinx.coroutines.CancellationException("whisper aborted"))
                    else -> close(WhisperException("whisper_full returned $rc"))
                }
            } catch (t: Throwable) {
                close(t)
            }
        }

        awaitClose {
            WhisperLib.setAbort(statePtr, true)
            transcribeJob.cancel()
            progressJob.cancel()
            scope.cancel()
            // freeContext/freeState are safe even after abort once whisper_full
            // returns. Run on a fresh thread so we don't block the consumer's
            // cancellation path on a pending whisper_full call (it'll exit at
            // the next abort_callback poll, typically <1s).
            Thread {
                runCatching {
                    // Best-effort: wait briefly for transcribeJob to unwind so
                    // we don't free while whisper_full still references ctx.
                    Thread.sleep(50)
                    WhisperLib.freeState(statePtr)
                    WhisperLib.freeContext(ctxPtr)
                }.onFailure { Timber.w(it, "freeContext/freeState failed") }
            }.start()
        }
    }.flowOn(dispatchers.io)

    private fun readSegments(ctxPtr: Long, language: String?): Subtitle {
        val n = WhisperLib.getTextSegmentCount(ctxPtr)
        val out = ArrayList<SubtitleSegment>(n)
        for (i in 0 until n) {
            // whisper.cpp ticks are 10ms — multiply by 10 for ms.
            val t0 = WhisperLib.getTextSegmentT0(ctxPtr, i) * 10
            val t1 = WhisperLib.getTextSegmentT1(ctxPtr, i) * 10
            val text = WhisperLib.getTextSegment(ctxPtr, i).trim()
            if (text.isEmpty()) continue
            out += SubtitleSegment(index = out.size + 1, startMs = t0, endMs = t1, text = text)
        }
        return Subtitle(segments = out, language = language)
    }
}
