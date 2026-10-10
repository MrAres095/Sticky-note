package com.mrares095.stickynote

import android.appwidget.AppWidgetManager
import android.content.Intent
import android.widget.RemoteViews
import android.widget.RemoteViewsService
import org.json.JSONArray

class NoteListWidgetService : RemoteViewsService() {
    override fun onGetViewFactory(intent: Intent): RemoteViewsFactory =
        NoteListRemoteViewsFactory(applicationContext, intent.getIntExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, 0))

    private class NoteListRemoteViewsFactory(
        private val context: android.content.Context,
        private val widgetId: Int
    ) : RemoteViewsFactory {
        private data class Item(val id: Long, val title: String, val text: String)
        private var items: List<Item> = emptyList()

        override fun onCreate() = Unit
        override fun onDataSetChanged() {
            val prefs = context.getSharedPreferences(PREFS, android.content.Context.MODE_PRIVATE)
            val tab = prefs.getString("list_widget_tab_$widgetId", "Sve") ?: "Sve"
            val loaded = mutableListOf<Item>()
            try {
                if (tab == "To-do lista") {
                    val rawTasks = prefs.getString(TASKS, null)
                    if (!rawTasks.isNullOrBlank()) {
                        val array = JSONArray(rawTasks)
                        for (i in 0 until array.length()) {
                            val o = array.getJSONObject(i)
                            val completed = o.optBoolean("completed", false)
                            val title = o.optString("title").ifBlank { "Stavka" }
                            loaded += Item(o.optLong("id"), (if (completed) "✓  " else "□  ") + title, "")
                        }
                    }
                } else {
                    val raw = prefs.getString(NOTES, null)
                    if (!raw.isNullOrBlank()) {
                        val array = JSONArray(raw)
                        for (i in 0 until array.length()) {
                            val o = array.getJSONObject(i)
                            val category = o.optString("category", "Sve")
                            val trashed = o.optBoolean("trashed", category == "🗑 Otpad")
                            if (!trashed && (tab == "Sve" || category == tab)) {
                                loaded += Item(o.optLong("id"), o.optString("title").ifBlank { "Bez naslova" }, o.optString("text"))
                            }
                        }
                    }
                }
            } catch (_: Exception) { }
            items = loaded
        }
        override fun getCount() = items.size
        override fun getViewAt(position: Int): RemoteViews? {
            if (position !in items.indices) return null
            val item = items[position]
            return RemoteViews(context.packageName, R.layout.widget_note_list_item).apply {
                setTextViewText(R.id.widget_item_title, item.title)
                setTextViewText(R.id.widget_item_text, item.text)
                setOnClickFillInIntent(R.id.widget_item_root, Intent().putExtra("open_note_id", item.id))
            }
        }
        override fun getLoadingView(): RemoteViews? = null
        override fun getViewTypeCount() = 1
        override fun getItemId(position: Int) = items.getOrNull(position)?.id ?: position.toLong()
        override fun hasStableIds() = true
        override fun onDestroy() { items = emptyList() }
    }
}