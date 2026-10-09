package com.mrares095.stickynote

import android.content.Context

/**
 * Central configuration for the future shared notes backend.
 *
 * The backend is intentionally not contacted yet: the server and authentication
 * flow must be deployed and tested before enabling network sync. Keeping the
 * endpoint in one place means a future domain migration does not require
 * searching through the Android app for hard-coded URLs.
 */
object NotesServerConfig {
    private const val PREFS_NAME = "sticky_note_data"
    private const val SERVER_URL_KEY = "notes_server_base_url"

    const val DEFAULT_BASE_URL = "https://notes.mandocloud.uk"
    const val API_PREFIX = "/api/v1"

    fun baseUrl(context: Context): String {
        val saved = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getString(SERVER_URL_KEY, null)
            ?.trim()
            .orEmpty()
        return normalizeBaseUrl(saved.ifBlank { DEFAULT_BASE_URL })
    }

    fun apiUrl(context: Context, path: String): String {
        val cleanPath = path.trim().trimStart('/')
        return "${baseUrl(context)}${API_PREFIX}/$cleanPath"
    }

    /**
     * Validates and normalizes a server URL before saving it in app settings.
     * Only HTTPS is accepted for remote endpoints; HTTP is permitted for LAN
     * testing with a private IP address.
     */
    fun normalizeBaseUrl(value: String): String {
        val trimmed = value.trim().trimEnd('/')
        require(trimmed.startsWith("https://", ignoreCase = true) ||
            isPrivateLanHttpUrl(trimmed)) {
            "Adresa servera mora koristiti HTTPS (ili HTTP za privatnu LAN IP adresu)."
        }
        require(!trimmed.contains(' ')) { "Adresa servera ne smije sadržavati razmake." }
        return trimmed
    }

    private fun isPrivateLanHttpUrl(value: String): Boolean {
        if (!value.startsWith("http://", ignoreCase = true)) return false
        val host = value.removePrefix("http://").substringBefore('/').substringBefore(':')
        return host == "localhost" ||
            host.startsWith("192.168.") ||
            host.startsWith("10.") ||
            host.matches(Regex("172\\.(1[6-9]|2[0-9]|3[0-1])\\.\\d{1,3}\\.\\d{1,3}"))
    }

    fun saveBaseUrl(context: Context, value: String) {
        val normalized = normalizeBaseUrl(value)
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putString(SERVER_URL_KEY, normalized)
            .apply()
    }
}
