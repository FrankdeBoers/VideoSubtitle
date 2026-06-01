package com.frank.videosubtitle.data.source.local

import com.frank.videosubtitle.domain.model.Subtitle
import com.frank.videosubtitle.domain.model.SubtitleSegment
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class SrtSerializerTest {

    @get:Rule val tmp = TemporaryFolder()

    @Test fun `formatTimestamp pads hours minutes seconds and millis`() {
        assertEquals("00:00:00,000", SrtSerializer.formatTimestamp(0))
        assertEquals("00:00:01,234", SrtSerializer.formatTimestamp(1234))
        assertEquals("01:02:03,004", SrtSerializer.formatTimestamp(3723004))
        // Hour rollover past one digit still pads minutes/seconds
        assertEquals("12:34:56,789", SrtSerializer.formatTimestamp(12L * 3600_000 + 34 * 60_000 + 56_789))
    }

    @Test fun `formatTimestamp clamps negative to zero`() {
        assertEquals("00:00:00,000", SrtSerializer.formatTimestamp(-1))
    }

    @Test fun `parseTimestamp accepts comma and dot decimal`() {
        assertEquals(1234L, SrtSerializer.parseTimestamp("00:00:01,234"))
        assertEquals(1234L, SrtSerializer.parseTimestamp("00:00:01.234"))
        assertEquals(3723004L, SrtSerializer.parseTimestamp("01:02:03,004"))
    }

    @Test fun `parseTimestamp returns null on garbage`() {
        assertNull(SrtSerializer.parseTimestamp("not a time"))
        assertNull(SrtSerializer.parseTimestamp("01:02"))
        assertNull(SrtSerializer.parseTimestamp("01:02:03"))
    }

    @Test fun `roundtrip preserves segment text and times`() {
        val subtitle = Subtitle(
            segments = listOf(
                SubtitleSegment(1, 0, 1500, "Hello world"),
                SubtitleSegment(2, 1500, 3200, "Second line"),
                SubtitleSegment(3, 3200, 4000, "Third"),
            ),
            language = "en",
        )
        val file = tmp.newFile("out.srt")
        SrtSerializer.writeSrt(subtitle, file)

        val parsed = SrtSerializer.readSrt(file)
        assertEquals(3, parsed.segments.size)
        assertEquals("Hello world", parsed.segments[0].text)
        assertEquals(0L, parsed.segments[0].startMs)
        assertEquals(1500L, parsed.segments[0].endMs)
        assertEquals("Second line", parsed.segments[1].text)
        assertEquals(3200L, parsed.segments[2].startMs)
    }

    @Test fun `writeSrt skips blank segments`() {
        val subtitle = Subtitle(
            segments = listOf(
                SubtitleSegment(1, 0, 500, "kept"),
                SubtitleSegment(2, 500, 600, "   "),
                SubtitleSegment(3, 600, 1000, "also kept"),
            ),
            language = null,
        )
        val file = tmp.newFile("blank.srt")
        SrtSerializer.writeSrt(subtitle, file)
        val parsed = SrtSerializer.readSrt(file)
        assertEquals(2, parsed.segments.size)
        assertEquals("kept", parsed.segments[0].text)
        assertEquals("also kept", parsed.segments[1].text)
    }

    @Test fun `writeSrt trims segment text`() {
        val subtitle = Subtitle(
            segments = listOf(SubtitleSegment(1, 0, 1000, "  hello  ")),
            language = null,
        )
        val file = tmp.newFile("trim.srt")
        SrtSerializer.writeSrt(subtitle, file)
        assertEquals("hello", SrtSerializer.readSrt(file).segments[0].text)
    }
}
