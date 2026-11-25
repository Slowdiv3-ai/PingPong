package dev.abhi.zmt.lyrics

object YrcLyricsParser {

    private val LINE_REGEX = Regex("""^\[(\d+),(\d+)](.+)$""")
    private val WORD_REGEX = Regex("""\((\d+),(\d+),\d+\)([^(]*)""")

    fun matches(raw: String): Boolean =
        raw.lineSequence().any { LINE_REGEX.matches(it.trim()) && WORD_REGEX.containsMatchIn(it) }

    fun parse(raw: String): Lyrics? {
        val parsed = mutableListOf<LyricLine>()
        for (rawLine in raw.lineSequence()) {
            val trimmed = rawLine.trim()
            val lineMatch = LINE_REGEX.matchEntire(trimmed) ?: continue
            val lineStart = lineMatch.groupValues[1].toLongOrNull() ?: continue
            val lineDur = lineMatch.groupValues[2].toLongOrNull() ?: 0L
            val body = lineMatch.groupValues[3]

            val textBuilder = StringBuilder()
            val words = mutableListOf<LyricWord>()

            for (wordMatch in WORD_REGEX.findAll(body)) {
                val wStart = wordMatch.groupValues[1].toLongOrNull() ?: lineStart
                val wDur = wordMatch.groupValues[2].toLongOrNull() ?: 0L
                val token = wordMatch.groupValues[3]
                if (token.isEmpty()) continue

                val charStart = textBuilder.length
                textBuilder.append(token)
                val charEnd = textBuilder.length

                words += LyricWord(
                    startMs = wStart,
                    endMs = (wStart + wDur).coerceAtLeast(wStart + 1L),
                    start = charStart,
                    end = charEnd,
                    background = false,
                )
            }

            val fullText = textBuilder.toString().trim()
            if (fullText.isEmpty()) continue

            parsed += LyricLine(
                startMs = lineStart,
                endMs = (lineStart + lineDur).coerceAtLeast(lineStart + 1L),
                text = fullText,
                words = words,
            )
        }

        if (parsed.isEmpty()) return null
        val normalized = parsed
            .markInstrumentalLines()
            .fillLineEnds()
            .withInterludes()
        return Lyrics(lines = normalized, synced = true)
    }
}
