package com.parallelc.mixfliptool

import org.junit.Assert.*
import org.junit.Test

class MusicDisplayOptionsTest {
    @Test fun missingSettingsPreserveExistingBrightAndDimContent() {
        val options = MusicDisplayOptions.read { _, fallback -> fallback }
        MusicDisplayOptions.elements.forEach { (_, element) -> assertTrue(options.shows(element, false)) }
        assertTrue(options.shows(MusicDisplayOptions.TITLE, true))
        assertTrue(options.shows(MusicDisplayOptions.ALBUM, true))
        assertTrue(options.shows(MusicDisplayOptions.ARTIST, true))
        assertTrue(options.shows(MusicDisplayOptions.COVER, true))
        assertFalse(options.shows(MusicDisplayOptions.RHYTHM, true))
        assertFalse(options.shows(MusicDisplayOptions.NEXT_LYRIC, true))
        assertFalse(options.shows(MusicDisplayOptions.CONTROLS, true))
        assertFalse(options.shows(MusicDisplayOptions.CURRENT_LYRIC, true))
    }

    @Test fun savedModesAndPlaybackControlsStayIndependent() {
        val saved = mapOf(MusicDisplayOptions.BRIGHT_KEY to (MusicDisplayOptions.ALL xor MusicDisplayOptions.CONTROLS),
            MusicDisplayOptions.DIM_KEY to (MusicDisplayOptions.NEXT_LYRIC or MusicDisplayOptions.CONTROLS))
        val options = MusicDisplayOptions.read { key, fallback -> saved[key] ?: fallback }
        assertFalse(options.shows(MusicDisplayOptions.CONTROLS, false))
        assertTrue(options.shows(MusicDisplayOptions.TITLE, false))
        assertTrue(options.shows(MusicDisplayOptions.CONTROLS, true))
        assertTrue(options.shows(MusicDisplayOptions.NEXT_LYRIC, true))
        assertFalse(options.shows(MusicDisplayOptions.TITLE, true))
    }

    @Test fun modeTransitionFadesOnlyElementsWhoseVisibilityChanges() {
        val options = MusicDisplayOptions(MusicDisplayOptions.TITLE or MusicDisplayOptions.CONTROLS,
            MusicDisplayOptions.TITLE or MusicDisplayOptions.NEXT_LYRIC)
        assertEquals(1f, options.alpha(MusicDisplayOptions.TITLE, .3f))
        assertEquals(.3f, options.alpha(MusicDisplayOptions.CONTROLS, .3f))
        assertEquals(.7f, options.alpha(MusicDisplayOptions.NEXT_LYRIC, .3f))
        assertEquals(0f, options.alpha(MusicDisplayOptions.ALBUM, .3f))
        assertEquals(0f, options.alpha(MusicDisplayOptions.CONTROLS, 0f))
        assertEquals(0f, options.alpha(MusicDisplayOptions.NEXT_LYRIC, 1f))
    }

    @Test fun allOffAndUnknownSettingsDoNotEnableOtherElements() {
        val off = MusicDisplayOptions.read { _, _ -> 0 }
        MusicDisplayOptions.elements.forEach { (_, element) ->
            assertFalse(off.shows(element, false))
            assertFalse(off.shows(element, true))
        }
        assertEquals(MusicDisplayOptions(), MusicDisplayOptions.read { _, fallback -> fallback or 1024 })
    }

    @Test fun coverAndRhythmCanUseOppositeModesWithoutChangingControls() {
        val options = MusicDisplayOptions(MusicDisplayOptions.COVER or MusicDisplayOptions.CONTROLS,
            MusicDisplayOptions.RHYTHM)
        assertTrue(options.shows(MusicDisplayOptions.COVER, false))
        assertFalse(options.shows(MusicDisplayOptions.COVER, true))
        assertFalse(options.shows(MusicDisplayOptions.RHYTHM, false))
        assertTrue(options.shows(MusicDisplayOptions.RHYTHM, true))
        assertTrue(options.shows(MusicDisplayOptions.CONTROLS, false))
        assertFalse(options.shows(MusicDisplayOptions.CONTROLS, true))
        assertEquals(.25f, options.alpha(MusicDisplayOptions.COVER, .25f))
        assertEquals(.75f, options.alpha(MusicDisplayOptions.RHYTHM, .25f))
    }

    @Test fun oldSettingsKeepTheirElementsAndExistingCurrentLyricVisibility() {
        val saved = mapOf("bright_elements" to 31, "dim_elements" to 47)
        val options = MusicDisplayOptions.read { key, fallback -> saved[key] ?: fallback }
        assertEquals(31 or MusicDisplayOptions.CURRENT_LYRIC, options.bright)
        assertEquals(47, options.dim)
        assertFalse(options.shows(MusicDisplayOptions.CURRENT_LYRIC, true))
        assertFalse(options.shows(MusicDisplayOptions.COVER, false))
    }

    @Test fun savedCurrentLyricCanBeHiddenInBrightAndShownInDimIndependentlyOfNextLine() {
        val saved = mapOf("bright_elements" to 127, "dim_elements" to 47,
            MusicDisplayOptions.BRIGHT_KEY to MusicDisplayOptions.NEXT_LYRIC,
            MusicDisplayOptions.DIM_KEY to MusicDisplayOptions.CURRENT_LYRIC)
        val options = MusicDisplayOptions.read { key, fallback -> saved[key] ?: fallback }
        assertFalse(options.shows(MusicDisplayOptions.CURRENT_LYRIC, false))
        assertTrue(options.shows(MusicDisplayOptions.CURRENT_LYRIC, true))
        assertTrue(options.shows(MusicDisplayOptions.NEXT_LYRIC, false))
        assertFalse(options.shows(MusicDisplayOptions.NEXT_LYRIC, true))
        assertEquals(.7f, options.alpha(MusicDisplayOptions.CURRENT_LYRIC, .3f))
    }
}
