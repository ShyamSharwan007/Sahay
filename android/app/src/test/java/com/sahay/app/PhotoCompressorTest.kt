package com.sahay.app

import com.sahay.app.report.PhotoCompressor
import org.junit.Assert.assertEquals
import org.junit.Test

class PhotoCompressorTest {

    @Test fun `big photos are scaled so the long side is 1280`() {
        assertEquals(1280 to 960, PhotoCompressor.scaledSize(4000, 3000))
        assertEquals(960 to 1280, PhotoCompressor.scaledSize(3000, 4000))
    }

    @Test fun `small photos are not enlarged`() {
        assertEquals(800 to 600, PhotoCompressor.scaledSize(800, 600))
        assertEquals(1280 to 720, PhotoCompressor.scaledSize(1280, 720))
    }

    @Test fun `sample size keeps the long side at or above the target`() {
        assertEquals(1, PhotoCompressor.sampleSize(1280, 720))
        assertEquals(1, PhotoCompressor.sampleSize(2000, 1500))   // halving would give 1000 < 1280
        assertEquals(2, PhotoCompressor.sampleSize(4000, 3000))
        assertEquals(4, PhotoCompressor.sampleSize(8000, 6000))
    }

    @Test fun `degenerate sizes do not crash`() {
        assertEquals(1 to 1, PhotoCompressor.scaledSize(1, 1))
        assertEquals(1, PhotoCompressor.sampleSize(1, 1))
    }
}
