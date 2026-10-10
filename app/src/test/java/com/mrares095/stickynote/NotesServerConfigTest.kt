package com.mrares095.stickynote

import org.junit.Assert.assertEquals
import org.junit.Assert.fail
import org.junit.Test

class NotesServerConfigTest {
    @Test fun httpsRemoteUrlIsNormalized() {
        assertEquals(
            "https://notes.example.com",
            NotesServerConfig.normalizeBaseUrl("  https://notes.example.com///  ")
        )
    }

    @Test fun privateLanHttpIsAllowed() {
        assertEquals(
            "http://192.168.1.204:3000",
            NotesServerConfig.normalizeBaseUrl("http://192.168.1.204:3000/")
        )
    }

    @Test fun publicHttpIsRejected() {
        assertInvalid("http://notes.example.com")
    }

    @Test fun credentialsQueryAndFragmentAreRejected() {
        assertInvalid("https://user:password@notes.example.com")
        assertInvalid("https://notes.example.com?token=secret")
        assertInvalid("https://notes.example.com#settings")
    }

    @Test fun invalidPortsAreRejected() {
        assertInvalid("https://notes.example.com:70000")
    }

    private fun assertInvalid(value: String) {
        try {
            NotesServerConfig.normalizeBaseUrl(value)
            fail("Expected invalid server URL to be rejected: $value")
        } catch (_: IllegalArgumentException) {
            // Expected validation failure.
        }
    }
}
