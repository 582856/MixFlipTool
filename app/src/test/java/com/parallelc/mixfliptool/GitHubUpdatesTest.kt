package com.parallelc.mixfliptool

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GitHubUpdatesTest {
    @Test fun comparesNumbersInsteadOfAlphabeticalOrder() {
        assertTrue(isNewerRelease("1.3.10", "1.3.9"))
        assertTrue(isNewerRelease("v1.10.0", "1.9.9"))
        assertFalse(isNewerRelease("1.3.2", "1.3.3"))
    }

    @Test fun equalOrOlderReleaseNeverPromptsDowngrade() {
        assertFalse(isNewerRelease("1.3.3", "1.3.3"))
        assertFalse(isNewerRelease("v1.3.3", "1.3.3.0"))
        assertFalse(isNewerRelease("1.3.3+build42", "1.3.3"))
        assertFalse(isNewerRelease("1.3.3", "1.4.0"))
    }

    @Test fun stableReleaseCanReplaceSameVersionPrerelease() {
        assertTrue(isNewerRelease("1.3.3", "1.3.3-rc1"))
        assertFalse(isNewerRelease("1.3.3-rc1", "1.3.3"))
    }

    @Test fun malformedOrOverflowVersionCannotTriggerDownload() {
        assertFalse(isNewerRelease("latest", "1.3.3"))
        assertFalse(isNewerRelease("1.4.0", "unknown"))
        assertFalse(isNewerRelease("99999999999999999999999.0", "1.3.3"))
    }
}
