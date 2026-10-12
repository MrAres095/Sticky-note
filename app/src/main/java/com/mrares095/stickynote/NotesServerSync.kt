package com.mrares095.stickynote

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException

/**
 * Explicit, user-triggered merge with the Sticky & Note API.
 * Local data is never cleared by a sync. Matching uses server IDs where known,
 * then stable content keys for data that existed before server sync was added.
 */
internal object NotesServerSync {
    data class Result(
        val notes: List<Note>,
        val categories: List<String>,
        val lists: List<TodoList>,
        val tasks: List<TodoTask>,
        val noteCount: Int,
        val listCount: Int,
        val taskCount: Int
    )

    private const val MAP_PREFS = "sticky_note_data"
    private const val NOTE_MAP = "server_note_id_map"
    private const val LIST_MAP = "server_list_id_map"
    private const val TASK_MAP = "server_task_id_map"

    @Throws(IOException::class)
    fun sync(context: Context, localNotes: List<Note>, localCategories: List<String>,
             localLists: List<TodoList>, localTasks: List<TodoTask>): Result {
        val api = NotesServerApi(context)
        if (api.savedToken().isNullOrBlank()) throw IOException("Prvo se prijavi na Sticky & Note server.")

        val prefs = context.getSharedPreferences(MAP_PREFS, Context.MODE_PRIVATE)
        val noteMap = readMap(prefs.getString(NOTE_MAP, null))
        val listMap = readMap(prefs.getString(LIST_MAP, null))
        val taskMap = readMap(prefs.getString(TASK_MAP, null))

        val categoriesResponse = api.listCategories().optJSONArray("categories") ?: JSONArray()
        val categoryIds = linkedMapOf<String, String>()
        for (i in 0 until categoriesResponse.length()) {
            val item = categoriesResponse.optJSONObject(i) ?: continue
            categoryIds[item.optString("name")] = item.optString("id")
        }
        val mergedCategories = (localCategories + categoryIds.keys).filter { it.isNotBlank() }.distinct()
        for (name in mergedCategories) {
            if (name == "Sve" || name == "🗑 Otpad" || name == "📦 Arhiva") continue
            if (categoryIds[name].isNullOrBlank()) {
                try {
                    val created = api.createCategory(name)
                    val c = created.optJSONObject("category")
                    if (c != null) categoryIds[name] = c.optString("id")
                } catch (e: IOException) {
                    // A concurrent sync may have created this category; reload below.
                    val refreshed = api.listCategories().optJSONArray("categories") ?: JSONArray()
                    for (i in 0 until refreshed.length()) {
                        val c = refreshed.optJSONObject(i) ?: continue
                        categoryIds[c.optString("name")] = c.optString("id")
                    }
                    if (categoryIds[name].isNullOrBlank()) throw e
                }
            }
        }
        val categoryNames = categoryIds.entries.associate { it.value to it.key }

        val remoteNotes = linkedMapOf<String, JSONObject>()
        for (archived in listOf(false, true)) for (trashed in listOf(false, true)) {
            val arr = api.listNotes(archived, trashed).optJSONArray("notes") ?: JSONArray()
            for (i in 0 until arr.length()) {
                val n = arr.optJSONObject(i) ?: continue
                remoteNotes[n.optString("id")] = n
            }
        }
        val mergedNotes = localNotes.toMutableList()
        var noteCount = 0
        for (local in localNotes) {
            val mappedId = noteMap[local.id.toString()]
            val remote = mappedId?.let { remoteNotes[it] } ?: remoteNotes.values.firstOrNull {
                it.optString("title") == local.title && it.optString("text") == local.text &&
                    (categoryNames[it.optString("categoryId")] ?: "Osobno") == local.category &&
                    it.optBoolean("archived") == local.archived && it.optBoolean("trashed") == local.trashed
            }
            if (remote == null) {
                val body = noteBody(local, categoryIds[local.category])
                val created = api.createNote(body).optJSONObject("note")
                    ?: throw IOException("Server nije vratio stvorenu bilješku.")
                val serverId = created.optString("id")
                if (serverId.isBlank()) throw IOException("Bilješka nema ID na serveru.")
                noteMap[local.id.toString()] = serverId
                remoteNotes[serverId] = created
                noteCount++
            } else {
                val serverId = remote.optString("id")
                noteMap[local.id.toString()] = serverId
                if (local.updatedAt > remote.optLong("updatedAt")) {
                    api.updateNote(serverId, noteBody(local, categoryIds[local.category]))
                    noteCount++
                } else {
                    val idx = mergedNotes.indexOfFirst { it.id == local.id }
                    if (idx >= 0) mergedNotes[idx] = localFromRemote(local.id, remote, local.attachmentUri, categoryNames)
                }
            }
        }
        val knownServerNotes = noteMap.values.toSet()
        var nextNoteId = (mergedNotes.maxOfOrNull { it.id } ?: 0L) + 1L
        for (remote in remoteNotes.values) {
            val serverId = remote.optString("id")
            if (serverId in knownServerNotes) continue
            val local = localNotes.firstOrNull {
                it.title == remote.optString("title") && it.text == remote.optString("text") &&
                    it.category == (categoryNames[remote.optString("categoryId")] ?: "Osobno")
            }
            if (local != null) {
                noteMap[local.id.toString()] = serverId
                continue
            }
            mergedNotes.add(localFromRemote(nextNoteId++, remote, null, categoryNames))
            noteMap[(nextNoteId - 1).toString()] = serverId
            noteCount++
        }

        val remoteLists = linkedMapOf<String, JSONObject>()
        for (archived in listOf(false, true)) {
            val arr = api.listTodoLists(archived).optJSONArray("lists") ?: JSONArray()
            for (i in 0 until arr.length()) {
                val list = arr.optJSONObject(i) ?: continue
                remoteLists[list.optString("id")] = list
            }
        }
        val mergedLists = localLists.toMutableList()
        val mergedTasks = localTasks.toMutableList()
        var listCount = 0
        var taskCount = 0
        for (localList in localLists) {
            var remote = listMap[localList.id]?.let { remoteLists[it] }
            if (remote == null) remote = remoteLists.values.firstOrNull {
                it.optString("title") == localList.title &&
                    (categoryNames[it.optString("categoryId")] ?: "Osobno") == localList.category &&
                    it.optBoolean("archived") == localList.archived
            }
            if (remote == null) {
                val created = api.createTodoList(localList.title, categoryIds[localList.category])
                remote = created.optJSONObject("list") ?: throw IOException("Server nije vratio todo listu.")
                remoteLists[remote.optString("id")] = remote
                listCount++
            }
            val serverListId = remote.optString("id")
            listMap[localList.id] = serverListId
            if (remote.optString("title") != localList.title ||
                remote.optBoolean("archived") != localList.archived ||
                (categoryNames[remote.optString("categoryId")] ?: "Osobno") != localList.category) {
                api.updateTodoList(serverListId, localList.title, categoryIds[localList.category], localList.archived)
                listCount++
            }
            val remoteTasks = remote.optJSONArray("tasks") ?: JSONArray()
            val localForList = localTasks.filter { it.listId == localList.id }
            val matchedTaskIds = mutableSetOf<String>()
            for (task in localForList) {
                var remoteTaskId = taskMap[task.id.toString()]
                var rt: JSONObject? = null
                if (!remoteTaskId.isNullOrBlank()) {
                    for (j in 0 until remoteTasks.length()) {
                        val candidate = remoteTasks.optJSONObject(j) ?: continue
                        if (candidate.optString("id") == remoteTaskId) { rt = candidate; break }
                    }
                }
                if (rt == null) {
                    for (j in 0 until remoteTasks.length()) {
                        val candidate = remoteTasks.optJSONObject(j) ?: continue
                        if (candidate.optString("title") == task.title && candidate.optBoolean("completed") == task.completed) {
                            rt = candidate; break
                        }
                    }
                }
                if (rt == null) {
                    val created = api.createTask(serverListId, task.title).optJSONObject("task")
                        ?: throw IOException("Server nije vratio zadatak.")
                    taskMap[task.id.toString()] = created.optString("id")
                    taskCount++
                } else {
                    remoteTaskId = rt.optString("id")
                    taskMap[task.id.toString()] = remoteTaskId
                    matchedTaskIds.add(remoteTaskId)
                    if (rt.optString("title") != task.title || rt.optBoolean("completed") != task.completed) {
                        api.updateTask(remoteTaskId, JSONObject().put("title", task.title).put("completed", task.completed))
                        taskCount++
                    }
                }
            }
            for (j in 0 until remoteTasks.length()) {
                val rt = remoteTasks.optJSONObject(j) ?: continue
                val serverTaskId = rt.optString("id")
                if (serverTaskId in matchedTaskIds || taskMap.values.contains(serverTaskId)) continue
                val localId = (mergedTasks.maxOfOrNull { it.id } ?: 0L) + 1L
                mergedTasks.add(TodoTask(localId, rt.optString("title"), rt.optBoolean("completed"), rt.optLong("reminderAt").takeIf { it > 0L }, null, localList.id))
                taskMap[localId.toString()] = serverTaskId
                taskCount++
            }
        }
        prefs.edit()
            .putString(NOTE_MAP, writeMap(noteMap))
            .putString(LIST_MAP, writeMap(listMap))
            .putString(TASK_MAP, writeMap(taskMap))
            .apply()
        return Result(mergedNotes.distinctBy { it.id }, mergedCategories, mergedLists.distinctBy { it.id },
            mergedTasks.distinctBy { it.id }, noteCount, listCount, taskCount)
    }

    private fun noteBody(n: Note, categoryId: String?): JSONObject = JSONObject()
        .put("title", n.title).put("text", n.text)
        .put("categoryId", categoryId ?: JSONObject.NULL)
        .put("favorite", n.favorite).put("pinned", n.pinned)
        .put("archived", n.archived).put("trashed", n.trashed)
        .put("reminderAt", n.reminderAt ?: JSONObject.NULL)

    private fun localFromRemote(id: Long, n: JSONObject, attachment: String?, categoryNames: Map<String, String>): Note = Note(
        id = id,
        title = n.optString("title"),
        text = n.optString("text"),
        category = categoryNames[n.optString("categoryId")] ?: "Osobno",
        favorite = n.optBoolean("favorite"),
        pinned = n.optBoolean("pinned"),
        archived = n.optBoolean("archived"),
        trashed = n.optBoolean("trashed"),
        reminderAt = n.optLong("reminderAt").takeIf { it > 0L },
        attachmentUri = attachment,
        updatedAt = n.optLong("updatedAt", System.currentTimeMillis())
    )

    private fun readMap(raw: String?): MutableMap<String, String> {
        val result = linkedMapOf<String, String>()
        if (raw.isNullOrBlank()) return result
        try {
            val json = JSONObject(raw)
            val keys = json.keys()
            while (keys.hasNext()) {
                val key = keys.next()
                val value = json.optString(key)
                if (key.isNotBlank() && value.isNotBlank()) result[key] = value
            }
        } catch (_: Exception) { }
        return result
    }

    private fun writeMap(map: Map<String, String>): String = JSONObject(map as Map<*, *>).toString()
}
