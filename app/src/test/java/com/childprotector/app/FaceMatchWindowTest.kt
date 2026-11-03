package com.childprotector.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class FaceMatchWindowTest {
    @Test
    fun confirmsAfterThreeMatchesInWindow() {
        val window = FaceMatchWindow()

        assertNull(window.add(0.50))
        assertNull(window.add(0.48))
        assertEquals(0.4967, window.add(0.51)!!, 0.0001)
    }

    @Test
    fun onlyCountsMatchesAmongLatestFiveFrames() {
        val window = FaceMatchWindow()
        repeat(3) { window.add(0.50) }
        assertEquals(0.50, window.add(0.10)!!, 0.0001)
        assertEquals(0.50, window.add(0.10)!!, 0.0001)
        assertNull(window.add(0.10))
    }

    @Test
    fun resetClearsPreviousMatches() {
        val window = FaceMatchWindow()
        repeat(2) { window.add(0.50) }

        window.reset()

        assertNull(window.add(0.50))
        assertNull(window.add(0.50))
        assertEquals(0.50, window.add(0.50)!!, 0.0001)
    }
}
