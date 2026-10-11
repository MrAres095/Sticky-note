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
        private data class Item(val id: Long, val title: String, val text: String, val isTask: Boolean = false, val isTodoList: Boolean = false)
        private var items: List<Item> = emptyList()

        override fun onCreate() = Unit
        override fun onDataSetChanged() {
            val prefs = context.getSharedPreferences(PREFS, android.content.Context.MODE_PRIVATE)
            val tab = prefs.getString("list_widget_tab_$widgetId", "Sve") ?: "Sve"
            val loaded = mutableListOf<Item>()
            try {
                if (tab == "To-do lista") {
                    val selectedListId = prefs.getString("list_widget_todo_id_$widgetId", "default") ?: "default"
                    val rawTasks = prefs.getString(TASKS, null)
                    if (!rawTasks.isNullOrBlank()) {
                        val array = JSONArray(rawTasks)
                        for (i in 0 until array.length()) {
                            val o = array.getJSONObject(i)
                            val listId = o.optString("listId").ifBlank { "default" }
                            if (listId != selectedListId) continue
                            val completed = o.optBoolean("completed", false)
                            val title = o.optString("title").ifBlank { "Stavka" }
                            loaded += Item(o.optLong("id"), (if (completed) "✓  " else "□  ") + title, "", isTask = true)
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
                    // Show To-do list cards alongside notes when the widget is set to a category.
                    val rawLists = prefs.getString("todo_lists", null)
                    if (!rawLists.isNullOrBlank()) {
                        val lists = JSONArray(rawLists)
                        for (i in 0 until lists.length()) {
                            val o = lists.optJSONObject(i) ?: continue
                            val category = o.optString("category", "Osobno")
                            if (tab == "Sve" || category == tab) {
                                val listId = o.optString("id")
                                val title = o.optString("title").ifBlank { "To-do lista" }
                                val rawTasks = prefs.getString(TASKS, null)
                                var remaining = 0
                                if (!rawTasks.isNullOrBlank()) {
                                    val taskArray = JSONArray(rawTasks)
                                    for (j in 0 until taskArray.length()) {
                                        val task = taskArray.optJSONObject(j) ?: continue
                                        if (task.optString("listId").ifBlank { "default" } == listId && !task.optBoolean("completed", false)) remaining++
                                    }
                                }
                                loaded += Item(listId.hashCode().toLong(), "☑  " + title, "$remaining preostalo", isTodoList = true)
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
                setOnClickFillInIntent(R.id.widget_item_root, Intent().apply { if (item.isTodoList) putExtra("open_todo_list_id", item.title) else if (!item.isTask) putExtra("open_note_id", item.id) })
            }
        }
        override fun getLoadingView(): RemoteViews? = null
        override fun getViewTypeCount() = 1
        override fun getItemId(position: Int) = items.getOrNull(position)?.id ?: position.toLong()
        override fun hasStableIds() = true
        override fun onDestroy() { items = emptyList() }
    }
}