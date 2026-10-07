package com.mrares095.stickynote

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

data class Note(val title: String, val text: String, val category: String)

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { StickyNoteApp() }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun StickyNoteApp() {
    var categories by remember { mutableStateOf(listOf("Sve", "Osobno", "Recepti")) }
    var selected by remember { mutableStateOf("Sve") }
    var notes by remember { mutableStateOf(listOf(
        Note("Dobrodošli", "Ovo je tvoja nova Sticky & Note bilješka.", "Osobno")
    )) }
    var showAdd by remember { mutableStateOf(false) }
    var title by remember { mutableStateOf("") }
    var body by remember { mutableStateOf("") }

    MaterialTheme(colorScheme = darkColorScheme()) {
        Scaffold(
            topBar = { TopAppBar(title = { Text("Sticky & Note") }) },
            floatingActionButton = {
                FloatingActionButton(onClick = { showAdd = true }) { Text("+") }
            }
        ) { padding ->
            Column(Modifier.padding(padding).fillMaxSize()) {
                ScrollableTabRow(selectedTabIndex = categories.indexOf(selected).coerceAtLeast(0)) {
                    categories.forEach { category ->
                        Tab(selected = selected == category, onClick = { selected = category }, text = { Text(category) })
                    }
                    Tab(selected = false, onClick = {
                        val n = "Nova"
                        if (!categories.contains(n)) categories = categories + n
                    }, text = { Text("+") })
                }
                val shown = if (selected == "Sve") notes else notes.filter { it.category == selected }
                LazyVerticalGrid(columns = GridCells.Adaptive(160.dp), contentPadding = PaddingValues(12.dp)) {
                    items(shown) { note ->
                        Card(Modifier.padding(6.dp)) {
                            Column(Modifier.padding(14.dp)) {
                                Text(note.title, style = MaterialTheme.typography.titleMedium)
                                Spacer(Modifier.height(8.dp))
                                Text(note.text, maxLines = 8)
                            }
                        }
                    }
                }
            }
        }
        if (showAdd) {
            AlertDialog(
                onDismissRequest = { showAdd = false },
                title = { Text("Nova bilješka") },
                text = {
                    Column {
                        OutlinedTextField(title, { title = it }, label = { Text("Naslov") })
                        Spacer(Modifier.height(8.dp))
                        OutlinedTextField(body, { body = it }, label = { Text("Bilješka") }, minLines = 4)
                    }
                },
                confirmButton = {
                    TextButton(onClick = {
                        if (title.isNotBlank() || body.isNotBlank()) notes = notes + Note(title.ifBlank { "Bez naslova" }, body, if (selected == "Sve") "Osobno" else selected)
                        title = ""; body = ""; showAdd = false
                    }) { Text("Spremi") }
                },
                dismissButton = { TextButton(onClick = { showAdd = false }) { Text("Odustani") } }
            )
        }
    }
}
