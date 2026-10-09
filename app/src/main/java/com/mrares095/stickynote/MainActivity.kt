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
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.items
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

data class TodoTask(
    val id: Long,
    val title: String,
    val completed: Boolean = false,
    val reminderAt: Long? = null,
    val imageUri: String? = null
)

const val TASKS = "todo_tasks"

private fun loadTasks(context: Context): List<TodoTask> {
    val raw = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(TASKS, null) ?: return emptyList()
    return try {
        val a = JSONArray(raw)
        List(a.length()) { i ->
            val o = a.getJSONObject(i)
            TodoTask(o.getLong("id"), o.getString("title"), o.optBoolean("completed", false), o.optLong("reminderAt").takeIf { it > 0L }, o.optString("imageUri").takeIf { it.isNotBlank() && it != "null" })
        }
    } catch (_: Exception) { emptyList() }
}

private fun saveTasks(context: Context, tasks: List<TodoTask>) {
    val a = JSONArray()
    tasks.forEach { task -> a.put(JSONObject().apply {
        put("id", task.id); put("title", task.title); put("completed", task.completed); put("reminderAt", task.reminderAt ?: 0L); put("imageUri", task.imageUri ?: "")
    }) }
    context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(TASKS, a.toString()).apply()
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
    val reminderAt: Long? = null
)

const val PREFS = "sticky_note_data"
const val NOTES = "notes"
const val CATEGORIES = "categories"
private const val DRIVE_SCOPE = "https://www.googleapis.com/auth/drive.file"

private fun loadCategories(context: Context): List<String> {
    val raw = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(CATEGORIES, null)
        ?: return listOf("Sve", "Osobno", "Recepti")
    val a = JSONArray(raw)
    return List(a.length()) { a.getString(it) }
}

private fun saveCategories(context: Context, values: List<String>) {
    val a = JSONArray()
    values.forEach { a.put(it) }
    context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(CATEGORIES, a.toString()).apply()
}

private fun loadNotes(context: Context): List<Note> {
    val raw = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(NOTES, null)
        ?: return listOf(Note(1L, "Dobrodošli", "Ovo je tvoja nova Sticky & Note bilješka.", "Osobno"))
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
            o.optLong("reminderAt").takeIf { it > 0L }
        )
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
        })
    }
    context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(NOTES, a.toString()).apply()
}

class MainActivity : ComponentActivity() {
    private var driveAccessToken: String? by mutableStateOf(null)
    private var driveStatusMessage by mutableStateOf<String?>(null)
    private var syncAfterAuthorization = false

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
        setContent {
            StickyNoteApp(
                context = this,
                driveConnected = driveAccessToken != null,
                message = driveStatusMessage,
                onDismissMessage = { driveStatusMessage = null },
                onConnectDrive = { authorizeDrive(false) },
                onSyncDrive = { authorizeDrive(true) },
                onCheckUpdate = { checkForUpdate() },
                openNoteId = intent.getLongExtra("open_note_id", -1L).takeIf { it > 0L }
            )
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

        thread {
            try {
                val remote = GoogleDriveSync.downloadState(token)
                val merged: Pair<List<Note>, List<String>> = if (remote == null) {
                    GoogleDriveSync.uploadState(token, current, currentCategories)
                    current to currentCategories
                } else {
                    val prefs = getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                    val firstSync = !prefs.getBoolean("drive_sync_initialized", false)

                    val localIsFreshInstall = current.size == 1 &&
                        current.first().id == 1L &&
                        current.first().title == "Dobrodošli" &&
                        current.first().text.contains("Sticky & Note")

                    val notes = if (firstSync && localIsFreshInstall && remote.notes.isNotEmpty()) {
                        remote.notes
                    } else {
                        val byId = LinkedHashMap<Long, Note>()
                        remote.notes.forEach { byId[it.id] = it }
                        current.forEach { local ->
                            val cloud = byId[local.id]
                            when {
                                cloud == null -> byId[local.id] = local
                                local.updatedAt > cloud.updatedAt + 1000L -> byId[local.id] = local
                                else -> byId[local.id] = cloud
                            }
                        }
                        byId.values.toList()
                    }

                    val categories = (currentCategories + remote.categories)
                        .distinct()
                        .ifEmpty { listOf("Sve", "Osobno", "Recepti") }

                    GoogleDriveSync.uploadState(token, notes, categories)
                    notes to categories
                }

                runOnUiThread {
                    saveNotes(this, merged.first)
                    saveCategories(this, merged.second)
                    getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                        .edit()
                        .putBoolean("drive_sync_initialized", true)
                        .apply()
                    NoteWidgetProvider.updateAll(this)
                    showDriveMessage("Google Drive sinkronizacija završena. " + merged.first.size + " bilješki.")
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
    onCheckUpdate: () -> Unit,
    openNoteId: Long? = null
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
    var confirmPermanentDelete by remember { mutableStateOf<Note?>(null) }
    var confirmEmptyTrash by remember { mutableStateOf(false) }
    var confirmDeleteCategory by remember { mutableStateOf<String?>(null) }
    var showTasks by remember { mutableStateOf(false) }
    var tasks by remember { mutableStateOf(loadTasks(context)) }
    var showAddTask by remember { mutableStateOf(false) }
    var taskTitle by remember { mutableStateOf("") }
    var taskReminderAt by remember { mutableStateOf<Long?>(null) }
    var taskImageUri by remember { mutableStateOf<String?>(null) }
    val taskImagePicker = androidx.activity.compose.rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            try { context.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION) } catch (_: Exception) { }
            taskImageUri = uri.toString()
        }
    }

    LaunchedEffect(showTasks) {
        if (showTasks && Build.VERSION.SDK_INT >= 33 && context is ComponentActivity && context.checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            context.requestPermissions(arrayOf(android.Manifest.permission.POST_NOTIFICATIONS), 101)
        }
    }

    LaunchedEffect(openNoteId, notes) {
        if (openNoteId != null && editing == null) {
            notes.firstOrNull { it.id == openNoteId }?.let { editing = it }
        }
    }

    fun persistNotes(v: List<Note>) {
        notes = v
        saveNotes(context, v)
        NoteWidgetProvider.updateAll(context)
    }

    fun deleteNote(note: Note) {
        val now = System.currentTimeMillis()
        persistNotes(notes.map { if (it.id == note.id) it.copy(trashed = true, updatedAt = now) else it })
    }

    fun restoreNote(note: Note) {
        val now = System.currentTimeMillis()
        persistNotes(notes.map {
            if (it.id == note.id) it.copy(
                category = if (it.category == "🗑 Otpad" || it.category == "Sve") "Osobno" else it.category,
                trashed = false,
                updatedAt = now
            ) else it
        })
    }

    fun permanentlyDeleteNote(note: Note) {
        persistNotes(notes.filterNot { it.id == note.id })
    }

    fun emptyTrash() {
        persistNotes(notes.filterNot { it.trashed })
    }

    fun persistCategories(v: List<String>) {
        categories = v
        saveCategories(context, v)
        if (!v.contains(selected)) selected = v.first()
    }

    fun deleteCategory(category: String) {
        if (category == "Sve") return
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
                        TextButton(onClick = { showTasks = !showTasks }) { Text(if (showTasks) "Bilješke" else "To-do / podsjetnici") }
                        TextButton(onClick = onConnectDrive) { Text(if (driveConnected) "Google Drive ✓" else "Google Drive") }
                        TextButton(onClick = onSyncDrive) { Text("Sync") }
                        TextButton(onClick = onCheckUpdate) { Text("Ažuriraj") }
                    }
                )
            },
            floatingActionButton = {
                FloatingActionButton(onClick = {
                    if (showTasks) {
                        taskTitle = ""
                        taskReminderAt = null
                        taskImageUri = null
                        showAddTask = true
                    } else editing = Note(
                        System.currentTimeMillis(),
                        "",
                        "",
                        if (selected == "Sve") "Osobno" else selected
                    )
                }) { Text("+") }
            }
        ) { padding ->
            Column(Modifier.padding(padding).fillMaxSize()) {
                if (showTasks) {
                    Row(Modifier.fillMaxWidth().padding(16.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text("To-do lista", style = MaterialTheme.typography.titleLarge)
                        Text("${tasks.count { !it.completed }} preostalo")
                    }
                    if (tasks.isEmpty()) Text("Još nema zadataka. Dodaj prvi pomoću +.", Modifier.padding(16.dp))
                    androidx.compose.foundation.lazy.LazyColumn(Modifier.fillMaxSize().padding(horizontal = 12.dp)) {
                        items(tasks.sortedWith(compareBy<TodoTask> { it.completed }.thenBy { it.reminderAt ?: Long.MAX_VALUE }), key = { it.id }) { task ->
                            Card(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                                Row(Modifier.fillMaxWidth().padding(12.dp), verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                                    Checkbox(checked = task.completed, onCheckedChange = { checked ->
                                        val updated = tasks.map { if (it.id == task.id) it.copy(completed = checked) else it }
                                        tasks = updated; saveTasks(context, updated)
                                        if (checked) cancelReminder(context, task.id) else task.reminderAt?.let { scheduleReminder(context, task) }
                                    })
                                    Column(Modifier.weight(1f)) {
                                        Text(task.title, style = MaterialTheme.typography.bodyLarge, color = if (task.completed) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface)
                                        task.reminderAt?.let { at -> Text("Podsjetnik: " + java.text.SimpleDateFormat("dd.MM.yyyy. HH:mm", java.util.Locale.getDefault()).format(java.util.Date(at)), style = MaterialTheme.typography.bodySmall) }
                                        task.imageUri?.let { uri ->
                                            val thumbnail = remember(uri) { loadTaskThumbnail(context, uri) }
                                            if (thumbnail != null) Image(bitmap = thumbnail.asImageBitmap(), contentDescription = "Slika zadatka", modifier = Modifier.fillMaxWidth().heightIn(max = 180.dp), contentScale = ContentScale.Fit)
                                        }
                                    }
                                    TextButton(onClick = {
                                        cancelReminder(context, task.id)
                                        val updated = tasks.filterNot { it.id == task.id }; tasks = updated; saveTasks(context, updated)
                                    }) { Text("Obriši") }
                                }
                            }
                        }
                    }
                } else {
                ScrollableTabRow(selectedTabIndex = categories.indexOf(selected).coerceAtLeast(0)) {
                    categories.forEach { category ->
                        Tab(selected = selected == category, onClick = { selected = category }, text = { Text(category) })
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
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Row {
                        TextButton(onClick = { favoritesOnly = !favoritesOnly }) {
                            Text(if (favoritesOnly) "★ Favoriti" else "☆ Svi")
                        }
                        TextButton(onClick = { pinnedOnly = !pinnedOnly }) {
                            Text(if (pinnedOnly) "📌 Prikvačeno" else "📌 Sve")
                        }
                        TextButton(onClick = { trashOnly = !trashOnly }) {
                            Text(if (trashOnly) "🗑 Otpad" else "🗑")
                        }
                    }
                    if (selected != "Sve") {
                        TextButton(onClick = {
                            showRenameTab = selected
                            renameText = selected
                        }) { Text("Preimenuj") }
                        TextButton(onClick = { confirmDeleteCategory = selected }) { Text("Obriši") }
                    }
                    if (trashOnly && notes.any { it.trashed }) {
                        TextButton(onClick = { confirmEmptyTrash = true }) { Text("Isprazni otpad") }
                    }
                }

                val categoryNotes = when {
                    trashOnly -> notes.filter { it.trashed }
                    selected == "Sve" -> notes.filter { !it.trashed }
                    else -> notes.filter { !it.trashed && it.category == selected }
                }
                val q = search.trim().lowercase()
                val searched = if (q.isEmpty()) categoryNotes else categoryNotes.filter {
                    it.title.lowercase().contains(q) || it.text.lowercase().contains(q)
                }
                val filtered = searched
                    .let { if (favoritesOnly) it.filter(Note::favorite) else it }
                    .let { if (pinnedOnly) it.filter(Note::pinned) else it }
                    .sortedWith(compareByDescending<Note> { it.pinned }.thenByDescending { it.favorite }.thenByDescending { it.updatedAt })

                LazyVerticalGrid(columns = GridCells.Adaptive(160.dp), contentPadding = PaddingValues(12.dp)) {
                    items(filtered, key = { it.id }) { note ->
                        Card(
                            Modifier.padding(6.dp).clickable { editing = note },
                            colors = CardDefaults.cardColors(containerColor = Color(note.color))
                        ) {
                            Column(Modifier.padding(14.dp)) {
                                Text(note.title.ifBlank { "Bez naslova" }, style = MaterialTheme.typography.titleMedium)
                                Spacer(Modifier.height(8.dp))
                                Text(note.text, maxLines = 8)
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
                                    } else {
                                        TextButton(onClick = { editing = note }) { Text("Uredi") }
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

        if (showAddTask) {
            AlertDialog(
                onDismissRequest = { showAddTask = false },
                title = { Text("Novi zadatak") },
                text = {
                    Column {
                        OutlinedTextField(taskTitle, { taskTitle = it }, label = { Text("Što treba napraviti?") }, singleLine = true)
                        Spacer(Modifier.height(12.dp))
                        Text(if (taskReminderAt == null) "Bez podsjetnika" else "Podsjetnik: " + java.text.SimpleDateFormat("dd.MM.yyyy. HH:mm", java.util.Locale.getDefault()).format(java.util.Date(taskReminderAt!!)))
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
                                val now = java.util.Calendar.getInstance()
                                DatePickerDialog(context, { _, year, month, day ->
                                    val chosen = java.util.Calendar.getInstance().apply { set(year, month, day) }
                                    TimePickerDialog(context, { _, hour, minute ->
                                        chosen.set(java.util.Calendar.HOUR_OF_DAY, hour); chosen.set(java.util.Calendar.MINUTE, minute); chosen.set(java.util.Calendar.SECOND, 0); chosen.set(java.util.Calendar.MILLISECOND, 0)
                                        taskReminderAt = chosen.timeInMillis
                                    }, now.get(java.util.Calendar.HOUR_OF_DAY), now.get(java.util.Calendar.MINUTE), true).show()
                                }, now.get(java.util.Calendar.YEAR), now.get(java.util.Calendar.MONTH), now.get(java.util.Calendar.DAY_OF_MONTH)).show()
                            }) { Text("Postavi datum i vrijeme") }
                            TextButton(onClick = { taskReminderAt = null }) { Text("Ukloni") }
                        }
                    }
                },
                confirmButton = {
                    TextButton(onClick = {
                        val title = taskTitle.trim()
                        if (title.isNotEmpty()) {
                            val task = TodoTask(System.currentTimeMillis(), title, false, taskReminderAt, taskImageUri)
                            val updated = tasks + task; tasks = updated; saveTasks(context, updated)
                            task.reminderAt?.let { scheduleReminder(context, task) }
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

            androidx.compose.ui.window.Dialog(
                onDismissRequest = { editing = null },
                properties = androidx.compose.ui.window.DialogProperties(usePlatformDefaultWidth = false)
            ) {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = Color(0xFF17191D)
                ) {
                    Column(Modifier.fillMaxSize()) {
                        Row(
                            Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 6.dp),
                            verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            TextButton(onClick = { editing = null }) { Text("Natrag") }
                            Text(
                                if (note.title.isBlank()) "Nova bilješka" else "Bilješka",
                                style = MaterialTheme.typography.titleLarge
                            )
                            TextButton(onClick = {
                                if (title.isNotBlank() || body.isNotBlank()) {
                                    val saved = note.copy(
                                        title = title,
                                        text = body,
                                        category = if (cat == "🗑 Otpad" || cat == "Sve") "Osobno" else cat,
                                        color = noteColor,
                                        trashed = false,
                                        reminderAt = noteReminderAt,
                                        updatedAt = System.currentTimeMillis()
                                    )
                                    persistNotes((notes.filterNot { it.id == note.id }) + saved)
                                    if (noteReminderAt != null && noteReminderAt!! > System.currentTimeMillis()) {
                                        scheduleNoteReminder(context, saved)
                                    } else {
                                        cancelNoteReminder(context, note.id)
                                    }
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
                            Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp)
                        ) {
                            Text("Kategorija", style = MaterialTheme.typography.labelLarge)
                            Row(
                                Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                categories.filter { it != "Sve" }.forEach { category ->
                                    FilterChip(
                                        selected = cat == category,
                                        onClick = { cat = category },
                                        label = { Text(category) }
                                    )
                                }
                            }
                            Spacer(Modifier.height(4.dp))
                            Text("Boja bilješke", style = MaterialTheme.typography.labelLarge)
                            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                                listOf(0xFF252525L, 0xFF5A3D31L, 0xFF5B4B1FL, 0xFF3E5739L, 0xFF304B63L, 0xFF563E63L, 0xFF633C4AL).forEach { color ->
                                    Surface(
                                        modifier = Modifier.size(32.dp).clickable { noteColor = color },
                                        shape = androidx.compose.foundation.shape.CircleShape,
                                        color = Color(color),
                                        border = if (noteColor == color) androidx.compose.foundation.BorderStroke(2.dp, Color.White) else null
                                    ) {}
                                }
                            }
                            Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                                TextButton(onClick = {
                                    val now = java.util.Calendar.getInstance()
                                    DatePickerDialog(context, { _, year, month, day ->
                                        val chosen = java.util.Calendar.getInstance().apply { set(year, month, day) }
                                        TimePickerDialog(context, { _, hour, minute ->
                                            chosen.set(java.util.Calendar.HOUR_OF_DAY, hour)
                                            chosen.set(java.util.Calendar.MINUTE, minute)
                                            chosen.set(java.util.Calendar.SECOND, 0)
                                            chosen.set(java.util.Calendar.MILLISECOND, 0)
                                            noteReminderAt = chosen.timeInMillis
                                        }, now.get(java.util.Calendar.HOUR_OF_DAY), now.get(java.util.Calendar.MINUTE), true).show()
                                    }, now.get(java.util.Calendar.YEAR), now.get(java.util.Calendar.MONTH), now.get(java.util.Calendar.DAY_OF_MONTH)).show()
                                }) { Text(if (noteReminderAt == null) "Dodaj podsjetnik" else "Promijeni podsjetnik") }
                                TextButton(onClick = { noteReminderAt = null }) { Text("Ukloni") }
                            }
                            noteReminderAt?.let { at ->
                                Text(
                                    "Podsjetnik: " + java.text.SimpleDateFormat("dd.MM.yyyy. HH:mm", java.util.Locale.getDefault()).format(java.util.Date(at)),
                                    style = MaterialTheme.typography.bodySmall
                                )
                            }
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
                            persistCategories(categories.map { if (it == old) n else it })
                            persistNotes(notes.map { if (it.category == old) it.copy(category = n) else it })
                            if (selected == old) selected = n
                        }
                        showRenameTab = null
                    }) { Text("Spremi") }
                },
                dismissButton = { TextButton(onClick = { showRenameTab = null }) { Text("Odustani") } }
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
