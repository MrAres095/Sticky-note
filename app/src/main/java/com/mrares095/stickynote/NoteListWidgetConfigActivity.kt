package com.mrares095.stickynote

import android.app.Activity
import android.appwidget.AppWidgetManager
import android.content.Intent
import android.os.Bundle
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.LinearLayout
import android.widget.Spinner
import android.widget.TextView
import org.json.JSONArray

class NoteListWidgetConfigActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val widgetId = intent.getIntExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, AppWidgetManager.INVALID_APPWIDGET_ID)
        if (widgetId == AppWidgetManager.INVALID_APPWIDGET_ID) { finish(); return }

        val prefs = getSharedPreferences(PREFS, MODE_PRIVATE)
        data class Choice(val label: String, val tab: String, val listId: String = "")
        val choices = mutableListOf(Choice("Sve bilješke", "Sve"))
        try {
            val raw = prefs.getString(CATEGORIES, null)
            if (!raw.isNullOrBlank()) {
                val array = JSONArray(raw)
                for (i in 0 until array.length()) {
                    val tab = array.optString(i)
                    if (tab.isNotBlank() && tab != "Sve" && tab != "🗑 Otpad") choices += Choice(tab, tab)
                }
            }
        } catch (_: Exception) { }
        try {
            val rawLists = prefs.getString("todo_lists", null)
            if (!rawLists.isNullOrBlank()) {
                val array = JSONArray(rawLists)
                for (i in 0 until array.length()) {
                    val obj = array.optJSONObject(i) ?: continue
                    val id = obj.optString("id")
                    val name = obj.optString("title")
                    if (id.isNotBlank() && name.isNotBlank()) choices += Choice("To-do: $name", "To-do lista", id)
                }
            }
        } catch (_: Exception) { }
        if (choices.none { it.tab == "To-do lista" }) choices += Choice("To-do: Moj prvi popis", "To-do lista", "default")
        val savedTab = prefs.getString("list_widget_tab_$widgetId", "Sve") ?: "Sve"
        val savedListId = prefs.getString("list_widget_todo_id_$widgetId", "default") ?: "default"
        val savedIndex = choices.indexOfFirst { it.tab == savedTab && (it.tab != "To-do lista" || it.listId == savedListId) }.coerceAtLeast(0)
        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(40, 36, 40, 24)
        }
        val title = TextView(this).apply { text = "Postavke widgeta"; textSize = 21f }
        val description = TextView(this).apply {
            text = "Odaberi bilješke ili konkretnu To-do listu za prikaz. Widget se može povećavati, smanjivati i pomicati po početnom zaslonu."
            textSize = 15f
            setPadding(0, 16, 0, 16)
        }
        val spinner = Spinner(this).apply {
            adapter = ArrayAdapter(this@NoteListWidgetConfigActivity, android.R.layout.simple_spinner_dropdown_item, choices.map { it.label })
            setSelection(savedIndex)
        }
        val save = Button(this).apply { text = "Spremi" }
        layout.addView(title); layout.addView(description); layout.addView(spinner); layout.addView(save)
        save.setOnClickListener {
            val choice = choices[spinner.selectedItemPosition.coerceIn(0, choices.lastIndex)]
            prefs.edit()
                .putString("list_widget_tab_$widgetId", choice.tab)
                .putString("list_widget_todo_id_$widgetId", choice.listId.ifBlank { "default" })
                .apply()
            NoteListWidgetProvider.update(this, AppWidgetManager.getInstance(this), widgetId)
            setResult(RESULT_OK, Intent().putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, widgetId))
            finish()
        }
        setContentView(layout)

        save.setOnClickListener {
            prefs.edit().putString("list_widget_tab_$widgetId", tabs[spinner.selectedItemPosition]).apply()
            NoteListWidgetProvider.update(this, AppWidgetManager.getInstance(this), widgetId)
            setResult(RESULT_OK, Intent().putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, widgetId))
            finish()
        }
    }
}