package com.parallelc.mixfliptool

internal class MusicDisplayState {
    var visible = false
        private set
    var dimmed = true
        private set
    private var pausedAt: Long? = null
    private var lastInteraction = 0L
    private var wakeUntil = 0L
    private var wasPlaying = false
    private var wasLocked = false

    fun update(enabled: Boolean, playing: Boolean, hasTrack: Boolean, locked: Boolean, now: Long, hasSession: Boolean = true) {
        val wasVisible = visible
        if (!enabled || !hasSession) {
            visible = false
            pausedAt = null
        } else if (playing && hasTrack) {
            visible = true
            pausedAt = null
        } else if (visible) {
            val since = pausedAt ?: now.also { pausedAt = it }
            if (now - since >= 3_600_000L) visible = false
        }
        if (!visible) {
            dimmed = true
            wakeUntil = 0L
        } else if (wakeUntil != 0L) {
            // A deliberate outer-screen wake must survive main-screen and media-session transitions.
            dimmed = now >= wakeUntil
            if (dimmed) wakeUntil = 0L
        }
        else if (!wasVisible || locked && !wasLocked) {
            dimmed = locked || !playing
            lastInteraction = now
        } else if (!playing && wasPlaying) dimmed = true
        else if (now - lastInteraction >= 15_000L) dimmed = true
        wasPlaying = playing
        wasLocked = locked
    }

    fun command(command: String, now: Long) {
        if (!visible) return
        when (command) {
            "sleep" -> dimmed = true
            "wake" -> dimmed = false
            "toggle" -> dimmed = !dimmed
        }
        if (command in setOf("sleep", "wake", "toggle")) {
            wakeUntil = if (dimmed) 0L else now + 15_000L
        }
        if (!dimmed && command in setOf("wake", "toggle", "touch", "next", "previous", "playPause")) {
            lastInteraction = now
            wakeUntil = now + 15_000L
        }
    }
}
