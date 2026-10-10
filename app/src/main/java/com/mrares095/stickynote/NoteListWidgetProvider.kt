package com.mrares095.stickynote

import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.app.PendingIntent
import android.widget.RemoteViews

class NoteListWidgetProvider : AppWidgetProvider() {
    override fun onUpdate(context: Context, manager: AppWidgetManager, ids: IntArray) {
        ids.forEach { update(context, manager, it) }
    }

    override fun onDeleted(context: Context, ids: IntArray) {
        val edit = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
        ids.forEach { edit.remove("list_widget_tab_$it") }
        edit.apply()
    }

    companion object {
        fun update(context: Context, manager: AppWidgetManager, widgetId: Int) {
            val views = RemoteViews(context.packageName, R.layout.widget_note_list)
            val intent = Intent(context, NoteListWidgetService::class.java).apply {
                putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, widgetId)
                data = android.net.Uri.parse(toUri(Intent.URI_INTENT_SCHEME))
            }
            views.setRemoteAdapter(R.id.widget_note_list_items, intent)
            views.setEmptyView(R.id.widget_note_list_items, R.id.widget_empty)
            val open = PendingIntent.getActivity(
                context, widgetId + 7000,
                Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            views.setPendingIntentTemplate(R.id.widget_note_list_items, open)
            views.setTextViewText(R.id.widget_list_title, "Sticky & Note")
            manager.updateAppWidget(widgetId, views)
            manager.notifyAppWidgetViewDataChanged(widgetId, R.id.widget_note_list_items)
        }

        fun updateAll(context: Context) {
            val manager = AppWidgetManager.getInstance(context)
            manager.getAppWidgetIds(ComponentName(context, NoteListWidgetProvider::class.java))
                .forEach { update(context, manager, it) }
        }
    }
}