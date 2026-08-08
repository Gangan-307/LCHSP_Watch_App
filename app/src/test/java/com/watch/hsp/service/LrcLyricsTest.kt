package com.watch.hsp.service

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class LrcLyricsTest {
    @Test
    fun parsesCentisecondAndMillisecondTimestamps() {
        val lines = LrcLyrics.parse(
            "[00:01.50]第一句\n[00:04.125]第二句\n[ar:歌手]",
            null,
            10_000L
        )

        assertEquals(2, lines.size)
        assertEquals(1_500L, lines[0].timeMs)
        assertEquals("第二句", LrcLyrics.lineAt(lines, 4_500L))
    }

    @Test
    fun plainLyricsReceiveApproximateTimeline() {
        val lines = LrcLyrics.parse(null, "第一句\n\n第二句\n第三句", 9_000L)

        assertEquals(listOf(0L, 3_000L, 6_000L), lines.map { it.timeMs })
        assertEquals("第二句", LrcLyrics.lineAt(lines, 4_000L))
        assertFalse(lines.any { it.text.isBlank() })
    }
}
