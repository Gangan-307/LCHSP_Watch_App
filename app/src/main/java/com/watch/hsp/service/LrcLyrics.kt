package com.watch.hsp.service

internal data class TimedLyricLine(val timeMs: Long, val text: String)

/** Pure LRC parsing kept separate so it can be covered by local unit tests. */
internal object LrcLyrics {
    private val timestamp = Regex("\\[(\\d{1,3}):(\\d{2})(?:[.:](\\d{1,3}))?]")

    fun parse(synced: String?, plain: String?, durationMs: Long): List<TimedLyricLine> {
        val timed = synced.orEmpty().lineSequence().flatMap { rawLine ->
            val matches = timestamp.findAll(rawLine).toList()
            val text = rawLine.substring(matches.lastOrNull()?.range?.last?.plus(1) ?: 0)
                .trim()
            if (matches.isEmpty() || text.isEmpty()) {
                emptySequence()
            } else {
                matches.asSequence().map { match ->
                    val minutes = match.groupValues[1].toLong()
                    val seconds = match.groupValues[2].toLong()
                    val fraction = match.groupValues[3]
                    val fractionMs = when (fraction.length) {
                        1 -> fraction.toLong() * 100L
                        2 -> fraction.toLong() * 10L
                        3 -> fraction.toLong()
                        else -> 0L
                    }
                    TimedLyricLine((minutes * 60L + seconds) * 1_000L + fractionMs, text)
                }
            }
        }.sortedBy { it.timeMs }.toList()

        if (timed.isNotEmpty()) {
            return timed.groupBy { it.timeMs }.map { (timeMs, lines) ->
                TimedLyricLine(timeMs, lines.map { it.text }.distinct().joinToString("\n"))
            }
        }

        val plainLines = plain.orEmpty().lineSequence()
            .map(String::trim)
            .filter(String::isNotEmpty)
            .toList()
        if (plainLines.isEmpty()) return emptyList()

        val interval = if (durationMs > 0L) {
            (durationMs / plainLines.size.coerceAtLeast(1)).coerceAtLeast(1_500L)
        } else {
            5_000L
        }
        return plainLines.mapIndexed { index, text -> TimedLyricLine(index * interval, text) }
    }

    fun lineAt(lines: List<TimedLyricLine>, positionMs: Long): String {
        if (lines.isEmpty()) return ""
        var low = 0
        var high = lines.lastIndex
        var result = 0
        while (low <= high) {
            val middle = (low + high) ushr 1
            if (lines[middle].timeMs <= positionMs) {
                result = middle
                low = middle + 1
            } else {
                high = middle - 1
            }
        }
        return lines[result].text
    }
}
