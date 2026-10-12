package com.mrares095.stickynote

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

/**
 * Small synchronous HTTP client for use from a worker thread only.
 * Local notes remain the source of truth until explicit sync/merge is implemented.
 */
class NotesServerApi(private val context: Context) {
    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    data class Session(val token: String, val email: String, val userId: String)

    fun savedToken(): String? = prefs.getString(TOKEN_KEY, null)?.takeIf { it.isNotBlank() }
    fun savedEmail(): String = prefs.getString(EMAIL_KEY, "").orEmpty()

    fun clearSession() {
        prefs.edit().remove(TOKEN_KEY).remove(EMAIL_KEY).remove(USER_ID_KEY).apply()
    }

    @Throws(IOException::class)
    fun register(email: String, password: String): Session = authenticate("auth/register", email, password)

    @Throws(IOException::class)
    fun login(email: String, password: String): Session = authenticate("auth/login", email, password)

    @Throws(IOException::class)
    fun testConnection(): JSONObject = request("GET", "health")

    fun verifySession(): JSONObject = request("GET", "auth/me", token = savedToken())

    @Throws(IOException::class)
    fun listCategories(): JSONObject = request("GET", "categories", token = requiredToken())

    @Throws(IOException::class)
    fun listNotes(archived: Boolean = false, trashed: Boolean = false): JSONObject {
        val query = "?archived=$archived&trashed=$trashed"
        return request("GET", "notes$query", token = requiredToken())
    }

    @Throws(IOException::class)
    fun listTodoLists(archived: Boolean = false): JSONObject =
        request("GET", "todos?archived=$archived", token = requiredToken())

    @Throws(IOException::class)
    fun createCategory(name: String, color: String? = null): JSONObject =
        request("POST", "categories", JSONObject().put("name", name).apply {
            if (color != null) put("color", color)
        }, requiredToken())

    @Throws(IOException::class)
    fun createNote(note: JSONObject): JSONObject = request("POST", "notes", note, requiredToken())

    @Throws(IOException::class)
    fun updateNote(serverId: String, patch: JSONObject): JSONObject =
        request("PATCH", "notes/${safeId(serverId)}", patch, requiredToken())

    @Throws(IOException::class)
    fun deleteNote(serverId: String) {
        request("DELETE", "notes/${safeId(serverId)}", token = requiredToken())
    }

    @Throws(IOException::class)
    fun createTodoList(title: String, categoryId: String? = null): JSONObject =
        request("POST", "todos", JSONObject().put("title", title).apply {
            if (categoryId != null) put("categoryId", categoryId)
        }, requiredToken())

    @Throws(IOException::class)
    fun createTask(listId: String, title: String): JSONObject =
        request("POST", "todos/${safeId(listId)}/tasks", JSONObject().put("title", title), requiredToken())

    @Throws(IOException::class)
    fun updateTask(taskId: String, patch: JSONObject): JSONObject =
        request("PATCH", "tasks/${safeId(taskId)}", patch, requiredToken())

    @Throws(IOException::class)
    fun deleteTask(taskId: String) {
        request("DELETE", "tasks/${safeId(taskId)}", token = requiredToken())
    }

    private fun authenticate(path: String, email: String, password: String): Session {
        val result = request("POST", path, JSONObject().put("email", email.trim()).put("password", password))
        val token = result.optString("token")
        val user = result.optJSONObject("user")
            ?: throw IOException("Server nije vratio podatke korisnika.")
        if (token.isBlank()) throw IOException("Server nije vratio token sesije.")
        val session = Session(token, user.optString("email", email.trim()), user.optString("id"))
        prefs.edit()
            .putString(TOKEN_KEY, session.token)
            .putString(EMAIL_KEY, session.email)
            .putString(USER_ID_KEY, session.userId)
            .apply()
        return session
    }

    private fun requiredToken(): String =
        savedToken()?.takeIf { it.length >= 30 } ?: throw IOException("Prvo se prijavi na Sticky & Note server.")

    private fun safeId(value: String): String {
        require(value.isNotBlank() && value.matches(Regex("[A-Za-z0-9_-]+"))) { "Nevaljan ID na serveru." }
        return value
    }

    @Throws(IOException::class)
    private fun request(
        method: String,
        path: String,
        body: JSONObject? = null,
        token: String? = null
    ): JSONObject {
        val url = URL(NotesServerConfig.apiUrl(context, path))
        val connection = (url.openConnection() as HttpURLConnection).apply {
            requestMethod = method
            connectTimeout = 8000
            readTimeout = 12000
            setRequestProperty("Accept", "application/json")
            if (body != null) {
                doOutput = true
                setRequestProperty("Content-Type", "application/json; charset=utf-8")
            }
            if (!token.isNullOrBlank()) setRequestProperty("Authorization", "Bearer $token")
        }
        try {
            if (body != null) connection.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }
            val code = connection.responseCode
            val stream = if (code in 200..299) connection.inputStream else connection.errorStream
            val response = stream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }.orEmpty()
            if (code !in 200..299) {
                val detail = try { JSONObject(response).optString("message").ifBlank { "HTTP $code" } }
                    catch (_: Exception) { "HTTP $code" }
                throw IOException(detail)
            }
            return if (response.isBlank()) JSONObject() else JSONObject(response)
        } catch (e: IllegalArgumentException) {
            throw IOException(e.message ?: "Nevaljan zahtjev.", e)
        } finally {
            connection.disconnect()
        }
    }

    companion object {
        private const val PREFS_NAME = "sticky_note_data"
        private const val TOKEN_KEY = "notes_server_token"
        private const val EMAIL_KEY = "notes_server_email"
        private const val USER_ID_KEY = "notes_server_user_id"
    }
}
