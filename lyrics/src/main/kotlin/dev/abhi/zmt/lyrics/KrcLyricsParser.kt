package dev.abhi.zmt.lyrics

import java.io.ByteArrayOutputStream
import java.util.Base64
import java.util.zip.Inflater

object KrcLyricsParser {

    private val XOR_KEY = intArrayOf(64, 71, 97, 119, 94, 50, 116, 71, 81, 54, 49, 45, 206, 210, 110, 105)
    private val LINE_REGEX = Regex("""^\[(\d+),(\d+)](.+)$""")
    private val WORD_REGEX = Regex("""<(\d+),(\d+),\d+>([^<]*)""")

    fun matches(raw: String): Boolean =
        raw.lineSequence().any { LINE_REGEX.matches(it.trim()) && WORD_REGEX.containsMatchIn(it) }

    fun decodeBase64Krc(base64Content: String): String? = runCatching {
        val raw = Base64.getDecoder().decode(base64Content.trim())
        if (raw.size <= 4) return null
        val compressed = ByteArray(raw.size - 4) { i ->
            (raw[i + 4].toInt() xor XOR_KEY[i % XOR_KEY.size]).toByte()
        }
        val inflater = Inflater()
        inflater.setInput(compressed)
        val out = ByteArrayOutputStream()
        val buf = ByteArray(1024)
        while (!inflater.finished()) {
            val count = inflater.inflate(buf)
            if (count == 0 && inflater.needsInput()) break
            out.write(buf, 0, count)
        }
        inflater.end()
        out.toString("UTF-8")
    }.getOrNull()

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
                val offsetMs = wordMatch.groupValues[1].toLongOrNull() ?: 0L
                val wDur = wordMatch.groupValues[2].toLongOrNull() ?: 0L
                val token = wordMatch.groupValues[3]
                if (token.isEmpty()) continue

                val charStart = textBuilder.length
                textBuilder.append(token)
                val charEnd = textBuilder.length

                val wStart = lineStart + offsetMs
                words += LyricWord(
                    startMs = wStart,
                    endMs = (wStart + wDur).coerceAtLeast(wStart + 1L),
                    start = charStart,
                    end = charEnd,
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
