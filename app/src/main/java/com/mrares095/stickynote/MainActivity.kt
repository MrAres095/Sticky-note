package com.mrares095.stickynote

import android.app.PendingIntent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.app.AlarmManager
import android.app.DatePickerDialog
import android.app.TimePickerDialog
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.BroadcastReceiver
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.content.Context
import android.content.IntentSender
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.lazy.grid.*
import androidx.compose.foundation.clickable
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.google.android.gms.auth.api.identity.AuthorizationRequest
import com.google.android.gms.auth.api.identity.AuthorizationResult
import com.google.android.gms.auth.api.identity.Identity
import com.google.android.gms.common.api.Scope
import org.json.JSONArray
import org.json.JSONObject
import kotlin.concurrent.thread
import kotlinx.coroutines.delay
import java.util.zip.ZipInputStream

data class TodoTask(
    val id: Long,
    val title: String,
    val completed: Boolean = false,
    val reminderAt: Long? = null,
    val imageUri: String? = null,
    val listId: String = "default"
)

data class TodoList(val id: String, val title: String, val category: String = "Osobno", val archived: Boolean = false)

const val TASKS = "todo_tasks"
private const val TODO_LISTS = "todo_lists"
private const val DEFAULT_TODO_LIST_ID = "default"

private fun loadTodoLists(context: Context): List<TodoList> {
    val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    val raw = prefs.getString(TODO_LISTS, null)
    if (raw.isNullOrBlank()) {
        val legacyTitle = prefs.getString("todo_list_title", null)?.trim().orEmpty()
        val initial = listOf(TodoList(DEFAULT_TODO_LIST_ID, legacyTitle.ifBlank { "Moj prvi popis" }, prefs.getString("selected", "Osobno")?.takeIf { it != "Sve" } ?: "Osobno"))
        saveTodoLists(context, initial)
        return initial
    }
    return try {
        val a = JSONArray(raw)
        val result = List(a.length()) { i ->
            val o = a.getJSONObject(i)
            TodoList(o.optString("id").ifBlank { DEFAULT_TODO_LIST_ID }, o.optString("title").ifBlank { "To-do lista" }, o.optString("category").ifBlank { "Osobno" }, o.optBoolean("archived", false))
        }.distinctBy { it.id }
        result.ifEmpty { listOf(TodoList(DEFAULT_TODO_LIST_ID, "Moj prvi popis")) }
    } catch (_: Exception) {
        listOf(TodoList(DEFAULT_TODO_LIST_ID, "Moj prvi popis"))
    }
}

private fun saveTodoLists(context: Context, lists: List<TodoList>) {
    val a = JSONArray()
    lists.forEach { a.put(JSONObject().put("id", it.id).put("title", it.title).put("category", it.category).put("archived", it.archived)) }
    context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(TODO_LISTS, a.toString()).apply()
    NoteListWidgetProvider.updateAll(context)
}

private fun decodeTasks(raw: String): List<TodoTask> {
    val a = JSONArray(raw)
    return List(a.length()) { i ->
        val o = a.getJSONObject(i)
        TodoTask(
            o.getLong("id"),
            o.getString("title"),
            o.optBoolean("completed", false),
            o.optLong("reminderAt").takeIf { it > 0L },
            o.optString("imageUri").takeIf { it.isNotBlank() && it != "null" },
            o.optString("listId").ifBlank { DEFAULT_TODO_LIST_ID }
        )
    }
}

private fun loadTasks(context: Context): List<TodoTask> {
    val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    val raw = prefs.getString(TASKS, null) ?: return emptyList()
    return try {
        decodeTasks(raw)
    } catch (_: Exception) {
        val backup = prefs.getString("${TASKS}_backup", null)
        prefs.edit().putString("${TASKS}_corrupt_backup", raw).apply()
        if (backup != null) {
            try {
                decodeTasks(backup).also { prefs.edit().putString(TASKS, backup).apply() }
            } catch (_: Exception) {
                emptyList()
            }
        } else {
            emptyList()
        }
    }
}

private fun saveTasks(context: Context, tasks: List<TodoTask>) {
    val a = JSONArray()
    tasks.forEach { task -> a.put(JSONObject().apply {
        put("id", task.id); put("title", task.title); put("completed", task.completed); put("reminderAt", task.reminderAt ?: 0L); put("imageUri", task.imageUri ?: ""); put("listId", task.listId)
    }) }
    val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    val current = prefs.getString(TASKS, null)
    val edit = prefs.edit()
    if (current != null) edit.putString("${TASKS}_backup", current)
    edit.putString(TASKS, a.toString()).apply()
    NoteListWidgetProvider.updateAll(context)
}

private fun loadTaskThumbnail(context: Context, uriString: String): Bitmap? {
    return try {
        val uri = Uri.parse(uriString)
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        context.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
        var sample = 1
        while (bounds.outWidth / sample > 600 || bounds.outHeight / sample > 600) sample *= 2
        val options = BitmapFactory.Options().apply { inSampleSize = sample.coerceAtLeast(1) }
        context.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, options) }
    } catch (_: Exception) { null }
}

private fun scheduleReminder(context: Context, task: TodoTask) {
    val at = task.reminderAt ?: return
    if (at <= System.currentTimeMillis()) return
    val intent = Intent(context, ReminderReceiver::class.java).putExtra("task_id", task.id).putExtra("task_title", task.title)
    val pending = PendingIntent.getBroadcast(context, task.id.hashCode(), intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
    val alarms = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
    alarms.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pending)
}

private fun cancelReminder(context: Context, taskId: Long) {
    val intent = Intent(context, ReminderReceiver::class.java)
    val pending = PendingIntent.getBroadcast(context, taskId.hashCode(), intent, PendingIntent.FLAG_NO_CREATE or PendingIntent.FLAG_IMMUTABLE)
    if (pending != null) (context.getSystemService(Context.ALARM_SERVICE) as AlarmManager).cancel(pending)
}

private fun cancelNoteReminder(context: Context, noteId: Long) {
    val intent = Intent(context, ReminderReceiver::class.java).putExtra("note_id", noteId)
    val pending = PendingIntent.getBroadcast(
        context, noteId.hashCode() xor 0x4E4F5445, intent,
        PendingIntent.FLAG_NO_CREATE or PendingIntent.FLAG_IMMUTABLE
    )
    if (pending != null) (context.getSystemService(Context.ALARM_SERVICE) as AlarmManager).cancel(pending)
}

private fun scheduleNoteReminder(context: Context, note: Note) {
    val at = note.reminderAt ?: return
    if (at <= System.currentTimeMillis()) return
    val intent = Intent(context, ReminderReceiver::class.java)
        .putExtra("note_id", note.id)
        .putExtra("note_title", note.title.ifBlank { "Bez naslova" })
    val pending = PendingIntent.getBroadcast(
        context, note.id.hashCode() xor 0x4E4F5445, intent,
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
    )
    val alarms = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
    alarms.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pending)
}


data class Note(
    val id: Long,
    val title: String,
    val text: String,
    val category: String,
    val favorite: Boolean = false,
    val color: Long = 0xFF252525,
    val pinned: Boolean = false,
    val keepId: String? = null,
    val updatedAt: Long = System.currentTimeMillis(),
    val trashed: Boolean = false,
    val reminderAt: Long? = null,
    val attachmentUri: String? = null,
    val archived: Boolean = false
)

data class MergedDriveState(
    val notes: List<Note>,
    val categories: List<String>,
    val deletedNoteIds: Set<Long>,
    val deletedCategories: Set<String>
)

const val PREFS = "sticky_note_data"
const val NOTES = "notes"
const val CATEGORIES = "categories"
private const val DELETED_NOTE_IDS = "drive_deleted_note_ids"
private const val DELETED_CATEGORIES = "drive_deleted_categories"
private const val DRIVE_SCOPE = "https://www.googleapis.com/auth/drive.file"

private fun decodeCategories(raw: String): List<String> {
    val a = JSONArray(raw)
    return List(a.length()) { a.getString(it) }.ifEmpty { listOf("Sve") }
}

private fun loadCategories(context: Context): List<String> {
    val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    val raw = prefs.getString(CATEGORIES, null) ?: return listOf("Sve", "Osobno", "Recepti")
    return try {
        decodeCategories(raw)
    } catch (_: Exception) {
        val backup = prefs.getString("${CATEGORIES}_backup", null)
        prefs.edit().putString("${CATEGORIES}_corrupt_backup", raw).apply()
        if (backup != null) {
            try {
                decodeCategories(backup).also { prefs.edit().putString(CATEGORIES, backup).apply() }
            } catch (_: Exception) {
                listOf("Sve", "Osobno", "Recepti")
            }
        } else {
            listOf("Sve", "Osobno", "Recepti")
        }
    }
}

private fun saveCategories(context: Context, values: List<String>) {
    val a = JSONArray()
    values.forEach { a.put(it) }
    val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    val current = prefs.getString(CATEGORIES, null)
    val edit = prefs.edit()
    if (current != null) edit.putString("${CATEGORIES}_backup", current)
    edit.putString(CATEGORIES, a.toString()).apply()
}

private fun loadDeletedNoteIds(context: Context): Set<Long> =
    context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        .getStringSet(DELETED_NOTE_IDS, emptySet())
        .orEmpty()
        .mapNotNull { it.toLongOrNull()?.takeIf { id -> id > 0L } }
        .toSet()

private fun saveDeletedNoteIds(context: Context, ids: Set<Long>) {
    context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        .edit()
        .putStringSet(DELETED_NOTE_IDS, ids.filter { it > 0L }.map { it.toString() }.toSet())
        .apply()
}

private fun loadDeletedCategories(context: Context): Set<String> =
    context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        .getStringSet(DELETED_CATEGORIES, emptySet())
        .orEmpty()
        .filter { it.isNotBlank() && it != "Sve" }
        .toSet()

private fun saveDeletedCategories(context: Context, values: Set<String>) {
    context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        .edit()
        .putStringSet(DELETED_CATEGORIES, values.filter { it.isNotBlank() && it != "Sve" }.toSet())
        .apply()
}

private fun decodeNotes(raw: String): List<Note> {
    val a = JSONArray(raw)
    return List(a.length()) {
        val o = a.getJSONObject(it)
        Note(
            o.getLong("id"),
            o.getString("title"),
            o.getString("text"),
            o.getString("category"),
            o.optBoolean("favorite", false),
            o.optLong("color", 0xFF252525),
            o.optBoolean("pinned", false),
            o.optString("keepId").takeIf { value -> value.isNotBlank() },
            o.optLong("updatedAt", System.currentTimeMillis()),
            o.optBoolean("trashed", o.optString("category") == "🗑 Otpad"),
            o.optLong("reminderAt").takeIf { it > 0L },
            o.optString("attachmentUri").takeIf { it.isNotBlank() && it != "null" },
            o.optBoolean("archived", false)
        )
    }
}

private fun loadNotes(context: Context): List<Note> {
    val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    val raw = prefs.getString(NOTES, null)
        ?: return listOf(Note(1L, "Dobrodošli", "Ovo je tvoja nova Sticky & Note bilješka.", "Osobno"))
    return try {
        decodeNotes(raw)
    } catch (_: Exception) {
        val backup = prefs.getString("${NOTES}_backup", null)
        prefs.edit().putString("${NOTES}_corrupt_backup", raw).apply()
        if (backup != null) {
            try {
                decodeNotes(backup).also { prefs.edit().putString(NOTES, backup).apply() }
            } catch (_: Exception) {
                emptyList()
            }
        } else {
            emptyList()
        }
    }
}

private fun saveNotes(context: Context, values: List<Note>) {
    val a = JSONArray()
    values.forEach { n ->
        a.put(JSONObject().apply {
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
            put("attachmentUri", n.attachmentUri ?: "")
            put("archived", n.archived)
        })
    }
    val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    val current = prefs.getString(NOTES, null)
    val edit = prefs.edit()
    if (current != null) edit.putString("${NOTES}_backup", current)
    edit.putString(NOTES, a.toString()).apply()
    NoteWidgetProvider.updateAll(context)
    NoteListWidgetProvider.updateAll(context)
}

class MainActivity : ComponentActivity() {
    private var pendingOpenNoteId: Long? by mutableStateOf(null)
    private var pendingOpenTodoListId: String? by mutableStateOf(null)
    private var driveAccessToken: String? by mutableStateOf(null)
    private var appRefreshKey by mutableIntStateOf(0)
    private var driveStatusMessage by mutableStateOf<String?>(null)
    private var syncAfterAuthorization = false

    private val keepTakeoutLauncher =
        registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            if (uri != null) importKeepTakeout(uri)
        }

    private val authorizationLauncher =
        registerForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) { result ->
            if (result.resultCode == RESULT_OK && result.data != null) {
                try {
                    val authorizationResult = Identity.getAuthorizationClient(this)
                        .getAuthorizationResultFromIntent(result.data!!)
                    handleAuthorizationResult(authorizationResult)
                } catch (e: Exception) {
                    showDriveMessage("Google autorizacija nije uspjela (${e.javaClass.simpleName}): ${e.message ?: "nema detalja"}")
                }
            } else {
                showDriveMessage("Google autorizacija nije dovršena (resultCode=${result.resultCode}). Pokušaj ponovno; ako se ponovi, pošalji mi točnu poruku.")
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        pendingOpenNoteId = intent.getLongExtra("open_note_id", -1L).takeIf { it > 0L }
        pendingOpenTodoListId = intent.getStringExtra("open_todo_list_id")
        setContent {
            key(appRefreshKey) {
            StickyNoteApp(
                context = this,
                driveConnected = driveAccessToken != null,
                message = driveStatusMessage,
                onDismissMessage = { driveStatusMessage = null },
                onConnectDrive = { authorizeDrive(false) },
                onSyncDrive = { authorizeDrive(true) },
                onImportKeep = { keepTakeoutLauncher.launch(arrayOf("application/zip", "application/octet-stream", "application/x-zip-compressed")) },
                onCheckUpdate = { checkForUpdate() },
                openNoteId = pendingOpenNoteId,
                onOpenNoteHandled = { pendingOpenNoteId = null },
                openTodoListId = pendingOpenTodoListId,
                onOpenTodoListHandled = { pendingOpenTodoListId = null }
            )
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        pendingOpenNoteId = intent.getLongExtra("open_note_id", -1L).takeIf { it > 0L }
        pendingOpenTodoListId = intent.getStringExtra("open_todo_list_id")
    }

    private fun importKeepTakeout(uri: Uri) {
        try {
            contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
        } catch (_: Exception) {
            // Some document providers do not support persistable grants; the import is immediate.
        }
        showDriveMessage("Čitam Google Keep ZIP…")
        thread {
            try {
                val existing = loadNotes(this)
                val result = GoogleKeepTakeoutImporter.read(this, uri, existing)
                val imported = result.notes
                val categories = (loadCategories(this) + imported.map { it.category })
                    .distinct()
                runOnUiThread {
                    if (imported.isNotEmpty()) {
                        saveNotes(this, existing + imported)
                        saveCategories(this, categories)
                        appRefreshKey++
                    }
                    val errorSummary = if (result.errors.isEmpty()) "" else
                        "\\nNepročitane datoteke: ${result.errors.size}."
                    showDriveMessage(
                        "Google Keep uvoz završen.\\n" +
                            "Uvezeno: ${imported.size}.\\n" +
                            "Preskočeno (duplikati/prazne datoteke): ${result.skippedFiles}." +
                            errorSummary +
                            "\\n\\nPostojeće bilješke nisu obrisane. Slike i privici još se ne prenose."
                    )
                }
            } catch (e: Exception) {
                runOnUiThread {
                    showDriveMessage("Google Keep uvoz nije uspio: ${e.message ?: e.javaClass.simpleName}")
                }
            }
        }
    }

    private fun authorizeDrive(syncAfter: Boolean) {
        syncAfterAuthorization = syncAfter
        val request = AuthorizationRequest.builder()
            .setRequestedScopes(listOf(Scope(DRIVE_SCOPE)))
            .build()

        Identity.getAuthorizationClient(this)
            .authorize(request)
            .addOnSuccessListener { handleAuthorizationResult(it) }
            .addOnFailureListener { error ->
                showDriveMessage("Google autorizacija nije dostupna (${error.javaClass.simpleName}): ${error.message ?: "nema detalja"}")
            }
    }

    private fun handleAuthorizationResult(result: AuthorizationResult) {
        if (result.hasResolution()) {
            val pendingIntent: PendingIntent? = result.pendingIntent
            if (pendingIntent == null) {
                showDriveMessage("Google nije ponudio autorizaciju.")
                return
            }
            authorizationLauncher.launch(
                androidx.activity.result.IntentSenderRequest.Builder(pendingIntent.intentSender).build()
            )
        } else {
            driveAccessToken = result.accessToken
            showDriveMessage("Google Drive je povezan.")
            if (syncAfterAuthorization) {
                syncAfterAuthorization = false
                syncDrive()
            }
        }
    }

    private fun syncDrive() {
        val token = driveAccessToken ?: run {
            showDriveMessage("Prvo poveži Google Drive.")
            return
        }
        showDriveMessage("Sinkronizacija s Google Driveom...")
        val current = loadNotes(this)
        val currentCategories = loadCategories(this)
        val currentDeletedNoteIds = loadDeletedNoteIds(this)
        val currentDeletedCategories = loadDeletedCategories(this)

        thread {
            try {
                val remote = GoogleDriveSync.downloadState(token)
                val merged: MergedDriveState = if (remote == null) {
                    GoogleDriveSync.uploadState(token, current, currentCategories, currentDeletedNoteIds, currentDeletedCategories)
                    MergedDriveState(current, currentCategories, currentDeletedNoteIds, currentDeletedCategories)
                } else {
                    val prefs = getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                    val firstSync = !prefs.getBoolean("drive_sync_initialized", false)
                    val deletedNoteIds = currentDeletedNoteIds + remote.deletedNoteIds
                    val deletedCategories = currentDeletedCategories + remote.deletedCategories

                    val localIsFreshInstall = current.size == 1 &&
                        current.first().id == 1L &&
                        current.first().title == "Dobrodošli" &&
                        current.first().text.contains("Sticky & Note")

                    val notes = DriveStateMerger.mergeNotes(
                        localNotes = current,
                        remoteNotes = remote.notes,
                        preferRemoteOnFirstSync = firstSync && localIsFreshInstall,
                        deletedNoteIds = deletedNoteIds
                    )
                    val categories = DriveStateMerger.mergeCategories(
                        localCategories = currentCategories,
                        remoteCategories = remote.categories,
                        deletedCategories = deletedCategories
                    )

                    GoogleDriveSync.uploadState(token, notes, categories, deletedNoteIds, deletedCategories)
                    MergedDriveState(notes, categories, deletedNoteIds, deletedCategories)
                }

                runOnUiThread {
                    saveNotes(this, merged.notes)
                    saveDeletedNoteIds(this, merged.deletedNoteIds)
                    saveDeletedCategories(this, merged.deletedCategories)
                    // Reconcile Android alarms after a Drive download/merge. A reminder can
                    // arrive from another device, be moved, or have been trashed remotely.
                    val reminderNow = System.currentTimeMillis()
                    merged.notes.forEach { note ->
                        if (!note.trashed && note.reminderAt != null && note.reminderAt > reminderNow) {
                            scheduleNoteReminder(this, note)
                        } else {
                            cancelNoteReminder(this, note.id)
                        }
                    }
                    saveCategories(this, merged.categories)
                    getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                        .edit()
                        .putBoolean("drive_sync_initialized", true)
                        .apply()
                    NoteWidgetProvider.updateAll(this)
                    showDriveMessage("Google Drive sinkronizacija završena. " + merged.notes.size + " bilješki.")
                }
            } catch (e: Exception) {
                runOnUiThread {
                    showDriveMessage("Google Drive greška: " + (e.message ?: "nepoznata greška"))
                }
            }
        }
    }

    private fun checkForUpdate() {
        showDriveMessage("Provjeravam ažuriranje…")
        UpdateManager.check(this) { message, apkUrl ->
            runOnUiThread {
                if (apkUrl != null) {
                    driveStatusMessage = message + " Preuzimam…"
                    UpdateManager.downloadAndInstall(this, apkUrl) { status -> showDriveMessage(status) }
                } else {
                    driveStatusMessage = message
                }
            }
        }
    }

    private fun showDriveMessage(message: String) {
        runOnUiThread { driveStatusMessage = message }
    }
}


@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun StickyNoteApp(
    context: Context,
    driveConnected: Boolean,
    message: String?,
    onDismissMessage: () -> Unit,
    onConnectDrive: () -> Unit,
    onSyncDrive: () -> Unit,
    onImportKeep: () -> Unit,
    onCheckUpdate: () -> Unit,
    openNoteId: Long? = null,
    onOpenNoteHandled: () -> Unit = {},
    openTodoListId: String? = null,
    onOpenTodoListHandled: () -> Unit = {}
) {
    var categories by remember { mutableStateOf(loadCategories(context)) }
    var selected by remember { mutableStateOf(categories.first()) }
    var notes by remember { mutableStateOf(loadNotes(context)) }
    var editing by remember { mutableStateOf<Note?>(null) }
    var showAddTab by remember { mutableStateOf(false) }
    var newTabName by remember { mutableStateOf("") }
    var showRenameTab by remember { mutableStateOf<String?>(null) }
    var renameText by remember { mutableStateOf("") }
    var search by remember { mutableStateOf("") }
    var favoritesOnly by remember { mutableStateOf(false) }
    var pinnedOnly by remember { mutableStateOf(false) }
    var trashOnly by remember { mutableStateOf(false) }
    var archiveOnly by remember { mutableStateOf(false) }
    var showMoreFilters by remember { mutableStateOf(false) }
    var confirmPermanentDelete by remember { mutableStateOf<Note?>(null) }
    var undoNotesSnapshot by remember { mutableStateOf<List<Note>?>(null) }
    var undoTasksSnapshot by remember { mutableStateOf<List<TodoTask>?>(null) }
    var confirmEmptyTrash by remember { mutableStateOf(false) }
    var confirmDeleteCategory by remember { mutableStateOf<String?>(null) }
    var showTasks by remember { mutableStateOf(context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean("show_tasks_screen", false)) }
    var showServerSettings by remember { mutableStateOf(false) }
    var serverUrlDraft by remember { mutableStateOf(NotesServerConfig.DEFAULT_BASE_URL) }
    var serverConfigError by remember { mutableStateOf<String?>(null) }
    var serverEmailDraft by remember { mutableStateOf(context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString("notes_server_email", "").orEmpty()) }
    var serverPasswordDraft by remember { mutableStateOf("") }
    var serverAuthBusy by remember { mutableStateOf(false) }
    var serverAuthStatus by remember { mutableStateOf<String?>(null) }
    var tasks by remember { mutableStateOf(loadTasks(context).map { task -> if (task.listId == DEFAULT_TODO_LIST_ID) task else task }) }
    var todoLists by remember { mutableStateOf(loadTodoLists(context)) }
    var selectedTodoListId by remember {
        mutableStateOf(context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString("selected_todo_list_id", DEFAULT_TODO_LIST_ID) ?: DEFAULT_TODO_LIST_ID)
    }
    var showTodoListPicker by remember { mutableStateOf(false) }
    var showCreateTodoList by remember { mutableStateOf(false) }
    var newTodoListTitle by remember { mutableStateOf("") }
    var newTodoListCategory by remember { mutableStateOf(if (selected == "Sve") categories.firstOrNull { it != "Sve" } ?: "Osobno" else selected) }
    var editingTodoList by remember { mutableStateOf<TodoList?>(null) }
    var confirmDeleteTodoList by remember { mutableStateOf<TodoList?>(null) }
    var editTodoListTitle by remember { mutableStateOf("") }
    var editTodoListCategory by remember { mutableStateOf("Osobno") }
    var activeTodoList by remember { mutableStateOf<TodoList?>(null) }
    val activeTasks = tasks.filter { it.listId == selectedTodoListId }
    var showRenameTaskList by remember { mutableStateOf(false) }
    var taskListTitle by remember { mutableStateOf("") }
    var taskListTitleDraft by remember { mutableStateOf("") }
    var inlineTaskDraft by remember { mutableStateOf("") }
    BackHandler(enabled = showTasks && editing == null) {
        showTasks = false
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putBoolean("show_tasks_screen", false).apply()
    }
    var showAddTask by remember { mutableStateOf(false) }
    var showCreateChooser by remember { mutableStateOf(false) }
    var taskTitle by remember { mutableStateOf("") }
    var taskReminderAt by remember { mutableStateOf<Long?>(null) }
    var taskReminderPermissionDenied by remember { mutableStateOf(false) }
    var taskImageUri by remember { mutableStateOf<String?>(null) }
    val taskImagePicker = androidx.activity.compose.rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            try { context.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION) } catch (_: Exception) { }
            taskImageUri = uri.toString()
        }
    }

    fun showTaskReminderPicker() {
        val now = java.util.Calendar.getInstance()
        DatePickerDialog(context, { _, year, month, day ->
            val chosen = java.util.Calendar.getInstance().apply { set(year, month, day) }
            TimePickerDialog(context, { _, hour, minute ->
                chosen.set(java.util.Calendar.HOUR_OF_DAY, hour)
                chosen.set(java.util.Calendar.MINUTE, minute)
                chosen.set(java.util.Calendar.SECOND, 0)
                chosen.set(java.util.Calendar.MILLISECOND, 0)
                if (chosen.timeInMillis > System.currentTimeMillis()) {
                    taskReminderAt = chosen.timeInMillis
                    taskReminderPermissionDenied = false
                } else {
                    taskReminderAt = null
                    android.widget.Toast.makeText(context, "Odaberi vrijeme u budućnosti.", android.widget.Toast.LENGTH_SHORT).show()
                }
            }, now.get(java.util.Calendar.HOUR_OF_DAY), now.get(java.util.Calendar.MINUTE), true).show()
        }, now.get(java.util.Calendar.YEAR), now.get(java.util.Calendar.MONTH), now.get(java.util.Calendar.DAY_OF_MONTH)).show()
    }

    val taskReminderPermissionLauncher = androidx.activity.compose.rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        taskReminderPermissionDenied = !granted
        if (granted) showTaskReminderPicker()
    }

    LaunchedEffect(openNoteId, notes) {
        if (openNoteId != null && editing == null) {
            val target = notes.firstOrNull { it.id == openNoteId }
            if (target != null) {
                editing = target
                onOpenNoteHandled()
            }
        }
    }

    LaunchedEffect(openTodoListId, todoLists) {
        val requestedId = openTodoListId ?: return@LaunchedEffect
        val target = todoLists.firstOrNull { it.id == requestedId }
        if (target != null) {
            selectedTodoListId = target.id
            selected = target.category
            showTasks = true
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                .putString("selected_todo_list_id", target.id)
                .putBoolean("show_tasks_screen", true).apply()
            onOpenTodoListHandled()
        }
    }

    fun persistNotes(v: List<Note>) {
        notes = v
        saveNotes(context, v)
        NoteWidgetProvider.updateAll(context)
    }

    fun deleteNote(note: Note) {
        undoNotesSnapshot = notes
        cancelNoteReminder(context, note.id)
        val now = System.currentTimeMillis()
        persistNotes(notes.map { if (it.id == note.id) it.copy(trashed = true, updatedAt = now) else it })
    }

    fun restoreNote(note: Note) {
        val now = System.currentTimeMillis()
        val restored = notes.map {
            if (it.id == note.id) it.copy(
                category = if (it.category == "🗑 Otpad" || it.category == "Sve") "Osobno" else it.category,
                trashed = false,
                updatedAt = now
            ) else it
        }
        persistNotes(restored)
        restored.firstOrNull { it.id == note.id }?.let { restoredNote ->
            restoredNote.reminderAt?.let { scheduleNoteReminder(context, restoredNote) }
        }
    }

    fun permanentlyDeleteNote(note: Note) {
        cancelNoteReminder(context, note.id)
        saveDeletedNoteIds(context, loadDeletedNoteIds(context) + note.id)
        persistNotes(notes.filterNot { it.id == note.id })
    }

    fun emptyTrash() {
        val removedIds = notes.filter { it.trashed }.map { it.id }.toSet()
        removedIds.forEach { cancelNoteReminder(context, it) }
        saveDeletedNoteIds(context, loadDeletedNoteIds(context) + removedIds)
        persistNotes(notes.filterNot { it.trashed })
    }

    fun persistCategories(v: List<String>) {
        categories = v
        saveCategories(context, v)
        if (!v.contains(selected)) selected = v.first()
    }

    fun deleteCategory(category: String) {
        if (category == "Sve") return
        saveDeletedCategories(context, loadDeletedCategories(context) + category)
        val fallback = categories.firstOrNull { it != "Sve" && it != category } ?: "Osobno"
        val nextCategories = if (fallback == "Osobno" && !categories.contains("Osobno")) {
            categories.filterNot { it == category } + "Osobno"
        } else {
            categories.filterNot { it == category }
        }
        persistCategories(nextCategories)
        persistNotes(notes.map {
            if (it.category == category) it.copy(category = fallback, updatedAt = System.currentTimeMillis()) else it
        })
        selected = fallback
        trashOnly = false
    }

    MaterialTheme(colorScheme = darkColorScheme()) {
        Scaffold(
            topBar = {
                TopAppBar(
                    title = { Text("Sticky & Note") },
                    actions = {
                        
                        TextButton(onClick = onConnectDrive) { Text(if (driveConnected) "Google Drive ✓" else "Google Drive") }
                        TextButton(onClick = onSyncDrive) { Text("Sync") }
                        TextButton(onClick = onImportKeep) { Text("Uvoz Keep") }
                        TextButton(onClick = {
                            serverUrlDraft = NotesServerConfig.baseUrl(context)
                            serverConfigError = null
                            showServerSettings = true
                        }) { Text("⚙") }
                    }
                )
            },
            floatingActionButton = {
                FloatingActionButton(onClick = { showCreateChooser = true }) { Text("+") }
            }
        ) { padding ->
            Column(Modifier.padding(padding).fillMaxSize()) {
                if (showTasks) {
                    val todoTabIndex = categories.indexOf(selected).coerceIn(0, (categories.size - 1).coerceAtLeast(0))
                    // The To-do screen only shows category tabs and a + tab; a list index here can exceed the tab count and crash Compose.
                    ScrollableTabRow(selectedTabIndex = todoTabIndex) {
                        categories.forEach { category ->
                            Tab(selected = false, onClick = {
                                selected = category
                                showTasks = false
                                context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                                    .putBoolean("show_tasks_screen", false).apply()
                            }, text = { Text(category) })
                        }
                        Tab(selected = false, onClick = {
                            newTodoListTitle = ""
                            newTodoListCategory = if (selected == "Sve") categories.firstOrNull { it != "Sve" } ?: "Osobno" else selected
                            showCreateTodoList = true
                        }, text = { Text("+") })
                    }
                    Row(Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = androidx.compose.ui.Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
                        Column(Modifier.weight(1f)) {
                            TextButton(
                                onClick = { showTodoListPicker = true },
                                contentPadding = PaddingValues(0.dp)
                            ) {
                                Text(todoLists.firstOrNull { it.id == selectedTodoListId }?.title ?: "Odaberi popis", style = MaterialTheme.typography.headlineSmall, color = MaterialTheme.colorScheme.onSurface)
                            }
                            Text("${activeTasks.count { !it.completed }} preostalo · ${activeTasks.count { it.completed }} dovršeno", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        if (activeTasks.any { it.completed }) {
                            TextButton(onClick = {
                                activeTasks.filter { it.completed }.forEach { cancelReminder(context, it.id) }
                                undoTasksSnapshot = tasks
                                val removedIds = activeTasks.filter { it.completed }.map { it.id }.toSet()
                                val remaining = tasks.filterNot { it.id in removedIds }
                                tasks = remaining
                                saveTasks(context, remaining)
                            }) { Text("Ukloni dovršene") }
                        }
                        TextButton(onClick = {
                            showTasks = false
                            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                                .edit().putBoolean("show_tasks_screen", false).apply()
                        }) { Text("✕ Bilješke") }
                    }
                    androidx.compose.foundation.lazy.LazyColumn(Modifier.fillMaxSize().padding(horizontal = 18.dp)) {
                        item {
                            Spacer(Modifier.height(8.dp))
                            Text(
                                "Piši zadatke izravno u retke, kao u bilješci. Svaka promjena automatski se sprema.",
                                modifier = Modifier.fillMaxWidth().padding(bottom = 10.dp),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        if (activeTasks.isEmpty()) {
                            item { Text("Dodaj prvu stavku popisa.", Modifier.padding(vertical = 12.dp), color = MaterialTheme.colorScheme.onSurfaceVariant) }
                        }
                        items(activeTasks.sortedWith(compareBy<TodoTask> { it.completed }.thenBy { it.reminderAt ?: Long.MAX_VALUE }), key = { it.id }) { task ->
                            Column(Modifier.fillMaxWidth()) {
                                Row(
                                    Modifier.fillMaxWidth().padding(vertical = 7.dp),
                                    verticalAlignment = androidx.compose.ui.Alignment.CenterVertically
                                ) {
                                    Text("⠿", modifier = Modifier.padding(end = 10.dp), color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.titleLarge)
                                    Checkbox(checked = task.completed, onCheckedChange = { checked ->
                                        val updated = tasks.map { if (it.id == task.id) it.copy(completed = checked) else it }
                                        tasks = updated
                                        saveTasks(context, updated)
                                        if (checked) cancelReminder(context, task.id) else task.reminderAt?.let { scheduleReminder(context, task) }
                                    })
                                    Column(Modifier.weight(1f).padding(start = 8.dp)) {
                                        OutlinedTextField(
                                            value = task.title,
                                            onValueChange = { newTitle ->
                                                val updated = tasks.map { if (it.id == task.id) it.copy(title = newTitle) else it }
                                                tasks = updated
                                                saveTasks(context, updated)
                                                if (task.reminderAt != null) {
                                                    cancelReminder(context, task.id)
                                                    updated.firstOrNull { it.id == task.id }?.let { scheduleReminder(context, it) }
                                                }
                                            },
                                            modifier = Modifier.fillMaxWidth(),
                                            textStyle = MaterialTheme.typography.bodyLarge.copy(
                                                color = if (task.completed) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface
                                            ),
                                            placeholder = { Text("Napiši zadatak…") },
                                            singleLine = true
                                        )
                                        task.reminderAt?.let { at ->
                                            Text("◷ " + java.text.SimpleDateFormat("dd.MM.yyyy. HH:mm", java.util.Locale.getDefault()).format(java.util.Date(at)), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
                                        }
                                        task.imageUri?.let { uri ->
                                            val thumbnail = remember(uri) { loadTaskThumbnail(context, uri) }
                                            if (thumbnail != null) {
                                                Image(
                                                    bitmap = thumbnail.asImageBitmap(),
                                                    contentDescription = "Slika zadatka",
                                                    modifier = Modifier.fillMaxWidth().heightIn(max = 220.dp).padding(top = 8.dp),
                                                    contentScale = ContentScale.Crop
                                                )
                                            }
                                        }
                                    }
                                    TextButton(onClick = {
                                        undoTasksSnapshot = tasks
                                        cancelReminder(context, task.id)
                                        val updated = tasks.filterNot { it.id == task.id }
                                        tasks = updated
                                        saveTasks(context, updated)
                                    }) { Text("⋮") }
                                }
                                Divider(color = Color(0xFF343A45), thickness = 1.dp)
                            }
                        }
                        item {
                            TextButton(
                                onClick = {
                                    val newId = maxOf(System.currentTimeMillis(), (tasks.maxOfOrNull { it.id } ?: 0L) + 1L)
                                    val updated = tasks + TodoTask(newId, "", false, null, null, selectedTodoListId)
                                    tasks = updated
                                    saveTasks(context, updated)
                                },
                                modifier = Modifier.fillMaxWidth().padding(top = 4.dp)
                            ) {
                                Text("＋    Dodaj novi redak", color = MaterialTheme.colorScheme.primary)
                            }
                            Surface(
                                modifier = Modifier.padding(top = 12.dp),
                                shape = androidx.compose.foundation.shape.RoundedCornerShape(12.dp),
                                color = Color(0xFF34333A)
                            ) {
                            }
                            Spacer(Modifier.height(80.dp))
                        }
                    }
                } else {
                ScrollableTabRow(selectedTabIndex = categories.indexOf(selected).coerceAtLeast(0)) {
                    categories.forEach { category ->
                        Tab(selected = selected == category, onClick = {
                            selected = category
                            showTasks = false
                            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                                .putBoolean("show_tasks_screen", false).apply()
                        }, text = { Text(category) })
                    }
                        Tab(selected = false, onClick = { showAddTab = true }, text = { Text("+") })
                }

                OutlinedTextField(
                    value = search,
                    onValueChange = { search = it },
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp),
                    singleLine = true,
                    label = { Text("Pretraži bilješke") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Text)
                )

                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 12.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = androidx.compose.ui.Alignment.CenterVertically
                ) {
                    Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                        TextButton(onClick = { favoritesOnly = !favoritesOnly }) {
                            Text(if (favoritesOnly) "★ Favoriti" else "☆ Svi")
                        }
                        TextButton(onClick = { pinnedOnly = !pinnedOnly }) {
                            Text(if (pinnedOnly) "📌 Prikvačeno" else "📌 Sve")
                        }
                        Box {
                            TextButton(onClick = { showMoreFilters = true }) { Text("⋮ Više") }
                            DropdownMenu(expanded = showMoreFilters, onDismissRequest = { showMoreFilters = false }) {
                                DropdownMenuItem(
                                    text = { Text(if (trashOnly) "Izlaz iz otpada" else "🗑 Otpad") },
                                    onClick = { trashOnly = !trashOnly; archiveOnly = false; showMoreFilters = false }
                                )
                                DropdownMenuItem(
                                    text = { Text(if (archiveOnly) "Povratak iz arhive" else "Arhiva") },
                                    onClick = { archiveOnly = !archiveOnly; trashOnly = false; showMoreFilters = false }
                                )
                                if (trashOnly && notes.any { it.trashed }) {
                                    DropdownMenuItem(text = { Text("Isprazni otpad") }, onClick = {
                                        confirmEmptyTrash = true; showMoreFilters = false
                                    })
                                }
                                if (selected != "Sve") {
                                    DropdownMenuItem(text = { Text("Preimenuj tab") }, onClick = {
                                        showRenameTab = selected; renameText = selected; showMoreFilters = false
                                    })
                                    DropdownMenuItem(text = { Text("Obriši tab") }, onClick = {
                                        confirmDeleteCategory = selected; showMoreFilters = false
                                    })
                                }
                                if (undoNotesSnapshot != null || undoTasksSnapshot != null) {
                                    DropdownMenuItem(text = { Text("↶ Poništi") }, onClick = {
                                        undoNotesSnapshot?.let { persistNotes(it) }
                                        undoTasksSnapshot?.let { snapshot -> tasks = snapshot; saveTasks(context, snapshot) }
                                        undoNotesSnapshot = null
                                        undoTasksSnapshot = null
                                        showMoreFilters = false
                                    })
                                }
                            }
                        }
                    }
                }

                val categoryNotes = when {
                    trashOnly -> notes.filter { it.trashed }
                    selected == "Sve" -> notes.filter { !it.trashed && it.archived == archiveOnly }
                    else -> notes.filter { !it.trashed && it.archived == archiveOnly && it.category == selected }
                }
                val categoryTodoLists = if (trashOnly) emptyList() else todoLists.filter { it.archived == archiveOnly && (selected == "Sve" || it.category == selected) }
                val q = search.trim().lowercase()
                val searched = if (q.isEmpty()) categoryNotes else categoryNotes.filter {
                    it.title.lowercase().contains(q) || it.text.lowercase().contains(q)
                }
                val filtered = searched
                    .let { if (favoritesOnly) it.filter(Note::favorite) else it }
                    .let { if (pinnedOnly) it.filter(Note::pinned) else it }
                    .sortedWith(compareByDescending<Note> { it.pinned }.thenByDescending { it.favorite }.thenByDescending { it.updatedAt })

                LazyVerticalGrid(columns = GridCells.Adaptive(160.dp), contentPadding = PaddingValues(12.dp)) {
                    items(categoryTodoLists, key = { "todo:" + it.id }) { list ->
                        val listTasks = tasks.filter { it.listId == list.id }
                        Card(
                            Modifier.padding(6.dp).fillMaxWidth().clickable {
                                selectedTodoListId = list.id
                                showTasks = true
                                context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                                    .putString("selected_todo_list_id", list.id).putBoolean("show_tasks_screen", true).apply()
                            },
                            colors = CardDefaults.cardColors(containerColor = Color(0xFF252525))
                        ) {
                            Column(Modifier.padding(12.dp)) {
                                Row(Modifier.fillMaxWidth(), verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                                    Text(list.title.ifBlank { "Bez naslova" }, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f), maxLines = 2)
                                    TextButton(onClick = {
                                        editTodoListTitle = list.title
                                        editTodoListCategory = list.category
                                        editingTodoList = list
                                    }, contentPadding = PaddingValues(2.dp)) { Text("⋮") }
                                }
                                listTasks.take(4).forEach { task ->
                                    Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                                        Checkbox(checked = task.completed, onCheckedChange = { checked ->
                                            val updated = tasks.map { if (it.id == task.id) it.copy(completed = checked) else it }
                                            tasks = updated
                                            saveTasks(context, updated)
                                        }, modifier = Modifier.size(34.dp))
                                        Text(task.title, modifier = Modifier.weight(1f), maxLines = 1,
                                            color = if (task.completed) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface,
                                            style = MaterialTheme.typography.bodyMedium)
                                    }
                                }
                                if (listTasks.size > 4) Text("… i još ${listTasks.size - 4}", style = MaterialTheme.typography.bodySmall)
                                Text("${listTasks.count { !it.completed }} preostalo · ${listTasks.count { it.completed }} dovršeno", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                    }
                    items(filtered, key = { it.id }) { note ->
                        Card(
                            Modifier.padding(6.dp).clickable { editing = note },
                            colors = CardDefaults.cardColors(containerColor = Color(note.color))
                        ) {
                            Column(Modifier.padding(14.dp)) {
                                Text(note.title.ifBlank { "Bez naslova" }, style = MaterialTheme.typography.titleMedium, color = Color.White)
                                Spacer(Modifier.height(8.dp))
                                Text(note.text, maxLines = 8, color = Color.White)
                                note.attachmentUri?.let { uri ->
                                    val preview = remember(uri) { loadTaskThumbnail(context, uri) }
                                    if (preview != null) {
                                        Spacer(Modifier.height(8.dp))
                                        Image(
                                            bitmap = preview.asImageBitmap(),
                                            contentDescription = "Slika bilješke",
                                            modifier = Modifier.fillMaxWidth().heightIn(max = 180.dp),
                                            contentScale = ContentScale.Crop
                                        )
                                    }
                                }
                                Spacer(Modifier.height(10.dp))
                                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                                    TextButton(onClick = {
                                        persistNotes(notes.map {
                                            if (it.id == note.id) it.copy(favorite = !it.favorite, updatedAt = System.currentTimeMillis()) else it
                                        })
                                    }) { Text(if (note.favorite) "★" else "☆") }
                                    TextButton(onClick = {
                                        persistNotes(notes.map {
                                            if (it.id == note.id) it.copy(pinned = !it.pinned, updatedAt = System.currentTimeMillis()) else it
                                        })
                                    }) { Text(if (note.pinned) "📌" else "📍") }
                                    if (trashOnly) {
                                        TextButton(onClick = { restoreNote(note) }) { Text("Vrati") }
                                        TextButton(onClick = { confirmPermanentDelete = note }) { Text("Trajno obriši") }
                                    } else if (archiveOnly) {
                                        TextButton(onClick = { persistNotes(notes.map { if (it.id == note.id) it.copy(archived = false, updatedAt = System.currentTimeMillis()) else it }) }) { Text("Vrati") }
                                        TextButton(onClick = { deleteNote(note) }) { Text("Obriši") }
                                    } else {
                                        TextButton(onClick = { editing = note }) { Text("Uredi") }
                                        TextButton(onClick = { persistNotes(notes.map { if (it.id == note.id) it.copy(archived = true, updatedAt = System.currentTimeMillis()) else it }) }) { Text("Arhiviraj") }
                                        TextButton(onClick = { deleteNote(note) }) { Text("Obriši") }
                                    }
                                }
                            }
                        }
                    }
                }
                }
            }
        }

        if (showCreateChooser) {
            AlertDialog(
                onDismissRequest = { showCreateChooser = false },
                title = { Text("Što želiš napraviti?") },
                text = {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(
                            onClick = {
                                showCreateChooser = false
                                // A new To-do action creates a separate named list.
                                newTodoListTitle = ""
                            newTodoListCategory = if (selected == "Sve") categories.firstOrNull { it != "Sve" } ?: "Osobno" else selected
                                showCreateTodoList = true
                            },
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Column(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                                Text("☑  Nova To-do lista")
                                Text("Napravi popis zadataka i podsjetnika", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                        OutlinedButton(
                            onClick = {
                                showCreateChooser = false
                                showTasks = false
                                context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                                    .edit().putBoolean("show_tasks_screen", false).apply()
                                editing = Note(
                                    System.currentTimeMillis(),
                                    "",
                                    "",
                                    if (selected == "Sve") "Osobno" else selected
                                )
                            },
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Column(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                                Text("▤  Nova bilješka")
                                Text("Napravi običnu bilješku, po želji sa slikom", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                    }
                },
                confirmButton = {},
                dismissButton = { TextButton(onClick = { showCreateChooser = false }) { Text("Odustani") } }
            )
        }

        if (showTodoListPicker) {
            AlertDialog(
                onDismissRequest = { showTodoListPicker = false },
                title = { Text("Moje To-do liste") },
                text = {
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        todoLists.forEach { list ->
                            OutlinedButton(
                                onClick = {
                                    selectedTodoListId = list.id
                                    context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                                        .putString("selected_todo_list_id", list.id)
                                        .putBoolean("show_tasks_screen", true).apply()
                                    showTodoListPicker = false
                                },
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Text(list.title, modifier = Modifier.fillMaxWidth())
                            }
                        }
                    }
                },
                confirmButton = {
                    TextButton(onClick = {
                        showTodoListPicker = false
                        newTodoListTitle = ""
                            newTodoListCategory = if (selected == "Sve") categories.firstOrNull { it != "Sve" } ?: "Osobno" else selected
                        showCreateTodoList = true
                    }) { Text("＋ Nova lista") }
                },
                dismissButton = { TextButton(onClick = { showTodoListPicker = false }) { Text("Zatvori") } }
            )
        }

        if (editingTodoList != null) {
            val currentList = editingTodoList!!
            AlertDialog(
                onDismissRequest = { editingTodoList = null },
                title = { Text("Uredi To-do listu") },
                text = {
                    Column {
                        OutlinedTextField(
                            value = editTodoListTitle,
                            onValueChange = { editTodoListTitle = it },
                            label = { Text("Naziv liste") },
                            singleLine = true
                        )
                        if (!currentList.archived) {
                            TextButton(onClick = {
                                todoLists = todoLists.map { if (it.id == currentList.id) it.copy(archived = true) else it }
                                saveTodoLists(context, todoLists)
                                editingTodoList = null
                            }) { Text("Arhiviraj listu") }
                        } else {
                            TextButton(onClick = {
                                todoLists = todoLists.map { if (it.id == currentList.id) it.copy(archived = false) else it }
                                saveTodoLists(context, todoLists)
                                editingTodoList = null
                            }) { Text("Vrati iz arhive") }
                        }
                        TextButton(onClick = {
                            confirmDeleteTodoList = currentList
                            editingTodoList = null
                        }) { Text("Obriši listu", color = MaterialTheme.colorScheme.error) }
                        Spacer(Modifier.height(12.dp))
                        Text("Premjesti cijelu listu u tab", style = MaterialTheme.typography.labelLarge)
                        categories.filter { it != "Sve" }.forEach { category ->
                            Row(
                                Modifier.fillMaxWidth().clickable { editTodoListCategory = category }.padding(vertical = 3.dp),
                                verticalAlignment = androidx.compose.ui.Alignment.CenterVertically
                            ) {
                                RadioButton(selected = editTodoListCategory == category, onClick = { editTodoListCategory = category })
                                Text(category)
                            }
                        }
                    }
                },
                confirmButton = {
                    TextButton(onClick = {
                        val title = editTodoListTitle.trim()
                        if (title.isNotEmpty()) {
                            val updated = todoLists.map { if (it.id == currentList.id) it.copy(title = title, category = editTodoListCategory) else it }
                            todoLists = updated
                            saveTodoLists(context, updated)
                            if (selectedTodoListId == currentList.id) selected = editTodoListCategory
                        }
                        editingTodoList = null
                    }) { Text("Spremi") }
                },
                dismissButton = { TextButton(onClick = { editingTodoList = null }) { Text("Odustani") } }
            )
        }

        confirmDeleteTodoList?.let { listToDelete ->
            AlertDialog(
                onDismissRequest = { confirmDeleteTodoList = null },
                title = { Text("Obriši To-do listu?") },
                text = { Text("Lista „${listToDelete.title}” i svi njezini zadaci bit će obrisani. Ovu radnju možeš odmah poništiti iz izbornika ⋮.") },
                confirmButton = {
                    TextButton(onClick = {
                        undoTasksSnapshot = tasks
                        val remainingTasks = tasks.filterNot { it.listId == listToDelete.id }
                        tasks.filter { it.listId == listToDelete.id }.forEach { cancelReminder(context, it.id) }
                        tasks = remainingTasks
                        saveTasks(context, remainingTasks)
                        todoLists = todoLists.filterNot { it.id == listToDelete.id }
                        saveTodoLists(context, todoLists)
                        if (selectedTodoListId == listToDelete.id) {
                            selectedTodoListId = todoLists.firstOrNull { it.category == selected && !it.archived }?.id
                                ?: todoLists.firstOrNull { !it.archived }?.id
                                ?: DEFAULT_TODO_LIST_ID
                        }
                        confirmDeleteTodoList = null
                    }) { Text("Obriši", color = MaterialTheme.colorScheme.error) }
                },
                dismissButton = { TextButton(onClick = { confirmDeleteTodoList = null }) { Text("Odustani") } }
            )
        }

        if (showCreateTodoList) {
            AlertDialog(
                onDismissRequest = { showCreateTodoList = false },
                title = { Text("Nova To-do lista") },
                text = {
                    Column {
                        OutlinedTextField(
                            value = newTodoListTitle,
                            onValueChange = { newTodoListTitle = it },
                            label = { Text("Naziv liste") },
                            placeholder = { Text("Npr. Kupovina, Posao, Kuća") },
                            singleLine = true
                        )
                        Spacer(Modifier.height(10.dp))
                        Text("Smjesti listu u tab", style = MaterialTheme.typography.labelLarge)
                        categories.filter { it != "Sve" }.forEach { category ->
                            Row(
                                Modifier.fillMaxWidth().clickable { newTodoListCategory = category }.padding(vertical = 2.dp),
                                verticalAlignment = androidx.compose.ui.Alignment.CenterVertically
                            ) {
                                RadioButton(
                                    selected = newTodoListCategory == category,
                                    onClick = { newTodoListCategory = category }
                                )
                                Text(category)
                            }
                        }
                    }
                },
                confirmButton = {
                    TextButton(onClick = {
                        val title = newTodoListTitle.trim()
                        if (title.isNotEmpty()) {
                            val id = "todo_" + java.util.UUID.randomUUID().toString()
                            val targetCategory = if (newTodoListCategory == "Sve") categories.firstOrNull { it != "Sve" } ?: "Osobno" else newTodoListCategory
                            val list = TodoList(id, title, targetCategory)
                            todoLists = todoLists + list
                            saveTodoLists(context, todoLists)
                            selectedTodoListId = id
                            selected = targetCategory
                            showTasks = false
                            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                                .putString("selected_todo_list_id", id)
                                .putBoolean("show_tasks_screen", false).apply()
                            tasks = tasks
                            taskTitle = ""
                            taskReminderAt = null
                            taskImageUri = null
                            showAddTask = true
                        }
                        showCreateTodoList = false
                    }) { Text("Stvori") }
                },
                dismissButton = { TextButton(onClick = { showCreateTodoList = false }) { Text("Odustani") } }
            )
        }

        if (showRenameTaskList) {
            AlertDialog(
                onDismissRequest = { showRenameTaskList = false },
                title = { Text("Naziv popisa") },
                text = {
                    OutlinedTextField(
                        value = taskListTitleDraft,
                        onValueChange = { taskListTitleDraft = it },
                        singleLine = true,
                        label = { Text("Naziv") }
                    )
                },
                confirmButton = {
                    TextButton(onClick = {
                        val newTitle = taskListTitleDraft.trim()
                        taskListTitle = newTitle
                        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString("todo_list_title", newTitle).apply()
                        showRenameTaskList = false
                    }) { Text("Spremi") }
                },
                dismissButton = { TextButton(onClick = { showRenameTaskList = false }) { Text("Odustani") } }
            )
        }

        if (showAddTask) {
            AlertDialog(
                onDismissRequest = { showAddTask = false },
                title = { Text("Novi zadatak") },
                text = {
                    Column {
                        Text("Dodaj više zadataka odjednom — svaki novi red postaje zaseban zadatak.", style = MaterialTheme.typography.bodySmall)
                        Spacer(Modifier.height(8.dp))
                        OutlinedTextField(
                            value = taskTitle,
                            onValueChange = { taskTitle = it },
                            modifier = Modifier.fillMaxWidth().heightIn(min = 110.dp, max = 220.dp),
                            label = { Text("Zadaci") },
                            placeholder = { Text("Npr. kupiti kruh\nNazvati servis\nPlatiti račun") },
                            minLines = 3,
                            maxLines = 8
                        )
                        Spacer(Modifier.height(12.dp))
                        Text(if (taskReminderAt == null) "Bez podsjetnika" else "Podsjetnik: " + java.text.SimpleDateFormat("dd.MM.yyyy. HH:mm", java.util.Locale.getDefault()).format(java.util.Date(taskReminderAt!!)))
                            if (taskReminderPermissionDenied) Text("Za prikaz podsjetnika dopusti obavijesti u postavkama Androida.", color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                        Text(
                            "Slike zadataka ostaju na ovom uređaju i ne sinkroniziraju se s Google Driveom.",
                            style = MaterialTheme.typography.bodySmall,
                            color = Color(0xFFADB4C0)
                        )
                        Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                            TextButton(onClick = { taskImagePicker.launch(arrayOf("image/*")) }) { Text(if (taskImageUri == null) "Dodaj sliku" else "Promijeni sliku") }
                            TextButton(onClick = { taskImageUri = null }) { Text("Ukloni sliku") }
                        }
                        taskImageUri?.let { uri ->
                            val thumbnail = remember(uri) { loadTaskThumbnail(context, uri) }
                            if (thumbnail != null) Image(bitmap = thumbnail.asImageBitmap(), contentDescription = "Odabrana slika", modifier = Modifier.fillMaxWidth().heightIn(max = 180.dp), contentScale = ContentScale.Fit)
                        }
                        Row {
                            TextButton(onClick = {
                                if (Build.VERSION.SDK_INT >= 33 && context.checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                                    taskReminderPermissionLauncher.launch(android.Manifest.permission.POST_NOTIFICATIONS)
                                } else {
                                    showTaskReminderPicker()
                                }
                            }) { Text("Postavi datum i vrijeme") }
                            TextButton(onClick = { taskReminderAt = null }) { Text("Ukloni") }
                        }
                    }
                },
                confirmButton = {
                    TextButton(onClick = {
                        val titles = taskTitle.lines().map { it.trim() }.filter { it.isNotEmpty() }
                        if (titles.isNotEmpty()) {
                            // Keep IDs unique even when adding many rows quickly or after restoring older tasks.
                            val firstId = maxOf(
                                System.currentTimeMillis(),
                                (tasks.maxOfOrNull { it.id } ?: 0L) + 1L
                            )
                            val added = titles.mapIndexed { index, title ->
                                TodoTask(firstId + index, title, false, taskReminderAt, taskImageUri, selectedTodoListId)
                            }
                            val updated = tasks + added
                            tasks = updated
                            saveTasks(context, updated)
                            added.forEach { task -> task.reminderAt?.let { scheduleReminder(context, task) } }
                        }
                        showAddTask = false
                    }) { Text("Spremi") }
                },
                dismissButton = { TextButton(onClick = { showAddTask = false }) { Text("Odustani") } }
            )
        }

        editing?.let { note ->
            var title by remember(note.id) { mutableStateOf(note.title) }
            var body by remember(note.id) { mutableStateOf(note.text) }
            var cat by remember(note.id) { mutableStateOf(if (note.category == "") "Osobno" else note.category) }
            var noteColor by remember(note.id) { mutableLongStateOf(note.color) }
            var noteReminderAt by remember(note.id) { mutableStateOf(note.reminderAt) }
            var noteAttachmentUri by remember(note.id) { mutableStateOf(note.attachmentUri) }

            LaunchedEffect(note.id, title, body, cat, noteColor, noteReminderAt, noteAttachmentUri) {
                // Debounced local autosave: edits survive app dismissal or an accidental close.
                delay(500)
                val saved = note.copy(
                    title = title,
                    text = body,
                    category = if (cat == "🗑 Otpad" || cat == "Sve") "Osobno" else cat,
                    color = noteColor,
                    trashed = false,
                    reminderAt = noteReminderAt,
                    attachmentUri = noteAttachmentUri,
                    updatedAt = System.currentTimeMillis()
                )
                persistNotes((notes.filterNot { it.id == note.id }) + saved)
                if (noteReminderAt != null && noteReminderAt!! > System.currentTimeMillis()) scheduleNoteReminder(context, saved)
                else cancelNoteReminder(context, note.id)
            }

            BackHandler(enabled = true) {
                val saved = note.copy(
                    title = title,
                    text = body,
                    category = if (cat == "🗑 Otpad" || cat == "Sve") "Osobno" else cat,
                    color = noteColor,
                    trashed = false,
                    reminderAt = noteReminderAt,
                    attachmentUri = noteAttachmentUri,
                    updatedAt = System.currentTimeMillis()
                )
                persistNotes((notes.filterNot { it.id == note.id }) + saved)
                if (noteReminderAt != null && noteReminderAt!! > System.currentTimeMillis()) scheduleNoteReminder(context, saved)
                else cancelNoteReminder(context, note.id)
                editing = null
            }
            val noteAttachmentPicker = androidx.activity.compose.rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
                if (uri != null) {
                    try { context.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION) } catch (_: Exception) { }
                    noteAttachmentUri = uri.toString()
                }
            }
            var noteReminderPermissionDenied by remember(note.id) { mutableStateOf(false) }
            var pendingNoteReminderPicker by remember(note.id) { mutableStateOf(false) }

            fun showNoteReminderPicker() {
                val now = java.util.Calendar.getInstance()
                DatePickerDialog(context, { _, year, month, day ->
                    val chosen = java.util.Calendar.getInstance().apply { set(year, month, day) }
                    TimePickerDialog(context, { _, hour, minute ->
                        chosen.set(java.util.Calendar.HOUR_OF_DAY, hour)
                        chosen.set(java.util.Calendar.MINUTE, minute)
                        chosen.set(java.util.Calendar.SECOND, 0)
                        chosen.set(java.util.Calendar.MILLISECOND, 0)
                        if (chosen.timeInMillis > System.currentTimeMillis()) {
                            noteReminderAt = chosen.timeInMillis
                            noteReminderPermissionDenied = false
                        } else {
                            noteReminderAt = null
                            android.widget.Toast.makeText(context, "Odaberi vrijeme u budućnosti.", android.widget.Toast.LENGTH_SHORT).show()
                        }
                    }, now.get(java.util.Calendar.HOUR_OF_DAY), now.get(java.util.Calendar.MINUTE), true).show()
                }, now.get(java.util.Calendar.YEAR), now.get(java.util.Calendar.MONTH), now.get(java.util.Calendar.DAY_OF_MONTH)).show()
            }

            val noteReminderPermissionLauncher = androidx.activity.compose.rememberLauncherForActivityResult(
                ActivityResultContracts.RequestPermission()
            ) { granted ->
                pendingNoteReminderPicker = false
                noteReminderPermissionDenied = !granted
                if (granted) showNoteReminderPicker()
            }

            androidx.compose.ui.window.Dialog(
                onDismissRequest = {
                    val saved = note.copy(
                        title = title, text = body,
                        category = if (cat == "🗑 Otpad" || cat == "Sve") "Osobno" else cat,
                        color = noteColor, trashed = false, reminderAt = noteReminderAt,
                        attachmentUri = noteAttachmentUri, updatedAt = System.currentTimeMillis()
                    )
                    persistNotes((notes.filterNot { it.id == note.id }) + saved)
                    if (noteReminderAt != null && noteReminderAt!! > System.currentTimeMillis()) scheduleNoteReminder(context, saved)
                    else cancelNoteReminder(context, note.id)
                    editing = null
                },
                properties = androidx.compose.ui.window.DialogProperties(usePlatformDefaultWidth = false)
            ) {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = Color(0xFF17191D)
                ) {
                    Column(Modifier.fillMaxSize().navigationBarsPadding()) {
                        Row(
                            Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 6.dp),
                            verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            TextButton(onClick = {
                                val saved = note.copy(
                                    title = title, text = body,
                                    category = if (cat == "🗑 Otpad" || cat == "Sve") "Osobno" else cat,
                                    color = noteColor, trashed = false, reminderAt = noteReminderAt,
                                    attachmentUri = noteAttachmentUri, updatedAt = System.currentTimeMillis()
                                )
                                persistNotes((notes.filterNot { it.id == note.id }) + saved)
                                if (noteReminderAt != null && noteReminderAt!! > System.currentTimeMillis()) scheduleNoteReminder(context, saved)
                                else cancelNoteReminder(context, note.id)
                                editing = null
                            }) { Text("Natrag") }
                            Text(
                                if (note.title.isBlank()) "Nova bilješka" else "Bilješka",
                                style = MaterialTheme.typography.titleLarge
                            )
                            TextButton(onClick = {
                                // Save even an empty new note: creating a note should produce a
                                // visible card instead of silently closing and discarding it.
                                val saved = note.copy(
                                    title = title,
                                    text = body,
                                    category = if (cat == "🗑 Otpad" || cat == "Sve") "Osobno" else cat,
                                    color = noteColor,
                                    trashed = false,
                                    reminderAt = noteReminderAt,
                                    attachmentUri = noteAttachmentUri,
                                    updatedAt = System.currentTimeMillis()
                                )
                                persistNotes((notes.filterNot { it.id == note.id }) + saved)
                                if (noteReminderAt != null && noteReminderAt!! > System.currentTimeMillis()) {
                                    scheduleNoteReminder(context, saved)
                                } else {
                                    cancelNoteReminder(context, note.id)
                                }
                                editing = null
                            }) { Text("Spremi") }
                        }

                        OutlinedTextField(
                            value = title,
                            onValueChange = { title = it },
                            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp),
                            placeholder = { Text("Naslov") },
                            singleLine = true,
                            textStyle = MaterialTheme.typography.titleLarge
                        )

                        Box(Modifier.weight(1f).fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp)) {
                            androidx.compose.foundation.Canvas(Modifier.fillMaxSize()) {
                                val lineGap = 30.dp.toPx()
                                var y = lineGap
                                while (y < size.height) {
                                    drawLine(
                                        color = Color(0xFF343A45),
                                        start = androidx.compose.ui.geometry.Offset(0f, y),
                                        end = androidx.compose.ui.geometry.Offset(size.width, y),
                                        strokeWidth = 1.dp.toPx()
                                    )
                                    y += lineGap
                                }
                            }
                            androidx.compose.foundation.text.BasicTextField(
                                value = body,
                                onValueChange = { body = it },
                                modifier = Modifier.fillMaxSize().padding(top = 5.dp),
                                textStyle = MaterialTheme.typography.bodyLarge.copy(
                                    color = Color(0xFFF0F0F0),
                                    lineHeight = 30.sp
                                ),
                                decorationBox = { innerTextField ->
                                    Box(Modifier.fillMaxSize()) {
                                        if (body.isEmpty()) {
                                            Text("Započni pisati bilješku…", color = Color(0xFF858B96))
                                        }
                                        innerTextField()
                                    }
                                }
                            )
                        }

                        Column(
                            Modifier.fillMaxWidth()
                                .heightIn(max = 260.dp)
                                .verticalScroll(androidx.compose.foundation.rememberScrollState())
                                .padding(horizontal = 12.dp, vertical = 4.dp)
                        ) {
                            Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                                Text("Podsjetnik", style = MaterialTheme.typography.labelLarge)
                                Spacer(Modifier.weight(1f))
                                TextButton(onClick = {
                                    if (Build.VERSION.SDK_INT >= 33 &&
                                        context.checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                                        pendingNoteReminderPicker = true
                                        noteReminderPermissionLauncher.launch(android.Manifest.permission.POST_NOTIFICATIONS)
                                    } else {
                                        showNoteReminderPicker()
                                    }
                                }) { Text(if (noteReminderAt == null) "＋ Dodaj" else "Promijeni") }
                                if (noteReminderAt != null) TextButton(onClick = { noteReminderAt = null }) { Text("Ukloni") }
                            }
                            noteReminderAt?.let { at ->
                                Text(
                                    java.text.SimpleDateFormat("dd.MM.yyyy. HH:mm", java.util.Locale.getDefault()).format(java.util.Date(at)),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.primary
                                )
                            }
                            if (noteReminderPermissionDenied) {
                                Text(
                                    "Obavijesti nisu dopuštene. Uključi ih u postavkama Androida kako bi podsjetnici mogli stizati.",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.error
                                )
                            }
                            Spacer(Modifier.height(4.dp))
                            Row(Modifier.fillMaxWidth(), verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                                Text("Kategorija", style = MaterialTheme.typography.labelLarge)
                                Spacer(Modifier.weight(1f))
                                TextButton(onClick = {
                                    val saved = note.copy(
                                        title = title, text = body,
                                        category = if (cat == "🗑 Otpad" || cat == "Sve") "Osobno" else cat,
                                        color = noteColor, archived = true, trashed = false,
                                        reminderAt = noteReminderAt, attachmentUri = noteAttachmentUri,
                                        updatedAt = System.currentTimeMillis()
                                    )
                                    persistNotes((notes.filterNot { it.id == note.id }) + saved)
                                    editing = null
                                }) { Text("Arhiviraj") }
                                TextButton(onClick = {
                                    val saved = note.copy(
                                        title = title, text = body,
                                        category = if (cat == "🗑 Otpad" || cat == "Sve") "Osobno" else cat,
                                        color = noteColor, trashed = true,
                                        reminderAt = null, attachmentUri = noteAttachmentUri,
                                        updatedAt = System.currentTimeMillis()
                                    )
                                    undoNotesSnapshot = notes
                                    cancelNoteReminder(context, note.id)
                                    persistNotes((notes.filterNot { it.id == note.id }) + saved)
                                    editing = null
                                }) { Text("Obriši") }
                            }
                            androidx.compose.foundation.lazy.LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                items(categories.filter { it != "Sve" }) { category ->
                                    FilterChip(
                                        selected = cat == category,
                                        onClick = { cat = category },
                                        label = { Text(category) }
                                    )
                                }
                            }
                            Spacer(Modifier.height(4.dp))
                            var colorMenuExpanded by remember(note.id) { mutableStateOf(false) }
                            val noteColors = listOf(
                                0xFF353535L to "Ugljen siva",
                                0xFF8B453AL to "Terakota",
                                0xFF8B641FL to "Zlatno smeđa",
                                0xFF567B38L to "Maslinasto zelena",
                                0xFF276A75L to "Petrolej",
                                0xFF315B91L to "Plava",
                                0xFF65439AL to "Ljubičasta",
                                0xFF93456CL to "Roza",
                                0xFF7D573CL to "Smeđa",
                                0xFF5E6D36L to "Maslinasta",
                                0xFF355E4BL to "Šumsko zelena",
                                0xFF5B526FL to "Sivo ljubičasta"
                            )
                            Text("Boja bilješke", style = MaterialTheme.typography.labelLarge)
                            Box {
                                OutlinedButton(
                                    onClick = { colorMenuExpanded = true },
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    Surface(
                                        modifier = Modifier.size(22.dp),
                                        shape = androidx.compose.foundation.shape.CircleShape,
                                        color = Color(noteColor),
                                        border = androidx.compose.foundation.BorderStroke(1.dp, Color.White.copy(alpha = 0.65f))
                                    ) {}
                                    Spacer(Modifier.width(10.dp))
                                    Text(noteColors.firstOrNull { it.first == noteColor }?.second ?: "Odaberi boju", color = Color.White)
                                    Spacer(Modifier.weight(1f))
                                    Text("▾")
                                }
                                DropdownMenu(
                                    expanded = colorMenuExpanded,
                                    onDismissRequest = { colorMenuExpanded = false },
                                    modifier = Modifier.fillMaxWidth(0.88f)
                                ) {
                                    noteColors.forEach { (color, label) ->
                                        DropdownMenuItem(
                                            text = {
                                                Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                                                    Surface(
                                                        modifier = Modifier.size(26.dp),
                                                        shape = androidx.compose.foundation.shape.CircleShape,
                                                        color = Color(color),
                                                        border = if (noteColor == color) androidx.compose.foundation.BorderStroke(2.dp, MaterialTheme.colorScheme.primary) else null
                                                    ) {}
                                                    Spacer(Modifier.width(12.dp))
                                                    Text(label, color = Color.White)
                                                    Spacer(Modifier.weight(1f))
                                                    if (noteColor == color) Text("✓", color = MaterialTheme.colorScheme.primary)
                                                }
                                            },
                                            onClick = {
                                                noteColor = color
                                                colorMenuExpanded = false
                                            }
                                        )
                                    }
                                }
                            }
                            Spacer(Modifier.height(8.dp))
                            Divider(color = Color(0xFF343A45))
                            Text(
                                "Privici ostaju na ovom uređaju i ne sinkroniziraju se s Google Driveom.",
                                style = MaterialTheme.typography.bodySmall,
                                color = Color(0xFFADB4C0)
                            )
                            Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                                Column(Modifier.weight(1f)) {
                                    Text("Slika bilješke", style = MaterialTheme.typography.labelLarge)
                                    Text(
                                        noteAttachmentUri?.substringAfterLast('/')?.takeIf { it.isNotBlank() } ?: "Nema slike",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = Color(0xFFADB4C0),
                                        maxLines = 1
                                    )
                                }
                                TextButton(onClick = { noteAttachmentPicker.launch(arrayOf("image/*")) }) {
                                    Text(if (noteAttachmentUri == null) "＋ Dodaj sliku" else "Promijeni sliku")
                                }
                                if (noteAttachmentUri != null) TextButton(onClick = { noteAttachmentUri = null }) { Text("Ukloni") }
                            }
                            noteAttachmentUri?.let { uri ->
                                val attachmentPreview = remember(uri) { loadTaskThumbnail(context, uri) }
                                if (attachmentPreview != null) {
                                    Image(
                                        bitmap = attachmentPreview.asImageBitmap(),
                                        contentDescription = "Pregled privitka bilješke",
                                        modifier = Modifier.fillMaxWidth().heightIn(max = 150.dp),
                                        contentScale = ContentScale.Fit
                                    )
                                }
                                TextButton(onClick = {
                                    try {
                                        val attachmentUri = Uri.parse(uri)
                                        val mimeType = context.contentResolver.getType(attachmentUri) ?: "*/*"
                                        context.startActivity(Intent(Intent.ACTION_VIEW).setDataAndType(attachmentUri, mimeType).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION))
                                    } catch (_: Exception) { }
                                }) { Text("Otvori privitak") }
                                Text(
                                    "Privitak je spremljen na ovom uređaju; prijenos privitaka na Google Drive i druge uređaje još nije aktiviran.",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = Color(0xFFADB4C0)
                                )
                            }
                            Spacer(Modifier.height(8.dp))
                        }
                    }
                }
            }
        }

        confirmPermanentDelete?.let { note ->
            AlertDialog(
                onDismissRequest = { confirmPermanentDelete = null },
                title = { Text("Trajno brisanje") },
                text = { Text("Želiš li trajno obrisati ovu bilješku? Ova radnja se ne može poništiti.") },
                confirmButton = {
                    TextButton(onClick = {
                        permanentlyDeleteNote(note)
                        confirmPermanentDelete = null
                    }) { Text("Trajno obriši") }
                },
                dismissButton = { TextButton(onClick = { confirmPermanentDelete = null }) { Text("Odustani") } }
            )
        }

        if (confirmEmptyTrash) {
            AlertDialog(
                onDismissRequest = { confirmEmptyTrash = false },
                title = { Text("Isprazni otpad") },
                text = { Text("Trajno ćeš obrisati sve bilješke iz otpada. Ova radnja se ne može poništiti.") },
                confirmButton = {
                    TextButton(onClick = {
                        emptyTrash()
                        confirmEmptyTrash = false
                    }) { Text("Isprazni otpad") }
                },
                dismissButton = { TextButton(onClick = { confirmEmptyTrash = false }) { Text("Odustani") } }
            )
        }

        confirmDeleteCategory?.let { category ->
            AlertDialog(
                onDismissRequest = { confirmDeleteCategory = null },
                title = { Text("Obriši kategoriju") },
                text = { Text("Kategorija \"$category\" će biti uklonjena. Bilješke iz nje bit će premještene u drugu kategoriju.") },
                confirmButton = {
                    TextButton(onClick = {
                        deleteCategory(category)
                        confirmDeleteCategory = null
                    }) { Text("Obriši") }
                },
                dismissButton = { TextButton(onClick = { confirmDeleteCategory = null }) { Text("Odustani") } }
            )
        }

        if (showAddTab) {
            AlertDialog(
                onDismissRequest = { showAddTab = false },
                title = { Text("Novi tab") },
                text = { OutlinedTextField(newTabName, { newTabName = it }, label = { Text("Naziv") }, singleLine = true) },
                confirmButton = {
                    TextButton(onClick = {
                        val n = newTabName.trim()
                        if (n.isNotEmpty() && !categories.contains(n)) {
                            saveDeletedCategories(context, loadDeletedCategories(context) - n)
                            persistCategories(categories + n)
                            selected = n
                        }
                        newTabName = ""
                        showAddTab = false
                    }) { Text("Dodaj") }
                },
                dismissButton = { TextButton(onClick = { showAddTab = false }) { Text("Odustani") } }
            )
        }

        showRenameTab?.let { old ->
            AlertDialog(
                onDismissRequest = { showRenameTab = null },
                title = { Text("Preimenuj tab") },
                text = { OutlinedTextField(renameText, { renameText = it }, singleLine = true) },
                confirmButton = {
                    TextButton(onClick = {
                        val n = renameText.trim()
                        if (n.isNotEmpty() && n != "Sve" && !categories.contains(n)) {
                            val deletedCategories = loadDeletedCategories(context) + old - n
                            saveDeletedCategories(context, deletedCategories)
                            persistCategories(categories.map { if (it == old) n else it })
                            val renamedAt = System.currentTimeMillis()
                            persistNotes(notes.map { if (it.category == old) it.copy(category = n, updatedAt = renamedAt) else it })
                            if (selected == old) selected = n
                        }
                        showRenameTab = null
                    }) { Text("Spremi") }
                },
                dismissButton = { TextButton(onClick = { showRenameTab = null }) { Text("Odustani") } }
            )
        }

        if (showServerSettings) {
            AlertDialog(
                onDismissRequest = { showServerSettings = false; serverConfigError = null },
                title = { Text("Sticky & Note server") },
                text = {
                    Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                        Text(
                            "Prijava povezuje račun s API-jem. Pritisni „Sinkroniziraj sa serverom” za ručno spajanje bilješki i todo lista između uređaja.",
                            style = MaterialTheme.typography.bodySmall
                        )
                        Spacer(Modifier.height(10.dp))
                        OutlinedTextField(
                            value = serverUrlDraft,
                            onValueChange = { serverUrlDraft = it; serverConfigError = null; serverAuthStatus = null },
                            modifier = Modifier.fillMaxWidth(),
                            label = { Text("Adresa servera") },
                            singleLine = true,
                            placeholder = { Text("https://notes.mandocloud.uk") }
                        )
                        Spacer(Modifier.height(8.dp))
                        OutlinedTextField(
                            value = serverEmailDraft,
                            onValueChange = { serverEmailDraft = it; serverConfigError = null; serverAuthStatus = null },
                            modifier = Modifier.fillMaxWidth(),
                            label = { Text("E-mail") },
                            singleLine = true,
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email)
                        )
                        Spacer(Modifier.height(8.dp))
                        OutlinedTextField(
                            value = serverPasswordDraft,
                            onValueChange = { serverPasswordDraft = it; serverConfigError = null; serverAuthStatus = null },
                            modifier = Modifier.fillMaxWidth(),
                            label = { Text("Lozinka servera") },
                            singleLine = true,
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                            visualTransformation = androidx.compose.ui.text.input.PasswordVisualTransformation()
                        )
                        serverConfigError?.let {
                            Spacer(Modifier.height(6.dp))
                            Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                        }
                        serverAuthStatus?.let {
                            Spacer(Modifier.height(6.dp))
                            Text(it, color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.bodySmall)
                        }
                        Spacer(Modifier.height(8.dp))
                        Text(
                            "Ova sinkronizacija koristi Sticky & Note server, ne Google Drive. Lokalni privici i slike ostaju na ovom uređaju.",
                            style = MaterialTheme.typography.bodySmall
                        )
                        TextButton(
                            enabled = !serverAuthBusy && !NotesServerApi(context).savedToken().isNullOrBlank(),
                            onClick = {
                                serverAuthBusy = true
                                serverConfigError = null
                                serverAuthStatus = "Sinkronizacija sa serverom u tijeku…"
                                thread {
                                    try {
                                        val result = NotesServerSync.sync(context, notes, categories, todoLists, tasks)
                                        android.os.Handler(android.os.Looper.getMainLooper()).post {
                                            notes = result.notes
                                            categories = result.categories
                                            todoLists = result.lists
                                            tasks = result.tasks
                                            saveNotes(context, result.notes)
                                            saveCategories(context, result.categories)
                                            saveTodoLists(context, result.lists)
                                            saveTasks(context, result.tasks)
                                            serverAuthBusy = false
                                            serverAuthStatus = "Sinkronizacija završena. Bilješke: ${result.notes.size}, todo liste: ${result.lists.size}, zadaci: ${result.tasks.size}."
                                        }
                                    } catch (e: Exception) {
                                        android.os.Handler(android.os.Looper.getMainLooper()).post {
                                            serverAuthBusy = false
                                            serverAuthStatus = null
                                            serverConfigError = "Sinkronizacija nije uspjela: ${e.message ?: "provjeri vezu i prijavu"}"
                                        }
                                    }
                                }
                            }
                        ) { Text("Sinkroniziraj sa serverom") }
                        if (serverAuthBusy) {
                            Spacer(Modifier.height(8.dp))
                            LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                        }
                    }
                },
                confirmButton = {
                    Column(horizontalAlignment = androidx.compose.ui.Alignment.End) {
                        Row {
                            TextButton(
                                enabled = !serverAuthBusy,
                                onClick = {
                                    serverConfigError = null
                                    serverAuthStatus = null
                                    if (serverEmailDraft.isBlank() || !android.util.Patterns.EMAIL_ADDRESS.matcher(serverEmailDraft.trim()).matches()) {
                                        serverConfigError = "Upiši valjanu e-mail adresu."
                                        return@TextButton
                                    }
                                    if (serverPasswordDraft.length < 8) {
                                        serverConfigError = "Lozinka mora imati najmanje 8 znakova."
                                        return@TextButton
                                    }
                                    serverAuthBusy = true
                                    try {
                                        NotesServerConfig.saveBaseUrl(context, serverUrlDraft)
                                        thread {
                                            try {
                                                val session = NotesServerApi(context).login(serverEmailDraft.trim(), serverPasswordDraft)
                                                android.os.Handler(android.os.Looper.getMainLooper()).post {
                                                    serverAuthBusy = false
                                                    serverPasswordDraft = ""
                                                    serverAuthStatus = "Prijavljen: ${session.email}. Bilješke još nisu sinkronizirane."
                                                }
                                            } catch (e: Exception) {
                                                android.os.Handler(android.os.Looper.getMainLooper()).post {
                                                    serverAuthBusy = false
                                                    serverConfigError = e.message ?: "Prijava nije uspjela."
                                                }
                                            }
                                        }
                                    } catch (e: Exception) {
                                        serverAuthBusy = false
                                        serverConfigError = e.message ?: "Adresa servera nije valjana."
                                    }
                                }
                            ) { Text("Prijava") }
                            TextButton(
                                enabled = !serverAuthBusy,
                                onClick = {
                                    serverConfigError = null
                                    serverAuthStatus = null
                                    if (serverEmailDraft.isBlank() || !android.util.Patterns.EMAIL_ADDRESS.matcher(serverEmailDraft.trim()).matches()) {
                                        serverConfigError = "Upiši valjanu e-mail adresu."
                                        return@TextButton
                                    }
                                    if (serverPasswordDraft.length < 8) {
                                        serverConfigError = "Lozinka mora imati najmanje 8 znakova."
                                        return@TextButton
                                    }
                                    serverAuthBusy = true
                                    try {
                                        NotesServerConfig.saveBaseUrl(context, serverUrlDraft)
                                        thread {
                                            try {
                                                val session = NotesServerApi(context).register(serverEmailDraft.trim(), serverPasswordDraft)
                                                android.os.Handler(android.os.Looper.getMainLooper()).post {
                                                    serverAuthBusy = false
                                                    serverPasswordDraft = ""
                                                    serverAuthStatus = "Račun je stvoren i prijavljen: ${session.email}. Bilješke još nisu sinkronizirane."
                                                }
                                            } catch (e: Exception) {
                                                android.os.Handler(android.os.Looper.getMainLooper()).post {
                                                    serverAuthBusy = false
                                                    serverConfigError = e.message ?: "Registracija nije uspjela."
                                                }
                                            }
                                        }
                                    } catch (e: Exception) {
                                        serverAuthBusy = false
                                        serverConfigError = e.message ?: "Adresa servera nije valjana."
                                    }
                                }
                            ) { Text("Registracija") }
                        }
                        Row {
                            TextButton(
                                enabled = !serverAuthBusy,
                                onClick = {
                                    serverAuthBusy = true
                                    serverConfigError = null
                                    serverAuthStatus = null
                                    try {
                                        NotesServerConfig.saveBaseUrl(context, serverUrlDraft)
                                        serverUrlDraft = NotesServerConfig.baseUrl(context)
                                        thread {
                                            try {
                                                val health = NotesServerApi(context).testConnection()
                                                android.os.Handler(android.os.Looper.getMainLooper()).post {
                                                    serverAuthBusy = false
                                                    val status = health.optString("status", "ok")
                                                    val version = health.optString("apiVersion")
                                                    serverAuthStatus = if (status == "ok") {
                                                        "Server je dostupan. API radi${if (version.isNotBlank()) " (v$version)" else ""}."
                                                    } else {
                                                        "Server odgovara, ali status nije očekivan: $status."
                                                    }
                                                }
                                            } catch (e: Exception) {
                                                android.os.Handler(android.os.Looper.getMainLooper()).post {
                                                    serverAuthBusy = false
                                                    serverConfigError = "Nije moguće spojiti se na server: ${e.message ?: "provjeri IP, port i Wi-Fi mrežu"}"
                                                }
                                            }
                                        }
                                    } catch (e: Exception) {
                                        serverAuthBusy = false
                                        serverConfigError = e.message ?: "Adresa servera nije valjana."
                                    }
                                }
                            ) { Text("Testiraj vezu") }
                            TextButton(
                                enabled = !serverAuthBusy,
                                onClick = {
                                    try {
                                        NotesServerConfig.saveBaseUrl(context, serverUrlDraft)
                                        serverUrlDraft = NotesServerConfig.baseUrl(context)
                                        serverConfigError = null
                                        showServerSettings = false
                                    } catch (e: Exception) {
                                        serverConfigError = e.message ?: "Adresa servera nije valjana."
                                    }
                                }
                            ) { Text("Spremi adresu") }
                        }
                    }
                },
                dismissButton = {
                    Row {
                        TextButton(onClick = {
                            serverUrlDraft = NotesServerConfig.DEFAULT_BASE_URL
                            serverConfigError = null
                            serverAuthStatus = null
                        }) { Text("Zadano") }
                        TextButton(onClick = { showServerSettings = false; serverConfigError = null }) { Text("Zatvori") }
                    }
                }
            )
        }

        if (!message.isNullOrBlank()) {
            AlertDialog(
                onDismissRequest = onDismissMessage,
                title = { Text("Google Drive") },
                text = { Text(message.orEmpty()) },
                confirmButton = { TextButton(onClick = onDismissMessage) { Text("OK") } }
            )
        }
    }
}


/**
 * AlarmManager alarms are cleared by Android after a reboot. Re-create future
 * reminders from the existing local JSON store without changing note/task data.
 */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED &&
            intent.action != Intent.ACTION_MY_PACKAGE_REPLACED
        ) return
        loadTasks(context).forEach { task ->
            if (!task.completed && task.reminderAt != null && task.reminderAt > System.currentTimeMillis()) {
                scheduleReminder(context, task)
            } else {
                cancelReminder(context, task.id)
            }
        }
        loadNotes(context).forEach { note ->
            if (!note.trashed && note.reminderAt != null && note.reminderAt > System.currentTimeMillis()) {
                scheduleNoteReminder(context, note)
            } else {
                cancelNoteReminder(context, note.id)
            }
        }
    }
}
