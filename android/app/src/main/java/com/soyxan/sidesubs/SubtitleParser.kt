package com.soyxan.sidesubs

private val SRT_TIME = Regex(
    """(\d{1,2}):(\d{2}):(\d{2})[,.](\d{1,3})\s*-->\s*(\d{1,2}):(\d{2}):(\d{2})[,.](\d{1,3})"""
)
private val ASS_TAG = Regex("""\{[^}]*\}""")
private val HTML_TAG = Regex("""<[^>]+>""")

object SubtitleParser {
    fun parse(text: String?): List<Cue> {
        if (text.isNullOrEmpty()) return emptyList()
        val ass = parseAss(text)
        return if (ass.isNotEmpty()) ass else parseSrt(text)
    }

    private fun parseAss(text: String): List<Cue> = buildList {
        text.replace("\r", "").lineSequence().forEach { line ->
            if (!line.startsWith("Dialogue:")) return@forEach
            val fields = line.removePrefix("Dialogue:").trim().split(",", limit = 10)
            if (fields.size < 10) return@forEach

            val start = parseAssTime(fields[1]) ?: return@forEach
            val end = parseAssTime(fields[2]) ?: return@forEach
            if (end < start) return@forEach

            val value = fields[9]
                .replace("\\N", "\n")
                .replace("\\n", "\n")
                .replace("\\h", " ")
                .replace(ASS_TAG, "")
                .trim()

            if (value.isNotEmpty()) add(Cue(start, end, value))
        }
    }

    private fun parseAssTime(value: String): Double? = runCatching {
        val parts = value.trim().split(":")
        require(parts.size == 3)
        parts[0].toInt() * 3600.0 +
            parts[1].toInt() * 60.0 +
            parts[2].replace(',', '.').toDouble()
    }.getOrNull()

    private fun parseSrt(text: String): List<Cue> {
        val lines = text.replace("\r", "").split("\n")
        val cues = mutableListOf<Cue>()
        var i = 0

        while (i < lines.size) {
            val match = SRT_TIME.matchEntire(lines[i].trim())
            if (match == null) {
                i++
                continue
            }

            val start = srtSeconds(match, 1)
            val end = srtSeconds(match, 5)
            val body = mutableListOf<String>()
            var j = i + 1
            while (j < lines.size && lines[j].isNotBlank()) {
                body += lines[j].trim()
                j++
            }
            val value = body.joinToString("\n").replace(HTML_TAG, "").trim()
            if (value.isNotEmpty() && end >= start) cues += Cue(start, end, value)
            i = j + 1
        }
        return cues
    }

    private fun srtSeconds(match: MatchResult, offset: Int): Double {
        val g = match.groupValues
        val hours = g[offset].toInt()
        val minutes = g[offset + 1].toInt()
        val seconds = g[offset + 2].toInt()
        val fraction = g[offset + 3]
        val millis = when (fraction.length) {
            1 -> fraction.toInt() * 100
            2 -> fraction.toInt() * 10
            else -> fraction.toInt()
        }
        return hours * 3600.0 + minutes * 60.0 + seconds + millis / 1000.0
    }
}
