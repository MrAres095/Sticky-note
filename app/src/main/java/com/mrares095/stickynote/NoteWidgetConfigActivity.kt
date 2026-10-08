package com.mrares095.stickynote

import android.app.Activity
import android.appwidget.AppWidgetManager
import android.content.Intent
import android.os.Bundle
import android.widget.*
import org.json.JSONArray

class NoteWidgetConfigActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val widgetId = intent.getIntExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, AppWidgetManager.INVALID_APPWIDGET_ID)
        if (widgetId == AppWidgetManager.INVALID_APPWIDGET_ID) { finish(); return }

        val prefs = getSharedPreferences(PREFS, MODE_PRIVATE)
        val raw = prefs.getString(NOTES, null)
        val notes = mutableListOf<Note>()
        try {
            if (!raw.isNullOrBlank()) {
                val a = JSONArray(raw)
                for (i in 0 until a.length()) {
                    val o = a.getJSONObject(i)
                    if (!o.optBoolean("trashed", o.optString("category") == "🗑 Otpad")) {
                        notes += Note(
                            o.getLong("id"),
                            o.getString("title"),
                            o.getString("text"),
                            o.getString("category"),
                            o.optBoolean("favorite", false),
                            o.optLong("color", 0xFF252525),
                            o.optBoolean("pinned", false),
                            o.optString("keepId").takeIf { it.isNotBlank() },
                            o.optLong("updatedAt", System.currentTimeMillis()),
                            false
                        )
                    }
                }
            }
        } catch (_: Exception) {}

        val names = if (notes.isEmpty()) listOf("Nema bilješki") else notes.map { it.title.ifBlank { "Bez naslova" } }
        val layout = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(32,32,32,32) }
        val title = TextView(this).apply { text = "Odaberi bilješku za widget"; textSize = 20f }
        val spinner = Spinner(this).apply { adapter = ArrayAdapter(this@NoteWidgetConfigActivity, android.R.layout.simple_spinner_dropdown_item, names) }
        val save = Button(this).apply { text = "Spremi" }
        layout.addView(title); layout.addView(spinner); layout.addView(save)
        setContentView(layout)

        save.setOnClickListener {
            if (notes.isNotEmpty()) prefs.edit().putLong("widget_note_${widgetId}", notes[spinner.selectedItemPosition].id).apply()
            NoteWidgetProvider.update(this, AppWidgetManager.getInstance(this), widgetId)
            setResult(RESULT_OK, Intent().putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, widgetId))
            finish()
        }
    }
}