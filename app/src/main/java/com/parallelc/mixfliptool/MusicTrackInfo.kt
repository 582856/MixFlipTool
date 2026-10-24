package com.parallelc.mixfliptool

internal data class MusicTrackInfo(val title: String, val artist: String, val lyric: String) {
    companion object {
        fun read(packageName: String, field: (String) -> String?): MusicTrackInfo {
            fun first(vararg keys: String) = keys.firstNotNullOfOrNull { key ->
                field(key)?.takeIf { it.isNotBlank() }
            }.orEmpty()
            // Both players publish lyrics as TITLE; their original song fields remain separate.
            val title = when (packageName) {
                "com.miui.player" -> first("CUSTOM_FIELD_TITLE", "DISPLAY_TITLE", "TITLE")
                "com.netease.cloudmusic" -> first("songName", "DISPLAY_TITLE", "TITLE")
                else -> first("TITLE", "DISPLAY_TITLE")
            }
            val artist = if (packageName in setOf("com.miui.player", "com.netease.cloudmusic"))
                first("originalArtist", "DISPLAY_SUBTITLE", "ARTIST", "ALBUM_ARTIST")
            else first("ARTIST", "ALBUM_ARTIST", "DISPLAY_SUBTITLE")
            val lyric = first("LYRIC").takeUnless { it.contains('[') || it.contains('\n') }.orEmpty()
                .ifBlank {
                    if (packageName in setOf("com.miui.player", "com.netease.cloudmusic"))
                        field("TITLE").orEmpty().takeIf { title.isNotBlank() && it != title && it != "$title - $artist" }.orEmpty()
                    else ""
                }
            return MusicTrackInfo(title, artist, lyric)
        }
    }
}

internal object MusicLyricsMatch {
    private fun normalized(value: String) = java.text.Normalizer.normalize(value, java.text.Normalizer.Form.NFKC)
        .filter { it.isLetterOrDigit() }.lowercase(java.util.Locale.ROOT)

    private fun artists(value: List<String>) = value.flatMap { it.split(Regex("[/、&＆;；]")) }
        .map(::normalized).filter { it.isNotEmpty() }.toSet()

    fun matches(title: String, artist: String, duration: Long, candidateTitle: String,
                candidateArtists: List<String>, candidateDuration: Long): Boolean =
        title.isNotBlank() && artist.isNotBlank() && duration > 0 && candidateDuration > 0 &&
            normalized(title) == normalized(candidateTitle) &&
            artists(listOf(artist)) == artists(candidateArtists) &&
            kotlin.math.abs(duration - candidateDuration) <= 2000L
}

internal class MusicLyrics(raw: String) {
    companion object {
        fun fromSeconds(lines: List<Pair<String, String>>): String = lines.mapNotNull { (time, text) ->
            val seconds = time.toDoubleOrNull()?.takeIf { it.isFinite() && it in 0.0..86_400.0 }
                ?: return@mapNotNull null
            val millis = kotlin.math.round(seconds * 1000).toLong()
            val stamp = "%02d:%02d.%03d".format(java.util.Locale.ROOT,
                millis / 60_000, millis / 1000 % 60, millis % 1000)
            "[$stamp]${text.replace('\n', ' ').replace('\r', ' ').trim()}"
        }.joinToString("\n")
    }

    private val voicePrefix = Regex("^[^:：]{1,20}[:：]")
    private fun content(value: String) = value.replace(voicePrefix, "").trim()
    private fun normalized(value: String) = content(value).filter { it.isLetterOrDigit() }.lowercase(java.util.Locale.ROOT)

    private val lines = buildList {
        val timestamp = Regex("\\[(\\d{1,3}):(\\d{2})(?:[.:](\\d{1,3}))?]")
        val offset = Regex("\\[offset:([+-]?\\d+)]", RegexOption.IGNORE_CASE)
            .find(raw)?.groupValues?.get(1)?.toLongOrNull() ?: 0L
        raw.lineSequence().forEach { line ->
            val matches = timestamp.findAll(line).toList()
            val value = timestamp.replace(line, "").trim()
            matches.forEach {
                val (minutes, seconds, fraction) = it.destructured
                val time = minutes.toLong() * 60_000 + seconds.toLong() * 1000 +
                    fraction.padEnd(3, '0').toLong() - offset
                add(time to value)
            }
        }
    }.sortedBy { it.first }
    private val normalizedLines = lines.map { normalized(it.second) }

    fun at(position: Long, current: String): Pair<String, String> {
        val timedIndex = lines.indexOfLast { it.first <= position }
        val matching = if (current.isBlank()) emptyList() else lines.indices.filter { lines[it].second == current }
        val currentText = normalized(current)
        val equivalent = if (currentText.isEmpty()) emptyList() else lines.indices.filter {
            val value = normalizedLines[it]
            value == currentText || (currentText.length >= 4 && value.contains(currentText))
        }
        val candidates = matching.ifEmpty { equivalent }.map { it to it }.ifEmpty {
            if (currentText.isEmpty()) emptyList() else (0 until lines.lastIndex).filter {
                normalizedLines[it].isNotEmpty() && normalizedLines[it + 1].isNotEmpty() &&
                    normalizedLines[it] + normalizedLines[it + 1] == currentText
            }.map { it to it + 1 }
        }
        val matched = candidates.minByOrNull { kotlin.math.abs(lines[it.first].first - position) }
        val index = matched?.first ?: if (current.isBlank()) timedIndex else -1
        val line = current.ifBlank { lines.getOrNull(index)?.second.orEmpty() }
        var remainder = ""
        if (index >= 0 && currentText.isNotEmpty()) {
            val value = content(lines[index].second)
            val text = normalized(value)
            val end = text.indexOf(currentText).takeIf { it >= 0 }?.plus(currentText.length) ?: text.length
            if (end < text.length) {
                var count = 0
                val boundary = value.indexOfFirst { if (it.isLetterOrDigit()) ++count > end else false }
                if (boundary >= 0) remainder = value.substring(boundary).trim()
            }
        }
        val next = remainder.ifBlank {
            if (index >= 0) lines.drop((matched?.second ?: index) + 1).firstOrNull { it.second.isNotBlank() }?.second.orEmpty() else ""
        }
        return line to next
    }
}
