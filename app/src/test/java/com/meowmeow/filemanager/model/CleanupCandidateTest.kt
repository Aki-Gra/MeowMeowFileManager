package com.meowmeow.filemanager.model

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CleanupCandidateTest {
    @Test fun excludesSmallDownloads() {
        assertFalse(isLargeDownload(49L * 1024 * 1024))
    }

    @Test fun includesThresholdAndAbove() {
        assertTrue(isLargeDownload(50L * 1024 * 1024))
        assertTrue(isLargeDownload(200L * 1024 * 1024))
    }
}
