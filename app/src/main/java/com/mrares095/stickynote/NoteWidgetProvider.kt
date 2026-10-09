package com.mrares095.stickynote

import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Intent
import android.app.PendingIntent
import android.content.Context
import android.widget.RemoteViews
import org.json.JSONArray

class NoteWidgetProvider : AppWidgetProvider() {
    override fun onUpdate(context: Context, manager: AppWidgetManager, ids: IntArray) {
        ids.forEach { update(context, manager, it) }
    }

    override fun onAppWidgetOptionsChanged(context: Context, manager: AppWidgetManager, widgetId: Int, newOptions: android.os.Bundle) {
        update(context, manager, widgetId)
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
            val selectedId = prefs.getLong("widget_note_${widgetId}", -1L)
            val note = notes.firstOrNull { it.id == selectedId } ?: notes.firstOrNull()
            val options = manager.getAppWidgetOptions(widgetId)
            val minWidth = options.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH, 110)
            val minHeight = options.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_HEIGHT, 60)
            // At the smallest supported size, show only the app icon; tapping it opens the app/note.
            val compact = minWidth <= 60 && minHeight <= 60
            val views = RemoteViews(
                context.packageName,
                if (compact) R.layout.widget_note_compact else R.layout.widget_note
            )
            val launchIntent = Intent(context, MainActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
                if (note != null) putExtra("open_note_id", note.id)
            }
            val pendingIntent = PendingIntent.getActivity(
                context, widgetId, launchIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            views.setOnClickPendingIntent(R.id.widget_root, pendingIntent)
            if (!compact) {
                views.setTextViewText(R.id.widget_title, note?.title?.ifBlank { "Bez naslova" } ?: "Sticky & Note")
                views.setTextViewText(R.id.widget_text, note?.text ?: "Nema spremljenih bilješki.")
            }
            manager.updateAppWidget(widgetId, views)
        }

        fun updateAll(context: Context) {
            val manager = AppWidgetManager.getInstance(context)
            val component = ComponentName(context, NoteWidgetProvider::class.java)
            manager.getAppWidgetIds(component).forEach { update(context, manager, it) }
        }
    }
}