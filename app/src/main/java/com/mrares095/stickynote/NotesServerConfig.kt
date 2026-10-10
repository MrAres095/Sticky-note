package com.mrares095.stickynote

import android.content.Context
import java.net.URI

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
        require(cleanPath.isNotBlank()) { "API putanja ne smije biti prazna." }
        require(!cleanPath.split('/').any { it == "." || it == ".." }) {
            "API putanja ne smije sadržavati relativne segmente."
        }
        return "${baseUrl(context)}${API_PREFIX}/$cleanPath"
    }

    /**
     * Validates and normalizes a server URL before saving it in app settings.
     * Only HTTPS is accepted for remote endpoints; HTTP is permitted for
     * loopback or private LAN addresses.
     */
    fun normalizeBaseUrl(value: String): String {
        val trimmed = value.trim().trimEnd('/')
        require(trimmed.isNotBlank()) { "Adresa servera ne smije biti prazna." }
        require(!trimmed.contains(' ')) { "Adresa servera ne smije sadržavati razmake." }

        val uri = try {
            URI(trimmed)
        } catch (_: Exception) {
            throw IllegalArgumentException("Adresa servera nije valjana URL adresa.")
        }
        val scheme = uri.scheme?.lowercase()
        val host = uri.host?.removePrefix("[")?.removeSuffix("]")
        require(!host.isNullOrBlank()) { "Adresa servera mora sadržavati valjano ime hosta ili IP adresu." }
        require(uri.rawUserInfo == null) { "Adresa servera ne smije sadržavati korisničko ime ili lozinku." }
        require(uri.rawQuery == null && uri.rawFragment == null) {
            "Adresa servera ne smije sadržavati upit ili fragment."
        }
        require(uri.port == -1 || uri.port in 1..65535) { "Port servera nije valjan." }

        val secureRemote = scheme == "https"
        val privateLanHttp = scheme == "http" && isPrivateLanHost(host)
        require(secureRemote || privateLanHttp) {
            "Adresa servera mora koristiti HTTPS (ili HTTP za privatnu LAN IP adresu)."
        }
        return trimmed
    }

    private fun isPrivateLanHost(host: String): Boolean {
        val normalized = host.lowercase().removePrefix("[").removeSuffix("]")
        if (normalized == "localhost") return true

        // Allow IPv6 loopback, unique-local and link-local addresses only.
        if (normalized.contains(':')) {
            return normalized == "::1" ||
                normalized.startsWith("fc") ||
                normalized.startsWith("fd") ||
                normalized.startsWith("fe8") ||
                normalized.startsWith("fe9") ||
                normalized.startsWith("fea") ||
                normalized.startsWith("feb")
        }

        val octets = normalized.split(".")
        if (octets.size != 4) return false
        val numbers = octets.map { it.toIntOrNull() ?: return false }
        if (numbers.any { it !in 0..255 }) return false

        return numbers[0] == 10 ||
            (numbers[0] == 192 && numbers[1] == 168) ||
            (numbers[0] == 172 && numbers[1] in 16..31) ||
            (numbers[0] == 127 && numbers[1] == 0 && numbers[2] == 0 && numbers[3] == 1) ||
            (numbers[0] == 169 && numbers[1] == 254)
    }

    fun saveBaseUrl(context: Context, value: String) {
        val normalized = normalizeBaseUrl(value)
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putString(SERVER_URL_KEY, normalized)
            .apply()
    }
}
