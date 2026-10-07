package com.mrares095.stickynote

import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.widget.RemoteViews
import org.json.JSONArray

class NoteWidgetProvider : AppWidgetProvider() {
    override fun onUpdate(context: Context, manager: AppWidgetManager, ids: IntArray) {
        ids.forEach { update(context, manager, it) }
    }

    override fun onDeleted(context: Context, ids: IntArray) {
        val edit = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
        ids.forEach { edit.remove("widget_note_${it}") }
        edit.apply()
    }

    companion object {
        fun update(context: Context, manager: AppWidgetManager, widgetId: Int) {
            val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            val raw = prefs.getString(NOTES, null)
            val notes = mutableListOf<Note>()
            try {
                if (!raw.isNullOrBlank()) {
                    val a = JSONArray(raw)
                    for (i in 0 until a.length()) {
                        val o = a.getJSONObject(i)
                        notes += Note(o.getLong("id"), o.getString("title"), o.getString("text"), o.getString("category"))
                    }
                }
            } catch (_: Exception) {}
            val selectedId = prefs.getLong("widget_note_${widgetId}", -1L)
            val note = notes.firstOrNull { it.id == selectedId } ?: notes.firstOrNull()
            val views = RemoteViews(context.packageName, R.layout.widget_note)
            views.setTextViewText(R.id.widget_title, note?.title?.ifBlank { "Bez naslova" } ?: "Sticky & Note")
            views.setTextViewText(R.id.widget_text, note?.text ?: "Nema spremljenih bilješki.")
            manager.updateAppWidget(widgetId, views)
        }

        fun updateAll(context: Context) {
            val manager = AppWidgetManager.getInstance(context)
            val component = ComponentName(context, NoteWidgetProvider::class.java)
            manager.getAppWidgetIds(component).forEach { update(context, manager, it) }
        }
    }
}