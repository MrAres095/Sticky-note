package com.mrares095.stickynote

import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

data class KeepRemoteNote(val name: String, val title: String, val text: String, val updateTimeMillis: Long)

object GoogleKeepSync {
    private const val BASE_URL = "https://keep.googleapis.com/v1"

    fun listNotes(accessToken: String): List<KeepRemoteNote> {
        val result = mutableListOf<KeepRemoteNote>()
        var pageToken: String? = null
        do {
            val query = buildString {
                append("?pageSize=100")
                if (!pageToken.isNullOrBlank()) {
                    append("&pageToken=")
                    append(URLEncoder.encode(pageToken, "UTF-8"))
                }
            }
            val json = request("GET", "$BASE_URL/notes$query", accessToken, null)
            val notes = json.optJSONArray("notes") ?: JSONArray()
            for (i in 0 until notes.length()) {
                val note = notes.getJSONObject(i)
                if (!note.optBoolean("trashed", false)) {
                    result += KeepRemoteNote(note.optString("name"), note.optString("title"), extractText(note), parseTimestamp(note.optString("updateTime")))
                }
            }
            pageToken = json.optString("nextPageToken").takeIf { it.isNotBlank() }
        } while (pageToken != null)
        return result
    }

    fun createNote(accessToken: String, title: String, text: String): KeepRemoteNote {
        val body = JSONObject().put("title", title.take(999)).put("body", JSONObject().put("text", JSONObject().put("text", text.take(19999))))
        val note = request("POST", "$BASE_URL/notes", accessToken, body)
        return KeepRemoteNote(note.optString("name"), note.optString("title"), extractText(note), parseTimestamp(note.optString("updateTime")))
    }

    fun deleteNote(accessToken: String, name: String) { request("DELETE", "$BASE_URL/$name", accessToken, null) }

    private fun extractText(note: JSONObject): String {
        val section = note.optJSONObject("body") ?: return ""
        section.optJSONObject("text")?.let { return it.optString("text") }
        val list = section.optJSONObject("list")?.optJSONArray("listItems") ?: return ""
        return buildString {
            for (i in 0 until list.length()) {
                val item = list.getJSONObject(i)
                if (i > 0) append("\n")
                append(if (item.optBoolean("checked", false)) "[x] " else "[ ] ")
                append(item.optJSONObject("text")?.optString("text") ?: "")
            }
        }
    }

    private fun parseTimestamp(value: String): Long = try { java.time.Instant.parse(value).toEpochMilli() } catch (_: Exception) { 0L }

    private fun request(method: String, urlString: String, accessToken: String, body: JSONObject?): JSONObject {
        val connection = (URL(urlString).openConnection() as HttpURLConnection).apply {
            requestMethod = method
            connectTimeout = 15000
            readTimeout = 30000
            setRequestProperty("Authorization", "Bearer $accessToken")
            setRequestProperty("Accept", "application/json")
            if (body != null) {
                doOutput = true
                setRequestProperty("Content-Type", "application/json")
                outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }
            }
        }
        val code = connection.responseCode
        val stream = if (code in 200..299) connection.inputStream else connection.errorStream
        val response = stream?.bufferedReader()?.use { it.readText() }.orEmpty()
        connection.disconnect()
        if (code !in 200..299) throw IllegalStateException("Google Keep HTTP $code: $response")
        return if (response.isBlank()) JSONObject() else JSONObject(response)
    }
}
