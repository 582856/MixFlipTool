package com.parallelc.mixfliptool

import org.junit.Assert.*
import org.junit.Test

class MusicTrackInfoTest {
    @Test fun bodianSongFieldsStaySeparateFromTimedLyrics() {
        val track = MusicTrackInfo.read("cn.wenyu.bodian", mapOf("TITLE" to "晴天",
            "ARTIST" to "周杰伦", "ALBUM" to "叶惠美", "MEDIA_ID" to "228908")::get)
        assertEquals(MusicTrackInfo("晴天", "周杰伦", ""), track)
        val lyrics = MusicLyrics(MusicLyrics.fromSeconds(listOf(
            "29.26" to "故事的小黄花", "32.71" to "从出生那年就飘着", "36.24" to "童年的荡秋千")))
        assertEquals("" to "", lyrics.at(29_259, track.lyric))
        assertEquals("故事的小黄花" to "从出生那年就飘着", lyrics.at(29_260, track.lyric))
        assertEquals("从出生那年就飘着" to "童年的荡秋千", lyrics.at(34_000, track.lyric))
        assertEquals("故事的小黄花" to "从出生那年就飘着", lyrics.at(30_000, track.lyric))
    }

    @Test fun bodianSecondsHandleMinutesPrecisionAndInvalidTimes() {
        val raw = MusicLyrics.fromSeconds(listOf("0.0" to "开头", "60.6" to "下一分钟",
            "62.321" to "后一行\n续行", "NaN" to "无效", "Infinity" to "无效",
            "-1" to "无效", "oops" to "无效", "9999999999" to "无效"))
        assertEquals("[00:00.000]开头\n[01:00.600]下一分钟\n[01:02.321]后一行 续行", raw)
        assertEquals("下一分钟" to "后一行 续行", MusicLyrics(raw).at(60_600, ""))
    }

    @Test fun xiaomiLyricsNeverReplaceSongAndArtist() {
        val metadata = mutableMapOf("CUSTOM_FIELD_TITLE" to "红尘客栈", "ARTIST" to "红尘客栈-周杰伦",
            "DISPLAY_SUBTITLE" to "周杰伦", "DISPLAY_TITLE" to "天涯的尽头是风沙", "TITLE" to "天涯的尽头是风沙")
        val first = MusicTrackInfo.read("com.miui.player", metadata::get)
        metadata["TITLE"] = "红尘的故事叫牵挂"
        metadata["DISPLAY_TITLE"] = "红尘的故事叫牵挂"
        val second = MusicTrackInfo.read("com.miui.player", metadata::get)
        assertEquals("红尘客栈", first.title)
        assertEquals(first.title, second.title)
        assertEquals("周杰伦", second.artist)
        assertEquals("红尘的故事叫牵挂", second.lyric)
        metadata["CUSTOM_FIELD_TITLE"] = "素颜"
        metadata["DISPLAY_SUBTITLE"] = "许嵩/何曼婷"
        metadata["TITLE"] = "词：许嵩"
        assertEquals("素颜", MusicTrackInfo.read("com.miui.player", metadata::get).title)
    }

    @Test fun neteaseUsesOriginalDisplayFieldsWhileTitleAndArtistCarryLyrics() {
        val metadata = mapOf("DISPLAY_TITLE" to "绽放", "TITLE" to "这一句歌词",
            "ARTIST" to "这一句翻译", "DISPLAY_SUBTITLE" to "郑润泽")
        val track = MusicTrackInfo.read("com.netease.cloudmusic", metadata::get)
        assertEquals("绽放", track.title)
        assertEquals("郑润泽", track.artist)
        assertEquals("这一句歌词", track.lyric)
    }

    @Test fun originalLyricInfoWinsAndBlankFieldsFallBack() {
        val metadata = mapOf("songName" to "真实歌名", "originalArtist" to "真实歌手",
            "DISPLAY_TITLE" to "旧展示标题", "TITLE" to "当前歌词")
        val track = MusicTrackInfo.read("com.netease.cloudmusic", metadata::get)
        assertEquals("真实歌名", track.title)
        assertEquals("真实歌手", track.artist)
        assertEquals("当前歌词", track.lyric)
        val fallback = MusicTrackInfo.read("com.netease.cloudmusic", mapOf("DISPLAY_TITLE" to " ", "TITLE" to "歌名")::get)
        assertEquals("歌名", fallback.title)
        assertEquals("", fallback.lyric)
    }

    @Test fun ordinaryPlayersAndTitleArtistHeadersAreNotLyrics() {
        val metadata = mapOf("CUSTOM_FIELD_TITLE" to "素颜", "TITLE" to "素颜 - 许嵩/何曼婷",
            "DISPLAY_SUBTITLE" to "许嵩/何曼婷")
        assertEquals("", MusicTrackInfo.read("com.miui.player", metadata::get).lyric)
        val normal = MusicTrackInfo.read("other.player", mapOf("TITLE" to "歌名", "DISPLAY_TITLE" to "其他展示标题")::get)
        assertEquals("歌名", normal.title)
        assertEquals("", normal.lyric)
    }

    @Test fun timedLyricsFollowPlaybackSeekAndExposeNextLine() {
        val lyrics = MusicLyrics("[00:01.50]第一句\n[00:04.250]第二句\n[00:07]最后一句")
        assertEquals("第一句" to "第二句", lyrics.at(2000, ""))
        assertEquals("第二句" to "最后一句", lyrics.at(5000, ""))
        assertEquals("最后一句" to "", lyrics.at(8000, ""))
        assertEquals("第一句" to "第二句", lyrics.at(1500, ""))
        assertEquals("" to "", lyrics.at(0, ""))
    }

    @Test fun liveLyricCanResolveNextLineWhenPlayerReportsZeroPosition() {
        val lyrics = MusicLyrics("[00:01]第一句\n[00:04]第二句\n[00:07]第三句")
        assertEquals("第二句" to "第三句", lyrics.at(0, "第二句"))
        assertEquals("未知句" to "", lyrics.at(5000, "未知句"))
        assertEquals("当前歌词" to "", MusicLyrics("").at(0, "当前歌词"))
    }

    @Test fun repeatedTimestampsAndOffsetsAreHandled() {
        val lyrics = MusicLyrics("[offset:500]\n[00:01][00:10]重复句\n[00:05]第二句")
        assertEquals("重复句" to "第二句", lyrics.at(500, ""))
        assertEquals("重复句" to "", lyrics.at(9500, "重复句"))
    }

    @Test fun neteaseInstrumentalAndEndingCuesClearThePreviousLyric() {
        val lyrics = MusicLyrics("{\"t\":0,\"c\":[{\"tx\":\"作词\"}]}\n[00:01]第一句\n[00:04]\n[00:08]第二句\n[00:12]")
        assertEquals("第一句" to "第二句", lyrics.at(2000, ""))
        assertEquals("" to "第二句", lyrics.at(5000, ""))
        assertEquals("第二句" to "", lyrics.at(9000, ""))
        assertEquals("" to "", lyrics.at(13000, ""))
    }

    @Test fun xiaomiLyricsOnlyMatchTheSameTitleArtistsAndDuration() {
        assertTrue(MusicLyricsMatch.matches("牵丝戏", "银临/Aki阿杰", 239000,
            "牵丝戏", listOf("Aki阿杰", "银临"), 239302))
        assertFalse(MusicLyricsMatch.matches("牵丝戏", "银临/Aki阿杰", 239000,
            "牵丝戏", listOf("其他歌手"), 239302))
        assertFalse(MusicLyricsMatch.matches("牵丝戏", "银临/Aki阿杰", 239000,
            "牵丝戏 (Live)", listOf("银临", "Aki阿杰"), 239302))
        assertFalse(MusicLyricsMatch.matches("牵丝戏", "银临/Aki阿杰", 239000,
            "牵丝戏", listOf("银临", "Aki阿杰"), 280000))
        assertFalse(MusicLyricsMatch.matches("牵丝戏", "银临/Aki阿杰", 0,
            "牵丝戏", listOf("银临", "Aki阿杰"), 239302))
    }

    @Test fun xiaomiCurrentLineMatchesSpacesPunctuationAndVocalPrefixes() {
        val lyrics = MusicLyrics("[00:01]Aki：兰花指捻红尘似水\n[00:05]三尺红台 万事入歌吹\n[00:09]唱别久悲不成悲")
        assertEquals("兰花指捻红尘似水" to "三尺红台 万事入歌吹", lyrics.at(0, "兰花指捻红尘似水"))
        assertEquals("三尺红台，万事入歌吹" to "唱别久悲不成悲", lyrics.at(6000, "三尺红台，万事入歌吹"))
        assertEquals("尚未匹配的歌词" to "", lyrics.at(6000, "尚未匹配的歌词"))
    }

    @Test fun xiaomiSplitLineExposesTheRestBeforeTheFollowingTimedLine() {
        val lyrics = MusicLyrics("[00:01]没了你才算原罪 没了心才好相配\n[00:10]你褴褛我彩绘")
        assertEquals("没了你才算原罪" to "没了心才好相配", lyrics.at(2000, "没了你才算原罪"))
        assertEquals("没了心才好相配" to "你褴褛我彩绘", lyrics.at(6000, "没了心才好相配"))
    }

    @Test fun xiaomiCombinedLineSkipsBothSourceFragmentsForTheNextLine() {
        val lyrics = MusicLyrics("[00:56.92]千秋黄沙\n[00:59.44]不曾褪减\n[01:01.55]全唐的浓墨重彩")
        assertEquals("千秋黄沙不曾褪减" to "全唐的浓墨重彩", lyrics.at(58000, "千秋黄沙不曾褪减"))
    }
}
