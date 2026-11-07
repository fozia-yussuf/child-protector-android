package com.childprotector.app

import org.junit.Assert.assertEquals
import org.junit.Test

class OnDeviceFaceMatchAnalyzerTest {
    @Test
    fun acceptsRawBase64PhotoStoredByAdmin() {
        assertEquals("aGVsbG8=", photoBase64Payload("aGVsbG8="))
    }

    @Test
    fun acceptsBase64DataUrlPhoto() {
        assertEquals(
            "aGVsbG8=",
            photoBase64Payload("data:image/jpeg;base64,aGVsbG8=")
        )
    }
}
