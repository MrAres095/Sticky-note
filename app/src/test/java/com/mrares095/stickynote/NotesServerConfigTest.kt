package com.mrares095.stickynote

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class NotesServerConfigTest {
    @Test
    fun defaultServerUrlIsHttps() {
        assertEquals("https://notes.mandocloud.uk", NotesServerConfig.DEFAULT_BASE_URL)
    }

    @Test
    fun acceptsHttpsRemoteServerAndRemovesTrailingSlash() {
        assertEquals(
            "https://notes.example.com/base",
            NotesServerConfig.normalizeBaseUrl("  https://notes.example.com/base/  ")
        )
    }

    @Test
    fun acceptsPrivateLanHttpAddresses() {
        assertEquals("http://192.168.1.204:3000", NotesServerConfig.normalizeBaseUrl("http://192.168.1.204:3000"))
        assertEquals("http://10.0.0.8", NotesServerConfig.normalizeBaseUrl("http://10.0.0.8"))
        assertEquals("http://172.20.0.4", NotesServerConfig.normalizeBaseUrl("http://172.20.0.4"))
        assertEquals("http://localhost:8080", NotesServerConfig.normalizeBaseUrl("http://localhost:8080"))
    }

    @Test
    fun rejectsPublicHttpServer() {
        assertThrows(IllegalArgumentException::class.java) {
            NotesServerConfig.normalizeBaseUrl("http://notes.example.com")
        }
    }

    @Test
    fun rejectsMissingHostAndInvalidPorts() {
        assertThrows(IllegalArgumentException::class.java) {
            NotesServerConfig.normalizeBaseUrl("https://")
        }
        assertThrows(IllegalArgumentException::class.java) {
            NotesServerConfig.normalizeBaseUrl("https://notes.example.com:70000")
        }
    }

    @Test
    fun rejectsCredentialsQueriesAndFragments() {
        assertThrows(IllegalArgumentException::class.java) {
            NotesServerConfig.normalizeBaseUrl("https://user:password@notes.example.com")
        }
        assertThrows(IllegalArgumentException::class.java) {
            NotesServerConfig.normalizeBaseUrl("https://notes.example.com?token=secret")
        }
        assertThrows(IllegalArgumentException::class.java) {
            NotesServerConfig.normalizeBaseUrl("https://notes.example.com#section")
        }
    }

    @Test
    fun rejectsMalformedPrivateIpAddresses() {
        assertThrows(IllegalArgumentException::class.java) {
            NotesServerConfig.normalizeBaseUrl("http://192.168.999.2")
        }
        assertThrows(IllegalArgumentException::class.java) {
            NotesServerConfig.normalizeBaseUrl("http://172.40.0.2")
        }
    }
}
