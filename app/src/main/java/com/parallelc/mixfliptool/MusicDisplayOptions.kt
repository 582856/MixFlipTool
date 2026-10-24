package com.parallelc.mixfliptool

internal data class MusicDisplayOptions(val bright: Int = ALL, val dim: Int = DIM_DEFAULT) {
    fun shows(element: Int, dimmed: Boolean) = (if (dimmed) dim else bright) and element != 0

    fun alpha(element: Int, awakeProgress: Float): Float =
        (if (shows(element, false)) awakeProgress else 0f) +
            (if (shows(element, true)) 1f - awakeProgress else 0f)

    companion object {
        const val ALBUM = 1
        const val TITLE = 2
        const val ARTIST = 4
        const val NEXT_LYRIC = 8
        const val CONTROLS = 16
        const val COVER = 32
        const val RHYTHM = 64
        const val CURRENT_LYRIC = 128
        const val INFO = ALBUM or TITLE or ARTIST
        const val DIM_DEFAULT = INFO or COVER
        const val ALL = INFO or CURRENT_LYRIC or NEXT_LYRIC or CONTROLS or COVER or RHYTHM
        const val BRIGHT_KEY = "bright_elements_v2"
        const val DIM_KEY = "dim_elements_v2"
        val elements = listOf("封面" to COVER, "音乐律动" to RHYTHM,
            "专辑名" to ALBUM, "歌名" to TITLE, "歌手" to ARTIST,
            "当前歌词" to CURRENT_LYRIC, "下一句歌词" to NEXT_LYRIC, "播放控制按钮" to CONTROLS)

        fun read(value: (String, Int) -> Int) = MusicDisplayOptions(
            value(BRIGHT_KEY, value("bright_elements", ALL) or CURRENT_LYRIC) and ALL,
            value(DIM_KEY, value("dim_elements", DIM_DEFAULT)) and ALL)
    }
}
