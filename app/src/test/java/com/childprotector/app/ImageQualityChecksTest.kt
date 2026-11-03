package com.childprotector.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ImageQualityChecksTest {
    @Test
    fun rejectsZeroOrMultipleFaces() {
        assertEquals(
            "Keep exactly one face in view of the camera.",
            ImageQualityChecks.hint(0, 1000, null)
        )
        assertEquals(
            "Keep exactly one face in view of the camera.",
            ImageQualityChecks.hint(2, 1000, 500.0, 100.0, 100.0)
        )
    }

    @Test
    fun rejectsFaceShorterThanTwentyPercentOfFrame() {
        assertEquals(
            "Move closer so the face fills more of the frame.",
            ImageQualityChecks.hint(1, 1000, 199.0)
        )
        assertNull(ImageQualityChecks.hint(1, 1000, 200.0))
    }

    @Test
    fun rejectsDarkAndOverexposedImages() {
        assertEquals(
            "Too dark. Move to a brighter area.",
            ImageQualityChecks.hint(1, 1000, 300.0, brightness = 44.9)
        )
        assertEquals(
            "Too bright. Reduce glare and try again.",
            ImageQualityChecks.hint(1, 1000, 300.0, brightness = 215.1)
        )
        assertNull(ImageQualityChecks.hint(1, 1000, 300.0, brightness = 100.0))
    }

    @Test
    fun rejectsBlurredImageAndAcceptsSharpImage() {
        assertEquals(
            "Hold still while the camera focuses.",
            ImageQualityChecks.hint(
                1,
                1000,
                300.0,
                brightness = 100.0,
                laplacianVariance = 39.9
            )
        )
        assertNull(
            ImageQualityChecks.hint(
                1,
                1000,
                300.0,
                brightness = 100.0,
                laplacianVariance = 40.0
            )
        )
    }
}
