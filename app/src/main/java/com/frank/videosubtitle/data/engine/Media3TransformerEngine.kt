package com.frank.videosubtitle.data.engine

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.Typeface
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import androidx.media3.common.MediaItem
import androidx.media3.common.util.UnstableApi
import androidx.media3.effect.BitmapOverlay
import androidx.media3.effect.OverlayEffect
import androidx.media3.effect.OverlaySettings
import androidx.media3.transformer.Composition
import androidx.media3.transformer.EditedMediaItem
import androidx.media3.transformer.Effects
import androidx.media3.transformer.ExportException
import androidx.media3.transformer.ExportResult
import androidx.media3.transformer.ProgressHolder
import androidx.media3.transformer.Transformer
import com.google.common.collect.ImmutableList
import com.frank.videosubtitle.domain.engine.BurnOptions
import com.frank.videosubtitle.domain.engine.FFmpegEngine
import com.frank.videosubtitle.domain.engine.FfmpegException
import com.frank.videosubtitle.domain.engine.FfmpegProgress
import com.frank.videosubtitle.domain.engine.SubtitleAlignment
import com.frank.videosubtitle.util.DispatcherProvider
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import timber.log.Timber
import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Hardware-accelerated alternative to [FFmpegKitEngine].
 *
 *  - **extractAudio** uses [MediaExtractor] + [MediaCodec] to decode the audio
 *    track to PCM, downmix to mono, naive-resample to 16 kHz, and prepend a
 *    44-byte WAV header. No video decode happens (the audio track is selected
 *    explicitly), matching FFmpeg's `-vn` behavior.
 *  - **burnSubtitles** uses Media3 [Transformer] with hardware decode + encode
 *    via [MediaCodec]. Subtitles render through [SrtBitmapOverlay], which
 *    rasterizes the active SRT cue to a [Bitmap] via [Canvas]/[StaticLayout].
 *    libass `{\fs..\c..}` per-line overrides are NOT supported here — when the
 *    user has configured distinct translated styling, [RoutingMediaEngine]
 *    falls back to FFmpeg before the call ever lands here.
 *
 * Errors get wrapped as [FfmpegException] so the existing orchestrator error
 * path (which only knows about that type) keeps working.
 */
@UnstableApi
class Media3TransformerEngine(
    private val context: Context,
    private val dispatchers: DispatcherProvider,
) : FFmpegEngine {

    override fun extractAudio(
        input: File,
        output: File,
        durationMs: Long,
    ): Flow<FfmpegProgress> = flow {
        output.parentFile?.mkdirs()
        if (output.exists()) output.delete()

        val extractor = MediaExtractor()
        var codec: MediaCodec? = null
        var raf: RandomAccessFile? = null
        var totalBytes = 0L

        try {
            extractor.setDataSource(input.absolutePath)
            val audioTrack = (0 until extractor.trackCount).firstOrNull { i ->
                extractor.getTrackFormat(i).getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true
            } ?: throw FfmpegException(-1, "No audio track in ${input.name}")
            extractor.selectTrack(audioTrack)
            val srcFormat = extractor.getTrackFormat(audioTrack)
            val srcSampleRate = srcFormat.getInteger(MediaFormat.KEY_SAMPLE_RATE)
            val srcChannels = srcFormat.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
            val mime = srcFormat.getString(MediaFormat.KEY_MIME) ?: "audio/raw"

            codec = MediaCodec.createDecoderByType(mime).apply {
                configure(srcFormat, /* surface = */ null, /* crypto = */ null, 0)
                start()
            }

            val pcmFile = RandomAccessFile(output, "rw").apply {
                // Reserve 44 bytes; we patch the header in the finally block
                // once we know the PCM byte count.
                setLength(0)
                write(ByteArray(WAV_HEADER_BYTES))
            }
            raf = pcmFile

            val info = MediaCodec.BufferInfo()
            val timeoutUs = 10_000L
            var sawInputEos = false
            var sawOutputEos = false
            // Linear-interpolation accumulator for naive resampling. Tracks
            // fractional source sample position; emits one target sample
            // whenever the accumulator advances past srcSampleRate ticks.
            var resampleAcc = 0L

            while (!sawOutputEos) {
                if (currentCoroutineContext()[Job]?.isActive == false) {
                    throw CancellationException("extractAudio cancelled")
                }

                if (!sawInputEos) {
                    val inIdx = codec.dequeueInputBuffer(timeoutUs)
                    if (inIdx >= 0) {
                        val inBuf = codec.getInputBuffer(inIdx) ?: continue
                        val sampleSize = extractor.readSampleData(inBuf, 0)
                        if (sampleSize < 0) {
                            codec.queueInputBuffer(inIdx, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            sawInputEos = true
                        } else {
                            codec.queueInputBuffer(inIdx, 0, sampleSize, extractor.sampleTime, 0)
                            extractor.advance()
                        }
                    }
                }

                val outIdx = codec.dequeueOutputBuffer(info, timeoutUs)
                if (outIdx >= 0) {
                    val outBuf = codec.getOutputBuffer(outIdx)
                    if (outBuf != null && info.size > 0) {
                        val pcmShorts = ShortArray(info.size / 2)
                        outBuf.order(ByteOrder.LITTLE_ENDIAN).asShortBuffer().get(pcmShorts)
                        val written = writeResampledMono(
                            pcmFile,
                            pcmShorts,
                            srcChannels,
                            srcSampleRate,
                            TARGET_SAMPLE_RATE,
                            resampleAccRef = { resampleAcc },
                            update = { resampleAcc = it },
                        )
                        totalBytes += written
                        // Emit progress based on presentation time. info.presentationTimeUs
                        // is monotonic; cap to durationMs so a slightly long source
                        // doesn't push percent past 100.
                        val timeMs = (info.presentationTimeUs / 1000).coerceAtLeast(0)
                        val percent = if (durationMs > 0) {
                            ((timeMs * 100) / durationMs).toInt().coerceIn(0, 100)
                        } else 0
                        emit(FfmpegProgress(percent, timeMs, totalBytes + WAV_HEADER_BYTES, 1.0))
                    }
                    codec.releaseOutputBuffer(outIdx, false)
                    if ((info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
                        sawOutputEos = true
                    }
                }
            }
            // Patch the WAV header now that we know the PCM byte count.
            patchWavHeader(pcmFile, totalBytes, channels = 1, sampleRate = TARGET_SAMPLE_RATE)
            emit(FfmpegProgress(100, durationMs, totalBytes + WAV_HEADER_BYTES, 1.0))
        } catch (t: Throwable) {
            runCatching { output.delete() }
            if (t is CancellationException) throw t
            if (t is FfmpegException) throw t
            throw FfmpegException(-1, t.message ?: t.javaClass.simpleName)
        } finally {
            runCatching { raf?.close() }
            runCatching { codec?.stop() }
            runCatching { codec?.release() }
            runCatching { extractor.release() }
        }
    }.flowOn(dispatchers.io)

    override fun burnSubtitles(
        video: File,
        srt: File,
        output: File,
        durationMs: Long,
        options: BurnOptions,
    ): Flow<FfmpegProgress> = callbackFlow {
        output.parentFile?.mkdirs()
        if (output.exists()) output.delete()

        val cues = parseSrt(srt.readText())
        val overlay = SrtBitmapOverlay(cues, options)

        val mediaItem = MediaItem.Builder()
            .setUri(video.absoluteFile.toURI().toString())
            .build()
        val edited = EditedMediaItem.Builder(mediaItem)
            .setEffects(
                Effects(
                    /* audioProcessors = */ emptyList(),
                    /* videoEffects = */ listOf(OverlayEffect(ImmutableList.of(overlay))),
                ),
            )
            .build()

        val transformer = Transformer.Builder(context).build()

        val listener = object : Transformer.Listener {
            override fun onCompleted(composition: Composition, exportResult: ExportResult) {
                trySend(FfmpegProgress(100, durationMs, output.length(), 1.0))
                close()
            }

            override fun onError(
                composition: Composition,
                exportResult: ExportResult,
                exportException: ExportException,
            ) {
                Timber.e(exportException, "Transformer export failed")
                close(FfmpegException(-1, exportException.message ?: "Transformer error"))
            }
        }

        // Transformer must be built+started on the main thread per its docs.
        withContext(Dispatchers.Main) {
            transformer.addListener(listener)
            transformer.start(edited, output.absolutePath)
        }

        // Poll progress every 250ms. This mirrors what FFmpegKitEngine emits
        // through StatisticsCallback so consumers see the same cadence.
        val progressJob = launch(Dispatchers.Main) {
            val holder = ProgressHolder()
            while (isActive) {
                val state = transformer.getProgress(holder)
                if (state == Transformer.PROGRESS_STATE_AVAILABLE) {
                    val percent = holder.progress.coerceIn(0, 100)
                    val timeMs = if (durationMs > 0) durationMs * percent / 100 else 0
                    trySend(FfmpegProgress(percent, timeMs, output.length(), 1.0))
                }
                delay(250L)
            }
        }

        awaitClose {
            progressJob.cancel()
            // Cancel must be on the main thread too.
            try {
                Thread {
                    runCatching { transformer.cancel() }
                }.also { it.start() }.join(1_000L)
            } catch (_: Throwable) { /* swallow */ }
            if (output.exists() && output.length() < 1024L) {
                runCatching { output.delete() }
            }
        }
    }

    /**
     * Decode→write loop — converts [pcmShorts] from [srcChannels] @ [srcRate]
     * to mono @ [targetRate] and appends to [raf]. Returns the number of
     * bytes written. Uses a sample-rate accumulator (Bresenham-style) to
     * pick which source frames map to target frames; this is naive but cheap
     * and good enough for ASR (16 kHz, ±dB doesn't matter — Whisper is
     * trained on noisy data).
     */
    private fun writeResampledMono(
        raf: RandomAccessFile,
        pcmShorts: ShortArray,
        srcChannels: Int,
        srcRate: Int,
        targetRate: Int,
        resampleAccRef: () -> Long,
        update: (Long) -> Unit,
    ): Int {
        val frames = pcmShorts.size / srcChannels
        if (frames == 0) return 0
        val out = ByteBuffer.allocate(frames * 2).order(ByteOrder.LITTLE_ENDIAN)
        var acc = resampleAccRef()
        var written = 0
        for (i in 0 until frames) {
            // Downmix to mono: average channels.
            var sum = 0
            for (c in 0 until srcChannels) sum += pcmShorts[i * srcChannels + c].toInt()
            val mono = (sum / srcChannels).coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt()).toShort()
            // Bresenham-style downsample. Emit while the accumulator owes
            // target-rate ticks; this handles both up- and down-sampling.
            acc += targetRate.toLong()
            while (acc >= srcRate.toLong()) {
                out.putShort(mono)
                written += 2
                acc -= srcRate.toLong()
            }
        }
        update(acc)
        if (written > 0) raf.write(out.array(), 0, written)
        return written
    }

    private fun patchWavHeader(
        raf: RandomAccessFile,
        pcmBytes: Long,
        channels: Int,
        sampleRate: Int,
    ) {
        val bitsPerSample = 16
        val byteRate = sampleRate * channels * bitsPerSample / 8
        val blockAlign = channels * bitsPerSample / 8
        val header = ByteBuffer.allocate(WAV_HEADER_BYTES).order(ByteOrder.LITTLE_ENDIAN)
        header.put("RIFF".toByteArray(Charsets.US_ASCII))
        header.putInt((36 + pcmBytes).toInt())
        header.put("WAVE".toByteArray(Charsets.US_ASCII))
        header.put("fmt ".toByteArray(Charsets.US_ASCII))
        header.putInt(16) // PCM fmt chunk size
        header.putShort(1) // PCM format
        header.putShort(channels.toShort())
        header.putInt(sampleRate)
        header.putInt(byteRate)
        header.putShort(blockAlign.toShort())
        header.putShort(bitsPerSample.toShort())
        header.put("data".toByteArray(Charsets.US_ASCII))
        header.putInt(pcmBytes.toInt())
        raf.seek(0)
        raf.write(header.array())
    }

    companion object {
        private const val TARGET_SAMPLE_RATE = 16_000
        private const val WAV_HEADER_BYTES = 44
    }
}

/**
 * Per-frame subtitle bitmap for Media3 [OverlayEffect]. Looks up the active
 * cue at [presentationTimeUs] and rasterizes it via [Canvas]/[StaticLayout].
 *
 * Note: this honors the same [BurnOptions] knobs as the FFmpeg/libass path
 * EXCEPT per-line overrides for translated text — that's libass-only and is
 * filtered out by the router before this engine ever sees the call.
 */
@UnstableApi
internal class SrtBitmapOverlay(
    private val cues: List<SrtCue>,
    private val options: BurnOptions,
) : BitmapOverlay() {

    private val transparent: Bitmap = Bitmap.createBitmap(2, 2, Bitmap.Config.ARGB_8888).also {
        it.eraseColor(Color.TRANSPARENT)
    }

    /**
     * LRU bitmap cache. ARGB_8888 cue bitmaps can be 100s of KB each; an
     * unbounded cache over a long video (1000+ cues) holds tens of MB live and
     * triggers OOM on low-RAM devices. The window only needs to cover cues
     * recently rendered around the cursor — Transformer never seeks backward.
     */
    private val bitmapCache: LinkedHashMap<Int, Bitmap> =
        object : LinkedHashMap<Int, Bitmap>(BITMAP_CACHE_MAX, 0.75f, /* accessOrder = */ true) {
            override fun removeEldestEntry(eldest: Map.Entry<Int, Bitmap>): Boolean {
                if (size > BITMAP_CACHE_MAX) {
                    eldest.value.recycle()
                    return true
                }
                return false
            }
        }
    // Settings is per-cue but tiny + immutable; keep as plain map.
    private val settingsCache = HashMap<Int, OverlaySettings>(cues.size)

    /**
     * Monotonic-cursor cue lookup. Transformer feeds [presentationTimeUs] in
     * non-decreasing order, so we only need to advance the cursor — the linear
     * scan that used to run per frame is now O(1) amortized over the whole
     * video. Falls back to a fresh forward scan if the caller ever rewinds
     * (e.g. a future seek-aware overlay use).
     */
    private var cursor = 0

    override fun getBitmap(presentationTimeUs: Long): Bitmap {
        val ms = presentationTimeUs / 1000
        val idx = activeCueIndex(ms) ?: return transparent
        return bitmapCache.getOrPut(idx) { renderCue(cues[idx]) }
    }

    override fun getOverlaySettings(presentationTimeUs: Long): OverlaySettings {
        val ms = presentationTimeUs / 1000
        val idx = activeCueIndex(ms) ?: return DEFAULT_SETTINGS
        return settingsCache.getOrPut(idx) { buildSettings() }
    }

    private fun activeCueIndex(ms: Long): Int? {
        if (cues.isEmpty()) return null
        // Rewind only if the caller went backward past the previous cue's start;
        // the common case (forward playback) keeps `cursor` monotonic.
        if (cursor >= cues.size || ms < cues[cursor].startMs) {
            cursor = 0
        }
        while (cursor < cues.size && ms > cues[cursor].endMs) cursor++
        if (cursor >= cues.size) return null
        val c = cues[cursor]
        return if (ms in c.startMs..c.endMs) cursor else null
    }

    private fun renderCue(cue: SrtCue): Bitmap {
        val lines = cue.filterBody(options.displayMode)
        if (lines.isEmpty()) return transparent
        val text = lines.joinToString("\n")

        // Subtitle overlays render against a 1080×720 canvas-equivalent the
        // app's preview already assumes; bitmaps composite onto whatever
        // resolution the encoder picks via OverlaySettings normalization.
        val paint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            textSize = options.fontSize.toFloat() * TEXT_DENSITY_SCALE
            color = options.fontColorArgb
            typeface = Typeface.DEFAULT_BOLD
        }
        val maxWidth = (CANVAS_WIDTH * 0.9f).toInt()
        val layout = StaticLayout.Builder
            .obtain(text, 0, text.length, paint, maxWidth)
            .setAlignment(Layout.Alignment.ALIGN_CENTER)
            .setLineSpacing(0f, 1f)
            .setIncludePad(false)
            .build()

        val padX = (paint.textSize / 4f).toInt()
        val padY = (paint.textSize / 6f).toInt()
        val bmpW = (layout.width + padX * 2).coerceAtLeast(2)
        val bmpH = (layout.height + padY * 2).coerceAtLeast(2)
        val bmp = Bitmap.createBitmap(bmpW, bmpH, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bmp)

        if (options.background) {
            val bgPaint = Paint().apply {
                color = (options.backgroundAlpha shl 24)
            }
            canvas.drawRect(Rect(0, 0, bmpW, bmpH), bgPaint)
        }

        if (options.outlineWidth > 0) {
            val outlinePaint = TextPaint(paint).apply {
                style = Paint.Style.STROKE
                strokeWidth = options.outlineWidth.toFloat() * TEXT_DENSITY_SCALE
                color = options.outlineColorArgb
            }
            canvas.save()
            canvas.translate(padX.toFloat(), padY.toFloat())
            val outlineLayout = StaticLayout.Builder
                .obtain(text, 0, text.length, outlinePaint, maxWidth)
                .setAlignment(Layout.Alignment.ALIGN_CENTER)
                .setLineSpacing(0f, 1f)
                .setIncludePad(false)
                .build()
            outlineLayout.draw(canvas)
            canvas.restore()
        }

        canvas.save()
        canvas.translate(padX.toFloat(), padY.toFloat())
        layout.draw(canvas)
        canvas.restore()
        return bmp
    }

    private fun buildSettings(): OverlaySettings {
        // Map the 9-grid SubtitleAlignment to NDC anchors. Media3's overlay
        // coordinate system is centered at (0, 0) with corners at ±1; y is
        // positive-up (GL convention). Two anchors are needed:
        //   backgroundFrameAnchor → where on the video frame to place
        //   overlayFrameAnchor → which point on the overlay aligns there
        val anchorX: Float; val anchorY: Float
        val overlayAnchorX: Float; val overlayAnchorY: Float
        when (options.alignment) {
            SubtitleAlignment.BottomCenter -> {
                anchorX = 0f; anchorY = -1f + marginYNdc()
                overlayAnchorX = 0f; overlayAnchorY = -1f
            }
            SubtitleAlignment.TopCenter -> {
                anchorX = 0f; anchorY = 1f - marginYNdc()
                overlayAnchorX = 0f; overlayAnchorY = 1f
            }
            SubtitleAlignment.MiddleCenter -> {
                anchorX = 0f; anchorY = 0f
                overlayAnchorX = 0f; overlayAnchorY = 0f
            }
        }
        val marginXNdc = (options.marginH.toFloat() / (CANVAS_WIDTH / 2f)).coerceIn(-1f, 1f)
        return OverlaySettings.Builder()
            .setBackgroundFrameAnchor(anchorX + marginXNdc, anchorY)
            .setOverlayFrameAnchor(overlayAnchorX, overlayAnchorY)
            .build()
    }

    private fun marginYNdc(): Float =
        (options.marginV.toFloat() / (CANVAS_HEIGHT / 2f)).coerceAtLeast(0f)

    companion object {
        // Reference canvas the subtitle preview already assumes (see
        // app/src/main/res/.../subtitle_style preview). Keeping this constant
        // so the burned output matches what the user previewed.
        private const val CANVAS_WIDTH = 1080
        private const val CANVAS_HEIGHT = 720
        // sp→px-ish scale; subtitles look tiny if we use the raw sp value
        // because Canvas paints in pixels. 2.0 matches the Roboto rendering
        // FFmpeg/libass produces at the same Fontsize.
        private const val TEXT_DENSITY_SCALE = 2.0f
        // Bitmap cache window — covers a few seconds of cues around the cursor.
        // Each entry can be a few hundred KB; 16 keeps the live set bounded.
        private const val BITMAP_CACHE_MAX = 16
        private val DEFAULT_SETTINGS: OverlaySettings = OverlaySettings.Builder().build()
    }
}
