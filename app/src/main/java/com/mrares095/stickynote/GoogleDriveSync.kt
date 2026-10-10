package com.mrares095.stickynote

import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

data class DriveSyncState(
    val notes: List<Note>,
    val categories: List<String>,
    val deletedNoteIds: Set<Long> = emptySet(),
    val deletedCategories: Set<String> = emptySet()
)

object GoogleDriveSync {
    private const val BASE_URL = "https://www.googleapis.com/drive/v3"
    private const val UPLOAD_URL = "https://www.googleapis.com/upload/drive/v3"
    private const val FILE_NAME = "sticky-note-sync.json"

    fun downloadState(accessToken: String): DriveSyncState? {
        val query = URLEncoder.encode(
            "name = '$FILE_NAME' and trashed = false",
            "UTF-8"
        )
        val listing = request(
            "GET",
            "$BASE_URL/files?q=$query&fields=files(id,name)",
            accessToken,
            null
        )
        val files = listing.optJSONArray("files") ?: JSONArray()
        if (files.length() == 0) return null

        val fileId = files.getJSONObject(0).optString("id")
        if (fileId.isBlank()) return null

        val content = requestText(
            "GET",
            "$BASE_URL/files/$fileId?alt=media",
            accessToken
        )
        val root = JSONObject(content)
        val notesJson = root.optJSONArray("notes") ?: JSONArray()
        val notes = mutableListOf<Note>()
        for (i in 0 until notesJson.length()) {
            val o = notesJson.getJSONObject(i)
            notes += Note(
                id = o.getLong("id"),
                title = o.optString("title"),
                text = o.optString("text"),
                category = o.optString("category", "Osobno"),
                favorite = o.optBoolean("favorite", false),
                color = o.optLong("color", 0xFF252525),
                pinned = o.optBoolean("pinned", false),
                keepId = o.optString("keepId").takeIf { it.isNotBlank() },
                updatedAt = o.optLong("updatedAt", System.currentTimeMillis()),
                trashed = o.optBoolean("trashed", false),
                reminderAt = o.optLong("reminderAt").takeIf { it > 0L }
            )
        }

        val categoriesJson = root.optJSONArray("categories") ?: JSONArray()
        val categories = List(categoriesJson.length()) { categoriesJson.getString(it) }
            .ifEmpty { listOf("Sve", "Osobno", "Recepti") }

        val deletedJson = root.optJSONArray("deletedNoteIds") ?: JSONArray()
        val deletedNoteIds = (0 until deletedJson.length()).mapNotNull { index ->
            deletedJson.optLong(index).takeIf { it > 0L }
        }.toSet()

        val deletedCategoriesJson = root.optJSONArray("deletedCategories") ?: JSONArray()
        val deletedCategories = (0 until deletedCategoriesJson.length()).mapNotNull { index ->
            deletedCategoriesJson.optString(index).takeIf { it.isNotBlank() }
        }.toSet()

        return DriveSyncState(notes, categories, deletedNoteIds, deletedCategories)
    }

    fun uploadState(
        accessToken: String,
        notes: List<Note>,
        categories: List<String>,
        deletedNoteIds: Set<Long> = emptySet(),
        deletedCategories: Set<String> = emptySet()
    ) {
        val root = JSONObject()
        val notesJson = JSONArray()
        notes.forEach { n ->
            notesJson.put(JSONObject().apply {
                put("id", n.id)
                put("title", n.title)
                put("text", n.text)
                put("category", n.category)
                put("favorite", n.favorite)
                put("color", n.color)
                put("pinned", n.pinned)
                put("keepId", n.keepId ?: "")
                put("updatedAt", n.updatedAt)
                put("trashed", n.trashed)
                put("reminderAt", n.reminderAt ?: 0L)
            })
        }
        val categoriesJson = JSONArray()
        categories.forEach { categoriesJson.put(it) }
        root.put("version", 1)
        root.put("updatedAt", System.currentTimeMillis())
        root.put("notes", notesJson)
        root.put("categories", categoriesJson)
        root.put("deletedNoteIds", JSONArray().apply {
            deletedNoteIds.filter { it > 0L }.sorted().forEach { put(it) }
        })
        root.put("deletedCategories", JSONArray().apply {
            deletedCategories.filter { it.isNotBlank() }.sorted().forEach { put(it) }
        })

        // With drive.file, query files created/opened by this app rather than appDataFolder.
        val query = URLEncoder.encode(
            "name = '$FILE_NAME' and trashed = false",
            "UTF-8"
        )
        val listing = request(
            "GET",
            "$BASE_URL/files?q=$query&fields=files(id)",
            accessToken,
            null
        )
        val files = listing.optJSONArray("files") ?: JSONArray()
        val existingId = if (files.length() > 0) {
            files.getJSONObject(0).optString("id").takeIf { it.isNotBlank() }
        } else {
            null
        }

        if (existingId == null) {
            val metadata = JSONObject()
                .put("name", FILE_NAME)
                .put("mimeType", "application/json")
            val created = request(
                "POST",
                "$BASE_URL/files?fields=id",
                accessToken,
                metadata
            )
            val id = created.optString("id")
            if (id.isBlank()) throw IllegalStateException("Google Drive nije vratio ID datoteke.")
            uploadMedia(accessToken, id, root.toString())
        } else {
            uploadMedia(accessToken, existingId, root.toString())
        }
    }

    private fun uploadMedia(accessToken: String, fileId: String, content: String) {
        val connection = (URL(
            "$UPLOAD_URL/files/$fileId?uploadType=media"
        ).openConnection() as HttpURLConnection).apply {
            requestMethod = "PATCH"
            connectTimeout = 15000
            readTimeout = 30000
            doOutput = true
            setRequestProperty("Authorization", "Bearer $accessToken")
            setRequestProperty("Content-Type", "application/json; charset=utf-8")
            setRequestProperty("Accept", "application/json")
            outputStream.use { it.write(content.toByteArray(Charsets.UTF_8)) }
        }
        val code = connection.responseCode
        val stream = if (code in 200..299) connection.inputStream else connection.errorStream
        val response = stream?.bufferedReader()?.use { it.readText() }.orEmpty()
        connection.disconnect()
        if (code !in 200..299) {
            throw IllegalStateException("Google Drive HTTP $code: $response")
        }
    }

    private fun request(
        method: String,
        urlString: String,
        accessToken: String,
        body: JSONObject?
    ): JSONObject {
        val connection = (URL(urlString).openConnection() as HttpURLConnection).apply {
            requestMethod = method
            connectTimeout = 15000
            readTimeout = 30000
            setRequestProperty("Authorization", "Bearer $accessToken")
            setRequestProperty("Accept", "application/json")
            if (body != null) {
                doOutput = true
                setRequestProperty("Content-Type", "application/json; charset=utf-8")
                outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }
            }
        }
        val code = connection.responseCode
        val stream = if (code in 200..299) connection.inputStream else connection.errorStream
        val response = stream?.bufferedReader()?.use { it.readText() }.orEmpty()
        connection.disconnect()
        if (code !in 200..299) {
            throw IllegalStateException("Google Drive HTTP $code: $response")
        }
        return if (response.isBlank()) JSONObject() else JSONObject(response)
    }

    private fun requestText(
        method: String,
        urlString: String,
        accessToken: String
    ): String {
        val connection = (URL(urlString).openConnection() as HttpURLConnection).apply {
            requestMethod = method
            connectTimeout = 15000
            readTimeout = 30000
            setRequestProperty("Authorization", "Bearer $accessToken")
            setRequestProperty("Accept", "application/json")
        }
        val code = connection.responseCode
        val stream = if (code in 200..299) connection.inputStream else connection.errorStream
        val response = stream?.bufferedReader()?.use { it.readText() }.orEmpty()
        connection.disconnect()
        if (code !in 200..299) {
            throw IllegalStateException("Google Drive HTTP $code: $response")
        }
        return response
    }
}
