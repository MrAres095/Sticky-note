package com.mrares095.stickynote

import android.content.Context
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import org.json.JSONArray
import org.json.JSONObject

data class Note(val id: Long, val title: String, val text: String, val category: String)

private const val PREFS = "sticky_note_data"
private const val NOTES = "notes"
private const val CATEGORIES = "categories"

private fun loadCategories(context: Context): List<String> {
    val raw = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(CATEGORIES, null) ?: return listOf("Sve", "Osobno", "Recepti")
    val a = JSONArray(raw); return List(a.length()) { a.getString(it) }
}
private fun saveCategories(context: Context, values: List<String>) {
    val a = JSONArray(); values.forEach { a.put(it) }
    context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(CATEGORIES, a.toString()).apply()
}
private fun loadNotes(context: Context): List<Note> {
    val raw = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(NOTES, null) ?: return listOf(Note(1L, "Dobrodošli", "Ovo je tvoja nova Sticky & Note bilješka.", "Osobno"))
    val a = JSONArray(raw); return List(a.length()) {
        val o=a.getJSONObject(it); Note(o.getLong("id"), o.getString("title"), o.getString("text"), o.getString("category"))
    }
}
private fun saveNotes(context: Context, values: List<Note>) {
    val a=JSONArray(); values.forEach { n -> a.put(JSONObject().apply { put("id",n.id); put("title",n.title); put("text",n.text); put("category",n.category) }) }
    context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(NOTES,a.toString()).apply()
}

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) { super.onCreate(savedInstanceState); setContent { StickyNoteApp(this) } }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun StickyNoteApp(context: Context) {
    var categories by remember { mutableStateOf(loadCategories(context)) }
    var selected by remember { mutableStateOf(categories.first()) }
    var notes by remember { mutableStateOf(loadNotes(context)) }
    var editing by remember { mutableStateOf<Note?>(null) }
    var showAddTab by remember { mutableStateOf(false) }
    var newTabName by remember { mutableStateOf("") }
    var showRenameTab by remember { mutableStateOf<String?>(null) }
    var renameText by remember { mutableStateOf("") }

    fun persistNotes(v: List<Note>) { notes=v; saveNotes(context,v) }
    fun persistCategories(v: List<String>) { categories=v; saveCategories(context,v); if (!v.contains(selected)) selected=v.first() }

    MaterialTheme(colorScheme = darkColorScheme()) {
        Scaffold(
            topBar = { TopAppBar(title={ Text("Sticky & Note") }) },
            floatingActionButton = { FloatingActionButton(onClick={ editing=Note(System.currentTimeMillis(),"","",if(selected=="Sve") "Osobno" else selected) }) { Text("+") } }
        ) { padding ->
            Column(Modifier.padding(padding).fillMaxSize()) {
                ScrollableTabRow(selectedTabIndex=categories.indexOf(selected).coerceAtLeast(0)) {
                    categories.forEach { category ->
                        Tab(selected=selected==category,onClick={selected=category},text={Text(category)},
                            onDoubleClick = { if(category!="Sve"){ showRenameTab=category; renameText=category } })
                    }
                    Tab(selected=false,onClick={showAddTab=true},text={Text("+")})
                }
                Text("Dugo pritisni/dvoklikni naziv taba za preimenovanje", style=MaterialTheme.typography.labelSmall, modifier=Modifier.padding(horizontal=16.dp,vertical=6.dp))
                val shown=if(selected=="Sve") notes else notes.filter{it.category==selected}
                LazyVerticalGrid(columns=GridCells.Adaptive(160.dp),contentPadding=PaddingValues(12.dp)) {
                    items(shown,key={it.id}) { note ->
                        Card(Modifier.padding(6.dp)) {
                            Column(Modifier.padding(14.dp)) {
                                Text(note.title.ifBlank{"Bez naslova"},style=MaterialTheme.typography.titleMedium)
                                Spacer(Modifier.height(8.dp)); Text(note.text,maxLines=8)
                                Spacer(Modifier.height(10.dp))
                                Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.End) {
                                    TextButton(onClick={editing=note}){Text("Uredi")}
                                    TextButton(onClick={persistNotes(notes.filterNot{it.id==note.id})}){Text("Obriši")}
                                }
                            }
                        }
                    }
                }
            }
        }
        editing?.let { note ->
            var title by remember(note.id){mutableStateOf(note.title)}
            var body by remember(note.id){mutableStateOf(note.text)}
            var cat by remember(note.id){mutableStateOf(if(note.category=="") "Osobno" else note.category)}
            AlertDialog(onDismissRequest={editing=null},title={Text(if(note.title.isBlank())"Nova bilješka" else "Uredi bilješku")},text={
                Column {
                    OutlinedTextField(title,{title=it},label={Text("Naslov")},singleLine=true)
                    Spacer(Modifier.height(8.dp)); OutlinedTextField(body,{body=it},label={Text("Bilješka")},minLines=5)
                    Spacer(Modifier.height(8.dp)); Text("Tab: $cat")
                    Row { categories.filter{it!="Sve"}.forEach { c -> TextButton(onClick={cat=c}){Text(c)} } }
                }
            },confirmButton={TextButton(onClick={
                if(title.isNotBlank()||body.isNotBlank()) persistNotes((notes.filterNot{it.id==note.id})+Note(note.id,title,body,cat))
                editing=null
            }){Text("Spremi")}},dismissButton={TextButton(onClick={editing=null}){Text("Odustani")}})
        }
        if(showAddTab) AlertDialog(onDismissRequest={showAddTab=false},title={Text("Novi tab")},text={OutlinedTextField(newTabName,{newTabName=it},label={Text("Naziv")},singleLine=true)},confirmButton={TextButton(onClick={
            val n=newTabName.trim(); if(n.isNotEmpty()&&!categories.contains(n)){persistCategories(categories+n);selected=n};newTabName="";showAddTab=false
        }){Text("Dodaj")}},dismissButton={TextButton(onClick={showAddTab=false}){Text("Odustani")}})
        showRenameTab?.let { old ->
            AlertDialog(onDismissRequest={showRenameTab=null},title={Text("Preimenuj tab")},text={OutlinedTextField(renameText,{renameText=it},singleLine=true)},confirmButton={TextButton(onClick={
                val n=renameText.trim(); if(n.isNotEmpty()&&n!="Sve"&&!categories.contains(n)){persistCategories(categories.map{if(it==old)n else it});persistNotes(notes.map{if(it.category==old)it.copy(category=n)else it});if(selected==old)selected=n};showRenameTab=null
            }){Text("Spremi")}},dismissButton={TextButton(onClick={showRenameTab=null}){Text("Odustani")}})
        }
    }
}
