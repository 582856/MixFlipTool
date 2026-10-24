package com.parallelc.mixfliptool

internal class MusicPresentationState {
    var ready = false
        private set
    var exiting = false
        private set
    private var exitStarted: Long? = null

    fun update(wanted: Boolean, wasActive: Boolean, command: String, now: Long): Boolean {
        if (command == "surfaceLost") ready = false
        if (wanted) {
            if (command == "surfaceReady") ready = true
            exiting = false
            exitStarted = null
            return true
        }
        val started = exitStarted ?: now
        if (wasActive && command != "exitComplete" && now - started < 1_000L) {
            exitStarted = started
            exiting = true
            return true
        }
        ready = false
        exiting = false
        exitStarted = null
        return false
    }
}
