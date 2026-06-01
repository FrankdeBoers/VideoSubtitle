package com.frank.videosubtitle.data.engine

import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Decode a 16 kHz mono PCM s16le WAV file (the exact shape FFmpegKitEngine
 * produces) into the float[] whisper.cpp expects: samples in [-1, 1].
 *
 * Strict on inputs because the only producer in this app is our own pipeline
 * — a mismatch is a bug, not a user-supplied file.
 */
internal object WavDecoder {

    class WavFormatException(message: String) : RuntimeException(message)

    fun decodeMono16kHzPcm(wav: File): FloatArray {
        require(wav.exists()) { "WAV file does not exist: ${wav.absolutePath}" }
        RandomAccessFile(wav, "r").use { raf ->
            val header = ByteArray(12)
            if (raf.read(header) != 12) throw WavFormatException("WAV too small: ${wav.length()} bytes")
            if (!(header[0] == 'R'.code.toByte() && header[1] == 'I'.code.toByte() &&
                  header[2] == 'F'.code.toByte() && header[3] == 'F'.code.toByte())
            ) throw WavFormatException("Not a RIFF file")
            if (!(header[8]  == 'W'.code.toByte() && header[9]  == 'A'.code.toByte() &&
                  header[10] == 'V'.code.toByte() && header[11] == 'E'.code.toByte())
            ) throw WavFormatException("Not a WAVE file")

            var sampleRate = 0
            var numChannels = 0
            var bitsPerSample = 0
            var formatTag = 0
            var dataOffset = -1L
            var dataSize = -1L

            val chunkHeader = ByteArray(8)
            while (raf.filePointer < raf.length()) {
                if (raf.read(chunkHeader) != 8) break
                val id = String(chunkHeader, 0, 4, Charsets.US_ASCII)
                val size = ByteBuffer.wrap(chunkHeader, 4, 4)
                    .order(ByteOrder.LITTLE_ENDIAN).int.toLong() and 0xFFFFFFFFL
                when (id) {
                    "fmt " -> {
                        val fmt = ByteArray(size.toInt())
                        if (raf.read(fmt) != fmt.size) throw WavFormatException("Truncated fmt chunk")
                        val bb = ByteBuffer.wrap(fmt).order(ByteOrder.LITTLE_ENDIAN)
                        formatTag = bb.short.toInt() and 0xFFFF
                        numChannels = bb.short.toInt() and 0xFFFF
                        sampleRate = bb.int
                        bb.int  // byteRate
                        bb.short  // blockAlign
                        bitsPerSample = bb.short.toInt() and 0xFFFF
                    }
                    "data" -> {
                        dataOffset = raf.filePointer
                        dataSize = size
                        raf.seek(raf.filePointer + size)
                    }
                    else -> {
                        // Skip unknown chunks (LIST/INFO/etc).
                        raf.seek(raf.filePointer + size)
                    }
                }
                // Chunks are word-aligned; skip pad byte if size is odd.
                if (size % 2L == 1L && raf.filePointer < raf.length()) {
                    raf.seek(raf.filePointer + 1)
                }
            }

            if (formatTag != 1) throw WavFormatException("Expected PCM (1), got format $formatTag")
            if (numChannels != 1) throw WavFormatException("Expected mono, got $numChannels channels")
            if (sampleRate != 16_000) throw WavFormatException("Expected 16 kHz, got $sampleRate Hz")
            if (bitsPerSample != 16) throw WavFormatException("Expected 16-bit, got $bitsPerSample-bit")
            if (dataOffset < 0 || dataSize < 0) throw WavFormatException("Missing data chunk")

            val sampleCount = (dataSize / 2).toInt()
            val out = FloatArray(sampleCount)
            raf.seek(dataOffset)

            val bufBytes = 64 * 1024
            val buf = ByteArray(bufBytes)
            val bb = ByteBuffer.wrap(buf).order(ByteOrder.LITTLE_ENDIAN)
            var written = 0
            var remaining = dataSize
            while (remaining > 0) {
                val toRead = minOf(remaining, bufBytes.toLong()).toInt() and 0x7FFFFFFE  // even
                val read = raf.read(buf, 0, toRead)
                if (read <= 0) break
                bb.position(0)
                val samples = read / 2
                for (i in 0 until samples) {
                    out[written++] = bb.short / 32768f
                }
                remaining -= read.toLong()
            }
            return out
        }
    }
}
