package com.frank.videosubtitle.data.source.local

import com.frank.videosubtitle.domain.model.Subtitle
import com.frank.videosubtitle.domain.model.SubtitleSegment
import java.io.File

/**
 * SubRip (.srt) read/write. Format:
 *
 *     1
 *     00:00:01,234 --> 00:00:02,500
 *     Hello world
 *     <blank line>
 *
 * - Times are HH:MM:SS,mmm (comma decimal, zero-padded).
 * - Hours roll over past 99 — we write the natural number of digits, but
 *   most players expect 2+ so we pad to at least 2.
 */
object SrtSerializer {

    fun writeSrt(subtitle: Subtitle, out: File) {
        out.parentFile?.mkdirs()
        out.bufferedWriter(Charsets.UTF_8).use { w ->
            subtitle.segments.forEachIndexed { i, seg ->
                if (seg.text.isBlank()) return@forEachIndexed
                w.write((i + 1).toString())
                w.newLine()
                w.write(formatTimestamp(seg.startMs))
                w.write(" --> ")
                w.write(formatTimestamp(seg.endMs))
                w.newLine()
                w.write(seg.text.trim())
                w.newLine()
                w.newLine()
            }
        }
    }

    fun readSrt(file: File): Subtitle {
        val segments = mutableListOf<SubtitleSegment>()
        val lines = file.readLines(Charsets.UTF_8)
        var i = 0
        while (i < lines.size) {
            // Skip blank lines between blocks.
            while (i < lines.size && lines[i].isBlank()) i++
            if (i >= lines.size) break
            // Index line (we don't trust it; renumber on output).
            val indexLine = lines[i].trim()
            if (indexLine.toIntOrNull() == null) { i++; continue }
            i++
            if (i >= lines.size) break
            val timeLine = lines[i]
            i++
            val arrow = timeLine.indexOf("-->")
            if (arrow < 0) continue
            val start = parseTimestamp(timeLine.substring(0, arrow).trim())
            val end = parseTimestamp(timeLine.substring(arrow + 3).trim())
            val text = StringBuilder()
            while (i < lines.size && lines[i].isNotBlank()) {
                if (text.isNotEmpty()) text.append('\n')
                text.append(lines[i])
                i++
            }
            if (start != null && end != null && text.isNotBlank()) {
                segments += SubtitleSegment(
                    index = segments.size + 1,
                    startMs = start,
                    endMs = end,
                    text = text.toString().trim(),
                )
            }
        }
        return Subtitle(segments = segments, language = null)
    }

    internal fun formatTimestamp(ms: Long): String {
        val clamped = if (ms < 0) 0 else ms
        val totalSeconds = clamped / 1000
        val millis = (clamped % 1000).toInt()
        val seconds = (totalSeconds % 60).toInt()
        val minutes = ((totalSeconds / 60) % 60).toInt()
        val hours = (totalSeconds / 3600).toInt()
        return "%02d:%02d:%02d,%03d".format(hours, minutes, seconds, millis)
    }

    internal fun parseTimestamp(s: String): Long? {
        // Accept HH:MM:SS,mmm or HH:MM:SS.mmm
        val normalized = s.replace('.', ',')
        val comma = normalized.lastIndexOf(',')
        if (comma < 0) return null
        val hms = normalized.substring(0, comma).split(':')
        if (hms.size != 3) return null
        val h = hms[0].toLongOrNull() ?: return null
        val m = hms[1].toLongOrNull() ?: return null
        val sec = hms[2].toLongOrNull() ?: return null
        val ms = normalized.substring(comma + 1).toLongOrNull() ?: return null
        return ((h * 3600 + m * 60 + sec) * 1000) + ms
    }
}
