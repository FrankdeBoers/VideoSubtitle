package com.frank.videosubtitle.data.engine

import com.frank.videosubtitle.domain.engine.ComputeMode
import com.frank.videosubtitle.domain.engine.InfoKey
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

        // Resolve Auto here so log lines record the actual backend used.
        val wantGpu = when (config.computeMode) {
            ComputeMode.Cpu -> false
            ComputeMode.Gpu -> true
            ComputeMode.Auto -> runCatching { WhisperLib.gpuAvailable() }.getOrDefault(false)
        }

        // Mutable so we can swap to a fresh CPU context on rc==-2 (GPU
        // runtime exception). Captured by `awaitClose` so it always frees
        // the CURRENT pair, never a stale one from before the retry.
        var ctxPtr = 0L
        var statePtr = 0L
        var progressJob: Job? = null

        fun openContext(useGpu: Boolean): Boolean {
            ctxPtr = WhisperLib.initContextWithParams(config.modelFile.absolutePath, useGpu)
            if (ctxPtr == 0L) return false
            statePtr = WhisperLib.newState()
            return true
        }

        // Open the requested backend; fall back to CPU on init failure.
        var resolvedGpu = wantGpu
        if (!openContext(wantGpu)) {
            if (wantGpu) {
                Timber.w("Whisper GPU init failed for %s — falling back to CPU", config.modelFile.name)
                trySend(TranscribeEvent.Info(InfoKey.GpuFallbackToCpu))
                resolvedGpu = false
                if (!openContext(false)) {
                    close(WhisperException("whisper_init_from_file failed for ${config.modelFile.name}"))
                    return@callbackFlow
                }
            } else {
                close(WhisperException("whisper_init_from_file failed for ${config.modelFile.name}"))
                return@callbackFlow
            }
        }

        Timber.i(
            "Whisper start: model=%s lang=%s threads=%d translate=%s gpu=%s wav=%s (%d bytes)",
            config.model.fileName, config.language ?: "auto", config.nThreads,
            config.translate, resolvedGpu, wav.name, wav.length(),
        )

        // Progress poller — has to capture `statePtr` indirectly so the
        // job restarted after a CPU fallback reads the new state pointer.
        // We re-create the job each attempt rather than chase a moving
        // target inside the running job.
        fun launchProgressJob(stateForJob: Long): Job = scope.launch {
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
                val p = WhisperLib.readProgress(stateForJob).coerceIn(0, 100)
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

        progressJob = launchProgressJob(statePtr)

        val transcribeJob: Job = scope.launch {
            try {
                val samples = WavDecoder.decodeMono16kHzPcm(wav)
                Timber.d("Whisper decoded %d samples (%.2fs)", samples.size, samples.size / 16_000.0)

                var rc = WhisperLib.fullTranscribe(
                    contextPtr = ctxPtr,
                    statePtr = statePtr,
                    numThreads = config.nThreads,
                    language = config.language,
                    prompt = config.initialPrompt,
                    translate = config.translate,
                    audioData = samples,
                )

                // rc == -2 is whisper_safe.cpp's "Vulkan threw a recoverable
                // error mid-graph" code (most commonly vk::DeviceLostError
                // from a flaky GPU driver). Tear down the GPU context, swap
                // in a fresh CPU one, and rerun the same audio. We only do
                // this once and only when we were running on GPU — a CPU
                // path that returns -2 is a real bug we shouldn't paper over.
                if (rc == -2 && resolvedGpu) {
                    Timber.w("Whisper GPU runtime error (rc=-2) — retrying on CPU")
                    trySend(TranscribeEvent.Info(InfoKey.GpuFallbackToCpu))

                    // Stop the old progress poller before freeing its state.
                    progressJob?.cancel()
                    progressJob = null

                    val oldCtx = ctxPtr
                    val oldState = statePtr
                    ctxPtr = 0L
                    statePtr = 0L
                    runCatching {
                        WhisperLib.freeState(oldState)
                        WhisperLib.freeContext(oldCtx)
                    }.onFailure { Timber.w(it, "freeContext/freeState (post-GPU-fail) failed") }

                    if (!openContext(false)) {
                        close(WhisperException("CPU fallback init failed after GPU runtime error"))
                        return@launch
                    }
                    resolvedGpu = false
                    progressJob = launchProgressJob(statePtr)

                    rc = WhisperLib.fullTranscribe(
                        contextPtr = ctxPtr,
                        statePtr = statePtr,
                        numThreads = config.nThreads,
                        language = config.language,
                        prompt = config.initialPrompt,
                        translate = config.translate,
                        audioData = samples,
                    )
                }

                when (rc) {
                    0 -> {
                        progressJob?.cancel()
                        trySend(TranscribeEvent.Progress(100))
                        val subtitle = readSegments(ctxPtr, config.language)
                        Timber.i("Whisper done: %d segments", subtitle.segments.size)
                        trySend(TranscribeEvent.Done(subtitle))
                        close()
                    }
                    -1 -> close(kotlinx.coroutines.CancellationException("whisper aborted"))
                    -2 -> close(WhisperException("Whisper GPU runtime error (CPU fallback also failed)"))
                    -3 -> close(WhisperException("Whisper inference threw a fatal C++ exception"))
                    else -> close(WhisperException("whisper_full returned $rc"))
                }
            } catch (t: Throwable) {
                close(t)
            }
        }

        awaitClose {
            // Capture the latest pointers — the rc==-2 retry path may have
            // swapped them out from under us.
            val finalState = statePtr
            val finalCtx = ctxPtr
            if (finalState != 0L) WhisperLib.setAbort(finalState, true)
            transcribeJob.cancel()
            progressJob?.cancel()
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
                    if (finalState != 0L) WhisperLib.freeState(finalState)
                    if (finalCtx   != 0L) WhisperLib.freeContext(finalCtx)
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
