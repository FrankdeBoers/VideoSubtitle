package com.frank.videosubtitle.data.engine

import com.frank.videosubtitle.domain.engine.SubtitleDisplay

/**
 * A single SubRip cue after splitting by blank lines and parsing the timing
 * row. [bodyLines] is whatever came after the timing line — typically two
 * lines for bilingual cues (`original\ntranslated`). Both engines (FFmpeg via
 * libass and Media3 via OverlayEffect) consume this to render the right
 * subset of lines per [SubtitleDisplay].
 */
data class SrtCue(
    val index: String,
    val startMs: Long,
    val endMs: Long,
    val mainLine: String,
    /** Empty when the cue had no second line. */
    val translatedLines: List<String>,
)

/**
 * Parse [text] (full file contents) into cues. Tolerates `\r\n` line endings
 * and missing index numbers; cues without a `-->` timing row are dropped.
 */
fun parseSrt(text: String): List<SrtCue> {
    val out = mutableListOf<SrtCue>()
    val blocks = text.split(Regex("\\r?\\n\\r?\\n"))
    for (block in blocks) {
        val trimmed = block.trim('\r', '\n', ' ')
        if (trimmed.isEmpty()) continue
        val lines = trimmed.split(Regex("\\r?\\n"))
        val timingIdx = lines.indexOfFirst { it.contains("-->") }
        if (timingIdx < 0) continue
        val timing = lines[timingIdx]
        val (startMs, endMs) = parseTiming(timing) ?: continue
        val index = if (timingIdx > 0) lines[0] else ""
        val body = if (timingIdx + 1 <= lines.lastIndex) {
            lines.subList(timingIdx + 1, lines.size)
        } else {
            emptyList()
        }
        val mainLine = body.firstOrNull().orEmpty()
        val translated = if (body.size > 1) body.subList(1, body.size) else emptyList()
        out.add(SrtCue(index, startMs, endMs, mainLine, translated))
    }
    return out
}

/** `HH:MM:SS,mmm --> HH:MM:SS,mmm` → (startMs, endMs). Null if malformed. */
private fun parseTiming(timing: String): Pair<Long, Long>? {
    val parts = timing.split("-->").map { it.trim() }
    if (parts.size != 2) return null
    val start = parseTimestamp(parts[0]) ?: return null
    val end = parseTimestamp(parts[1]) ?: return null
    return start to end
}

private fun parseTimestamp(stamp: String): Long? {
    // Accept both `,` and `.` as the millis separator since we've seen both
    // in the wild; whisper.cpp emits commas.
    val normalized = stamp.replace('.', ',')
    val match = Regex("(\\d+):(\\d+):(\\d+),(\\d+)").matchEntire(normalized) ?: return null
    val (h, m, s, ms) = match.destructured
    return h.toLong() * 3_600_000 + m.toLong() * 60_000 + s.toLong() * 1_000 + ms.toLong()
}

/**
 * Apply [SubtitleDisplay] filtering to a parsed cue's body lines. Returns
 * the lines that should actually render. Empty list means the cue should
 * be skipped entirely (only happens when both main and translated lines
 * are blank — defensive, shouldn't be reached for cues from whisper.cpp).
 */
fun SrtCue.filterBody(displayMode: SubtitleDisplay): List<String> = when (displayMode) {
    SubtitleDisplay.Both -> buildList {
        if (mainLine.isNotEmpty()) add(mainLine)
        addAll(translatedLines)
    }
    SubtitleDisplay.MainOnly -> if (mainLine.isNotEmpty()) listOf(mainLine) else emptyList()
    SubtitleDisplay.TranslatedOnly -> if (translatedLines.isNotEmpty()) {
        translatedLines
    } else {
        // Fall back to original so the cue isn't dropped entirely.
        if (mainLine.isNotEmpty()) listOf(mainLine) else emptyList()
    }
}
