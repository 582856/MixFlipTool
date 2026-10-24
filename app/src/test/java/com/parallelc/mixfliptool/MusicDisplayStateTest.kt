package com.parallelc.mixfliptool

import org.junit.Assert.*
import org.junit.Test

class MusicDisplayStateTest {
    @Test fun unlockingKeepsOuterDimUntilAnExplicitOuterWake() {
        val state = MusicDisplayState()
        state.update(true, true, true, true, 0)
        state.update(true, true, true, false, 100)
        assertTrue(state.visible)
        assertTrue(state.dimmed)
        state.command("wake", 200)
        state.update(true, true, true, false, 15_199)
        assertFalse(state.dimmed)
        state.update(true, true, true, false, 15_200)
        assertTrue(state.dimmed)
    }

    @Test fun unlockAfterLockOrAnExpiredOuterWakeDoesNotBrightenTheOuterScreen() {
        val state = MusicDisplayState()
        state.update(true, true, true, false, 0)
        assertFalse(state.dimmed)
        state.update(true, true, true, true, 100)
        assertTrue(state.dimmed)
        state.update(true, true, true, false, 200)
        assertTrue(state.dimmed)
        state.command("wake", 300)
        state.update(true, true, true, true, 400)
        assertFalse(state.dimmed)
        state.update(true, true, true, true, 15_300)
        assertTrue(state.dimmed)
        state.update(true, true, true, false, 15_400)
        assertTrue(state.dimmed)
    }

    @Test fun playbackButtonKeepsPausedControlsAwakeUntilTheInteractionTimeout() {
        val state = MusicDisplayState()
        state.update(true, true, true, true, 0)
        state.command("wake", 100)
        state.command("playPause", 14_000)
        state.update(true, false, true, true, 14_100)
        assertFalse(state.dimmed)
        state.update(true, false, true, true, 28_999)
        assertFalse(state.dimmed)
        state.update(true, false, true, true, 29_000)
        assertTrue(state.dimmed)
        state.command("playPause", 29_100)
        assertTrue(state.dimmed)
    }

    @Test fun pausedSessionDoesNotOpenUntilItHasPlayed() {
        val state = MusicDisplayState()
        state.update(true, false, true, false, 0)
        assertFalse(state.visible)
        state.update(true, true, true, false, 1)
        assertTrue(state.visible)
        assertFalse(state.dimmed)
    }

    @Test fun pauseRetainsInformationForOneHourAndResumeCancelsExpiry() {
        val state = MusicDisplayState()
        state.update(true, true, true, false, 0)
        state.update(true, false, false, false, 100)
        assertTrue(state.visible)
        assertTrue(state.dimmed)
        state.update(true, false, false, false, 3_600_099)
        assertTrue(state.visible)
        state.update(true, true, true, false, 3_600_100)
        state.update(true, false, true, false, 3_600_200)
        state.update(true, false, true, false, 7_200_199)
        assertTrue(state.visible)
        state.update(true, false, true, false, 7_200_200)
        assertFalse(state.visible)
    }

    @Test fun lockingDimsWithoutRemovingInformationAndOuterWakeDoesNotNeedUnlock() {
        val state = MusicDisplayState()
        state.update(true, true, true, false, 0)
        state.update(true, true, true, true, 100)
        assertTrue(state.visible)
        assertTrue(state.dimmed)
        state.command("wake", 200)
        state.update(true, true, true, true, 300)
        assertFalse(state.dimmed)
        state.update(true, false, true, true, 400)
        assertFalse(state.dimmed)
        state.command("wake", 500)
        state.update(true, false, true, true, 600)
        assertFalse(state.dimmed)
    }

    @Test fun explicitWakeSurvivesLockAndPlaybackChangesForTheFullTimeout() {
        val state = MusicDisplayState()
        state.update(true, true, true, true, 0)
        state.command("wake", 100)
        state.update(true, true, true, false, 200)
        assertFalse(state.dimmed)
        state.update(true, false, true, true, 300)
        assertFalse(state.dimmed)
        state.update(true, true, true, false, 15_099)
        assertFalse(state.dimmed)
        state.update(true, true, true, true, 15_100)
        assertTrue(state.dimmed)
        assertTrue(state.visible)
    }

    @Test fun toggleUsesCurrentStateAndSleepCancelsTheWakeTimeout() {
        val state = MusicDisplayState()
        state.update(true, true, true, true, 0)
        state.command("toggle", 100)
        assertFalse(state.dimmed)
        state.command("toggle", 200)
        state.update(true, true, true, true, 300)
        assertTrue(state.dimmed)
        state.command("wake", 400)
        state.command("sleep", 500)
        state.update(true, true, true, true, 600)
        assertTrue(state.dimmed)
    }

    @Test fun interactionExtendsWakeButDisablingClearsIt() {
        val state = MusicDisplayState()
        state.update(true, true, true, true, 0)
        state.command("wake", 100)
        state.command("next", 14_000)
        state.update(true, false, true, false, 28_999)
        assertFalse(state.dimmed)
        state.update(true, false, true, false, 29_000)
        assertTrue(state.dimmed)
        state.command("wake", 29_100)
        state.update(false, true, true, true, 29_200)
        state.update(true, true, true, true, 29_300)
        assertTrue(state.dimmed)
    }

    @Test fun pollingDoesNotKeepTheOuterScreenBright() {
        val state = MusicDisplayState()
        state.update(true, true, true, false, 0)
        for (now in 1L..15_000L step 100L) {
            state.update(true, true, true, false, now)
            state.command("sample", now)
        }
        state.update(true, true, true, false, 15_000)
        assertTrue(state.dimmed)
        state.command("wake", 15_001)
        state.command("touch", 29_000)
        state.update(true, true, true, false, 30_001)
        assertFalse(state.dimmed)
        state.update(false, true, true, false, 30_002)
        assertFalse(state.visible)
    }

    @Test fun endingTheSessionClosesCachedInformationAndCancelsExplicitWake() {
        val state = MusicDisplayState()
        state.update(true, true, true, false, 0)
        state.command("wake", 100)
        state.update(true, false, true, false, 200, hasSession = false)
        assertFalse(state.visible)
        assertTrue(state.dimmed)
        state.command("toggle", 300)
        assertFalse(state.visible)
        state.update(true, false, true, false, 400)
        assertFalse(state.visible)
        state.update(true, true, true, true, 500)
        assertTrue(state.visible)
        assertTrue(state.dimmed)
    }
}
