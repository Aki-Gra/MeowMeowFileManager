package com.meowmeow.filemanager.reader

import org.junit.Assert.assertEquals
import org.junit.Test

class ImageReaderRepositoryTest {
    @Test fun samplingFitsPreviewEdgeForLargeImages() {
        assertEquals(1, ImageReaderRepository.sampleSize(1600, 1000))
        assertEquals(2, ImageReaderRepository.sampleSize(3000, 2000))
        assertEquals(4, ImageReaderRepository.sampleSize(5000, 2000))
        assertEquals(8, ImageReaderRepository.sampleSize(12000, 8000))
        assertEquals(2, ImageReaderRepository.sampleSize(2049, 2000))
    }

    @Test fun portraitSamplingUsesTallDimension() {
        assertEquals(4, ImageReaderRepository.sampleSize(1000, 8000))
    }
}
