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
        val tabs = mutableListOf("Sve")
        try {
            val raw = prefs.getString(CATEGORIES, null)
            if (!raw.isNullOrBlank()) {
                val array = JSONArray(raw)
                for (i in 0 until array.length()) {
                    val tab = array.optString(i)
                    if (tab.isNotBlank() && tab != "Sve" && tab != "🗑 Otpad" && tab !in tabs) tabs += tab
                }
            }
        } catch (_: Exception) { }
        val savedTab = prefs.getString("list_widget_tab_$widgetId", "Sve") ?: "Sve"
        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(40, 36, 40, 24)
        }
        val title = TextView(this).apply { text = "Postavke widgeta"; textSize = 21f }
        val description = TextView(this).apply {
            text = "Odaberi koji tab bilješki će se prikazivati. Widget se može povećavati, smanjivati i pomicati po početnom zaslonu."
            textSize = 15f
            setPadding(0, 16, 0, 16)
        }
        val spinner = Spinner(this).apply {
            adapter = ArrayAdapter(this@NoteListWidgetConfigActivity, android.R.layout.simple_spinner_dropdown_item, tabs)
            setSelection(tabs.indexOf(savedTab).coerceAtLeast(0))
        }
        val save = Button(this).apply { text = "Spremi" }
        layout.addView(title); layout.addView(description); layout.addView(spinner); layout.addView(save)
        setContentView(layout)

        save.setOnClickListener {
            prefs.edit().putString("list_widget_tab_$widgetId", tabs[spinner.selectedItemPosition]).apply()
            NoteListWidgetProvider.update(this, AppWidgetManager.getInstance(this), widgetId)
            setResult(RESULT_OK, Intent().putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, widgetId))
            finish()
        }
    }
}