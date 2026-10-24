package com.parallelc.mixfliptool

import android.content.Context
import android.graphics.Bitmap
import android.media.AudioAttributes
import android.media.MediaMetadata
import android.media.audiofx.Visualizer
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.util.Base64
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import kotlin.math.sqrt

class MusicStateReader(context: Context) : AutoCloseable {
    private val sessions = context.getSystemService(MediaSessionManager::class.java)
    private var visualizer: Visualizer? = null
    private var artworkKey = ""
    private var lastArtwork: Bitmap? = null
    private var captureFailed = false
    private val nativeFft = NativeMusicFft(context)
    private val cloudLyrics = CloudMusicLyricsCache(context)
    private var playingPackage = ""
    private var source = "none"
    private var nativeFrame: NativeMusicFft.Frame? = null
    private var lastNativeTime = 0L
    private var lyricsKey = ""
    private var lyrics = MusicLyrics("")

    fun read(wanted: Boolean): JSONObject {
        val result = JSONObject().put("playing", false).put("hasTrack", false).put("hasSession", false)
        val controller = if (wanted) sessions.getActiveSessions(null).filter {
            it.playbackState?.state in setOf(PlaybackState.STATE_PLAYING, PlaybackState.STATE_PAUSED,
                PlaybackState.STATE_BUFFERING, PlaybackState.STATE_CONNECTING,
                PlaybackState.STATE_FAST_FORWARDING, PlaybackState.STATE_REWINDING,
                PlaybackState.STATE_SKIPPING_TO_NEXT, PlaybackState.STATE_SKIPPING_TO_PREVIOUS,
                PlaybackState.STATE_SKIPPING_TO_QUEUE_ITEM) &&
                it.playbackInfo?.audioAttributes?.contentType != AudioAttributes.CONTENT_TYPE_MOVIE
        }.let { candidates -> candidates.firstOrNull { it.playbackState?.state == PlaybackState.STATE_PLAYING }
            ?: candidates.firstOrNull { it.packageName == playingPackage } ?: candidates.firstOrNull() } else null
        if (controller == null) {
            close()
            artworkKey = ""
            return result
        }
        val metadata = controller.metadata
        playingPackage = controller.packageName
        val isPlaying = controller.playbackState?.state == PlaybackState.STATE_PLAYING
        val album = metadata?.getString(MediaMetadata.METADATA_KEY_ALBUM).orEmpty()
        val lyricInfo = metadata?.getString("lyricInfo")?.let { runCatching { JSONObject(it) }.getOrNull() }
        val track = MusicTrackInfo.read(playingPackage) { key ->
            when (key) {
                "songName" -> lyricInfo?.optString("songName")
                "originalArtist" -> lyricInfo?.optString("artist")
                else -> metadata?.getString("android.media.metadata.$key")
            }
        }
        val title = track.title
        val rawLyrics = lyricInfo?.optString("lyric").orEmpty().ifBlank {
            metadata?.getString("android.media.metadata.LYRIC").orEmpty()
        }.ifBlank {
            val id = metadata?.getString(MediaMetadata.METADATA_KEY_MEDIA_ID).orEmpty()
            when (playingPackage) {
                "com.netease.cloudmusic" -> cloudLyrics.read(id)
                "cn.wenyu.bodian" -> cloudLyrics.readBodian(id)
                "com.miui.player" -> cloudLyrics.readXiaomi(id, title, track.artist,
                    metadata?.getLong(MediaMetadata.METADATA_KEY_DURATION) ?: 0L)
                else -> ""
            }
        }
        val lyricKey = listOf(playingPackage, metadata?.getString(MediaMetadata.METADATA_KEY_MEDIA_ID), rawLyrics).joinToString("\u0000")
        if (lyricKey != lyricsKey) {
            lyricsKey = lyricKey
            lyrics = MusicLyrics(rawLyrics)
        }
        val playback = controller.playbackState
        nativeFft.select(playingPackage)
        val sessionPosition = (playback?.position ?: 0L) + if (isPlaying && playback != null)
            ((android.os.SystemClock.elapsedRealtime() - playback.lastPositionUpdateTime) * playback.playbackSpeed).toLong() else 0L
        // CloudMusic's session sometimes reports position=0 while playing; its own service returns the real seek position.
        val position = if (playingPackage == "com.netease.cloudmusic") nativeFft.position() ?: sessionPosition else sessionPosition
        val (lyric, nextLyric) = lyrics.at(position, track.lyric)
        result.put("hasSession", true).put("playing", isPlaying).put("hasTrack", title.isNotBlank() || album.isNotBlank())
            .put("album", album).put("title", title.ifBlank { "正在播放" }).put("artist", track.artist)
            .put("lyric", lyric).put("nextLyric", nextLyric)
        val art = metadata?.getBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART)
            ?: metadata?.getBitmap(MediaMetadata.METADATA_KEY_ART)
            ?: metadata?.getBitmap(MediaMetadata.METADATA_KEY_DISPLAY_ICON)
        val key = listOf(controller.packageName, metadata?.getString(MediaMetadata.METADATA_KEY_MEDIA_ID),
            album, title, (art != null).toString()).joinToString("\u0000")
        // Players may replace a placeholder with the real cover without changing the track fields.
        val sameArtwork = art === lastArtwork || art != null && art.sameAs(lastArtwork)
        if (key != artworkKey || !sameArtwork) {
            artworkKey = key
            lastArtwork = art
            result.put("art", if (art == null) "" else {
                val small = Bitmap.createScaledBitmap(art, 160, 160, true)
                val bytes = ByteArrayOutputStream()
                small.compress(Bitmap.CompressFormat.JPEG, 85, bytes)
                if (small !== art) small.recycle()
                Base64.encodeToString(bytes.toByteArray(), Base64.NO_WRAP)
            })
        }
        return result
    }

    fun level(active: Boolean): Float {
        if (!active) {
            nativeFft.setFftEnabled(false)
            nativeFrame = null
            source = "none"
            visualizer?.release()
            visualizer = null
            captureFailed = false
            return 0f
        }
        nativeFft.select(playingPackage)
        nativeFft.setFftEnabled(true)
        nativeFrame = nativeFft.frame()
        nativeFrame?.let {
            visualizer?.release()
            visualizer = null
            captureFailed = false
            source = "native_fft"
            return 0f
        }
        source = "visualizer"
        if (captureFailed) return 0f
        return try {
            val capture = visualizer ?: Visualizer(0).also {
                it.captureSize = 256
                it.scalingMode = Visualizer.SCALING_MODE_NORMALIZED
                it.enabled = true
                visualizer = it
            }
            val waveform = ByteArray(capture.captureSize)
            if (capture.getWaveForm(waveform) != Visualizer.SUCCESS) return 0f
            val mean = waveform.sumOf { it.toInt() and 255 }.toDouble() / waveform.size
            val energy = waveform.sumOf { val sample = (it.toInt() and 255) - mean; sample * sample }
            (sqrt(energy / waveform.size).toFloat() / 100f).coerceIn(0f, 1f)
        } catch (_: RuntimeException) {
            captureFailed = true
            visualizer?.release()
            visualizer = null
            0f
        }
    }

    fun rhythmAvailable() = !captureFailed
    fun rhythmSource() = if (captureFailed) "none" else source

    fun appendNativeFrame(result: JSONObject) {
        nativeFrame?.takeIf { it.time != lastNativeTime }?.let {
            lastNativeTime = it.time
            result.put("fft", it.encoded).put("sampleRate", it.sampleRate).put("captureRate", it.captureRate)
        }
    }

    fun skip(next: Boolean) {
        sessions.getActiveSessions(null).firstOrNull {
            it.packageName == playingPackage && it.playbackState?.state in setOf(PlaybackState.STATE_PLAYING, PlaybackState.STATE_PAUSED)
        }?.transportControls?.let { if (next) it.skipToNext() else it.skipToPrevious() }
    }

    fun togglePlayback() {
        sessions.getActiveSessions(null).firstOrNull {
            it.packageName == playingPackage && it.playbackState?.state in setOf(PlaybackState.STATE_PLAYING, PlaybackState.STATE_PAUSED)
        }?.let {
            if (it.playbackState?.state == PlaybackState.STATE_PLAYING) it.transportControls.pause()
            else it.transportControls.play()
        }
    }

    override fun close() {
        lastArtwork = null
        cloudLyrics.close()
        nativeFft.close()
        nativeFrame = null
        lastNativeTime = 0L
        source = "none"
        visualizer?.release()
        visualizer = null
        captureFailed = false
    }
}
