package com.mrares095.stickynote

import android.app.PendingIntent
import android.content.Context
import android.content.IntentSender
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.*
import androidx.compose.foundation.clickable
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.google.android.gms.auth.api.identity.AuthorizationRequest
import com.google.android.gms.auth.api.identity.AuthorizationResult
import com.google.android.gms.auth.api.identity.Identity
import com.google.android.gms.common.api.Scope
import org.json.JSONArray
import org.json.JSONObject
import kotlin.concurrent.thread

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
    val trashed: Boolean = false
)

const val PREFS = "sticky_note_data"
const val NOTES = "notes"
const val CATEGORIES = "categories"
private const val DRIVE_SCOPE = "https://www.googleapis.com/auth/drive.appdata"

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
            o.optBoolean("trashed", o.optString("category") == "🗑 Otpad")
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
        })
    }
    context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(NOTES, a.toString()).apply()
}

class MainActivity : ComponentActivity() {
    private var driveAccessToken: String? = null
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
                    showDriveMessage("Google autorizacija nije uspjela.")
                }
            } else {
                showDriveMessage("Google autorizacija je otkazana.")
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
            .addOnFailureListener { showDriveMessage("Google autorizacija nije dostupna: ${it.message}") }
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
                        TextButton(onClick = onConnectDrive) { Text(if (driveConnected) "Keep ✓" else "Google Drive") }
                        TextButton(onClick = onSyncDrive) { Text("Sync") }
                        TextButton(onClick = onCheckUpdate) { Text("Ažuriraj") }
                    }
                )
            },
            floatingActionButton = {
                FloatingActionButton(onClick = {
                    editing = Note(
                        System.currentTimeMillis(),
                        "",
                        "",
                        if (selected == "Sve") "Osobno" else selected
                    )
                }) { Text("+") }
            }
        ) { padding ->
            Column(Modifier.padding(padding).fillMaxSize()) {
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

        editing?.let { note ->
            var title by remember(note.id) { mutableStateOf(note.title) }
            var body by remember(note.id) { mutableStateOf(note.text) }
            var cat by remember(note.id) { mutableStateOf(if (note.category == "") "Osobno" else note.category) }
            var noteColor by remember(note.id) { mutableLongStateOf(note.color) }

            AlertDialog(
                onDismissRequest = { editing = null },
                title = { Text(if (note.title.isBlank()) "Nova bilješka" else "Uredi bilješku") },
                text = {
                    Column {
                        OutlinedTextField(title, { title = it }, label = { Text("Naslov") }, singleLine = true)
                        Spacer(Modifier.height(8.dp))
                        OutlinedTextField(body, { body = it }, label = { Text("Bilješka") }, minLines = 5)
                        Spacer(Modifier.height(8.dp))
                        Text("Tab: $cat")
                        Spacer(Modifier.height(8.dp))
                        Text("Boja bilješke")
                        Row {
                            listOf(0xFF252525L, 0xFF5A3D31L, 0xFF5B4B1FL, 0xFF3E5739L, 0xFF304B63L, 0xFF563E63L, 0xFF633C4AL).forEach { color ->
                                TextButton(onClick = { noteColor = color }) { Text("●", color = Color(color)) }
                            }
                        }
                        Row {
                            categories.filter { it != "Sve" }.forEach { c ->
                                TextButton(onClick = { cat = c }) { Text(c) }
                            }
                        }
                    }
                },
                confirmButton = {
                    TextButton(onClick = {
                        if (title.isNotBlank() || body.isNotBlank()) {
                            persistNotes(
                                (notes.filterNot { it.id == note.id }) + note.copy(
                                    title = title,
                                    text = body,
                                    category = if (cat == "🗑 Otpad") "Osobno" else cat,
                                    color = noteColor,
                                    trashed = false,
                                    updatedAt = System.currentTimeMillis()
                                )
                            )
                        }
                        editing = null
                    }) { Text("Spremi") }
                },
                dismissButton = { TextButton(onClick = { editing = null }) { Text("Odustani") } }
            )
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
