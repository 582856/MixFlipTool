package com.parallelc.mixfliptool

import org.junit.Assert.*
import org.junit.Test

class MusicPresentationStateTest {
    @Test fun recreatedDisplayWaitsForItsNewSurfaceEvenWhileMusicKeepsPlaying() {
        val state = MusicPresentationState()
        state.update(true, false, "surfaceReady", 0)
        assertTrue(state.ready)
        assertTrue(state.update(true, true, "surfaceLost", 100))
        assertFalse(state.ready)
        state.update(true, false, "sample", 200)
        assertFalse(state.ready)
        state.update(true, false, "surfaceReady", 300)
        assertTrue(state.ready)
    }

    @Test fun losingTheSurfaceDuringExitClearsReadinessBeforeMusicResumes() {
        val state = MusicPresentationState()
        state.update(true, false, "surfaceReady", 0)
        state.update(false, true, "on", 100)
        state.update(false, true, "surfaceLost", 200)
        assertFalse(state.ready)
        assertTrue(state.update(true, false, "on", 300))
        assertFalse(state.exiting)
        assertFalse(state.ready)
    }

    @Test fun powerWaitsForTheSurfaceBeforeStarting() {
        val state = MusicPresentationState()
        assertTrue(state.update(true, false, "on", 0))
        assertFalse(state.ready)
        state.update(true, false, "surfaceReady", 100)
        assertTrue(state.ready)
    }

    @Test fun exitKeepsTheSurfaceUntilItsFadeFinishes() {
        val state = MusicPresentationState()
        state.update(true, false, "surfaceReady", 0)
        assertTrue(state.update(false, true, "on", 100))
        assertTrue(state.exiting)
        assertTrue(state.ready)
        assertTrue(state.update(false, true, "sample", 300))
        assertFalse(state.update(false, true, "exitComplete", 340))
        assertFalse(state.exiting)
        assertFalse(state.ready)
    }

    @Test fun aLostFadeCallbackCannotLeaveTheDisplayHeld() {
        val state = MusicPresentationState()
        state.update(true, false, "surfaceReady", 0)
        state.update(false, true, "on", 100)
        assertTrue(state.update(false, true, "sample", 1_099))
        assertFalse(state.update(false, true, "sample", 1_100))
        assertFalse(state.ready)
    }

    @Test fun resumedMusicCancelsExitAndIgnoresAnOldCompletion() {
        val state = MusicPresentationState()
        state.update(true, false, "surfaceReady", 0)
        state.update(false, true, "on", 100)
        assertTrue(state.update(true, true, "on", 200))
        assertFalse(state.exiting)
        assertTrue(state.update(true, true, "exitComplete", 300))
        assertTrue(state.ready)
    }

    @Test fun aCancelledPreparationDoesNotStartAnExitAnimation() {
        val state = MusicPresentationState()
        state.update(true, false, "on", 0)
        assertFalse(state.update(false, false, "on", 100))
        assertFalse(state.exiting)
        assertFalse(state.ready)
    }
}
