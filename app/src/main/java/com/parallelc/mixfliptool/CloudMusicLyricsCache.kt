package com.parallelc.mixfliptool

import android.content.Context
import android.os.SystemClock
import android.util.Log
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.security.MessageDigest
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.Future

internal class CloudMusicLyricsCache(context: Context) : AutoCloseable {
    private val directory = runCatching { File(context.cacheDir, "mixflip-lyrics") }.getOrNull()
    private var worker: ExecutorService? = null
    private var request: Future<String?>? = null
    private var songId = ""
    private var raw = ""
    private var found = false
    private var retryAt = 0L

    fun read(id: String): String {
        if (!id.matches(Regex("[1-9][0-9]{0,18}"))) return ""
        return readCached(id) { load(id) }
    }

    fun readBodian(id: String): String {
        if (!id.matches(Regex("[1-9][0-9]{0,18}"))) return ""
        // Bodian publishes Kuwo RIDs; they must never share NetEase's numeric cache keys.
        val key = "bodian-$id"
        return readCached(key) {
            val file = directory?.let { File(it, "$key.lrc") }
            if (file?.isFile == true && file.length() <= 1_048_576L) {
                Log.i("MixFlipLyrics", "source=bodian-disk; song=$id")
                file.readText()
            } else {
                val json = getJson("https://www.kuwo.cn/openapi/v1/www/lyric/getlyric?musicId=$id")
                if (json?.optInt("code") != 200) return@readCached null
                val lines = json.optJSONObject("data")?.optJSONArray("lrclist") ?: return@readCached null
                val lyric = MusicLyrics.fromSeconds((0 until lines.length()).map { index ->
                    val line = lines.getJSONObject(index)
                    line.optString("time") to line.optString("lineLyric")
                })
                if (lines.length() > 0 && lyric.isBlank()) return@readCached null
                if (Thread.currentThread().isInterrupted) return@readCached null
                runCatching { directory?.mkdirs(); file?.writeText(lyric) }
                Log.i("MixFlipLyrics", "source=bodian-kuwo-api; song=$id; timed=${lyric.contains('[')}")
                lyric
            }
        }
    }

    fun readXiaomi(id: String, title: String, artist: String, duration: Long): String {
        if (title.isBlank() || artist.isBlank() || duration <= 0) return ""
        val key = "xiaomi-" + MessageDigest.getInstance("SHA-256")
            .digest("$id\u0000$title\u0000$artist\u0000$duration".toByteArray())
            .joinToString("") { "%02x".format(it) }
        return readCached(key) {
            val file = directory?.let { File(it, "$key.lrc") }
            if (file?.isFile == true && file.length() <= 1_048_576L) {
                Log.i("MixFlipLyrics", "source=xiaomi-match-disk; song=$id")
                file.readText()
            } else {
                val query = URLEncoder.encode("$title $artist", "UTF-8")
                val json = getJson("https://music.163.com/api/search/get?s=$query&type=1&limit=10")
                if (json?.optInt("code") != 200) return@readCached null
                val songs = json.optJSONObject("result")?.optJSONArray("songs") ?: return@readCached null
                val match = (0 until songs.length()).map { songs.getJSONObject(it) }.firstOrNull { song ->
                    val artists = song.optJSONArray("artists") ?: return@firstOrNull false
                    MusicLyricsMatch.matches(title, artist, duration, song.optString("name"),
                        (0 until artists.length()).map { artists.getJSONObject(it).optString("name") },
                        song.optLong("duration"))
                } ?: return@readCached null
                val lyric = load(match.optLong("id").toString()) ?: return@readCached null
                if (!Thread.currentThread().isInterrupted) runCatching { directory?.mkdirs(); file?.writeText(lyric) }
                Log.i("MixFlipLyrics", "source=xiaomi-match; song=$id; matched=${match.optLong("id")}")
                lyric
            }
        }
    }

    private fun readCached(id: String, load: () -> String?): String {
        if (songId != id) {
            request?.cancel(true)
            request = null
            songId = id
            raw = ""
            found = false
            retryAt = 0L
        }
        val now = SystemClock.elapsedRealtime()
        request?.takeIf { it.isDone }?.let {
            val result = runCatching { it.get() }.getOrNull()
            if (result != null) {
                raw = result
                found = true
            }
            request = null
        }
        if (!found && request == null && now >= retryAt) {
            retryAt = now + 30_000L
            val executor = worker ?: Executors.newSingleThreadExecutor().also { worker = it }
            // Network and disk work must not delay the outer-screen gesture/power loop.
            request = executor.submit<String?> { runCatching { load() }.getOrElse {
                Log.w("MixFlipLyrics", "Official lyrics unavailable", it)
                null
            } }
        }
        return raw
    }

    private fun load(id: String): String? {
        val file = directory?.let { File(it, "$id.lrc") }
        if (file?.isFile == true && file.length() <= 1_048_576L) {
            Log.i("MixFlipLyrics", "source=disk; song=$id")
            return file.readText()
        }
        val json = getJson("https://music.163.com/api/song/lyric?id=$id&lv=-1&tv=-1&kv=-1") ?: return null
        if (json.optInt("code") != 200) return null
        val lyric = json.optJSONObject("lrc")?.optString("lyric")?.takeUnless { it == "null" }
            ?: if (json.optBoolean("nolyric") || json.optBoolean("uncollected")) "" else return null
        if (Thread.currentThread().isInterrupted) return null
        runCatching { directory?.mkdirs(); file?.writeText(lyric) }
        Log.i("MixFlipLyrics", "source=netease-api; song=$id; timed=${lyric.contains('[')}")
        return lyric
    }

    private fun getJson(url: String): JSONObject? {
        if (Thread.currentThread().isInterrupted) return null
        val connection = URL(url).openConnection() as HttpURLConnection
        return try {
            connection.connectTimeout = 2500
            connection.readTimeout = 2500
            connection.setRequestProperty("User-Agent", "Mozilla/5.0")
            if (connection.responseCode != 200) return null
            JSONObject(connection.inputStream.use { it.readNBytes(1_048_576).toString(Charsets.UTF_8) })
        } finally { connection.disconnect() }
    }

    override fun close() {
        request?.cancel(true)
        request = null
        worker?.shutdownNow()
        worker = null
        songId = ""
        raw = ""
        found = false
        retryAt = 0L
    }
}
