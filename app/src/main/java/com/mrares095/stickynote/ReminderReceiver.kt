package com.mrares095.stickynote

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat

class ReminderReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val isNoteReminder = intent.hasExtra("note_id")
        val title = if (isNoteReminder) intent.getStringExtra("note_title") ?: "Bilješka" else intent.getStringExtra("task_title") ?: "Zadatak"
        val taskId = if (isNoteReminder) intent.getLongExtra("note_id", System.currentTimeMillis()) else intent.getLongExtra("task_id", System.currentTimeMillis())
        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            manager.createNotificationChannel(
                NotificationChannel("todo_reminders", "Podsjetnici", NotificationManager.IMPORTANCE_HIGH).apply {
                    description = "Podsjetnici za zadatke u Sticky & Note"
                }
            )
        }
        val openApp = PendingIntent.getActivity(
            context, taskId.hashCode(),
            Intent(context, MainActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
                if (isNoteReminder) putExtra("open_note_id", taskId)
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val notificationId = if (isNoteReminder) taskId.hashCode() xor 0x4E4F5445 else taskId.hashCode()
        val notification = NotificationCompat.Builder(context, "todo_reminders")
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle(if (isNoteReminder) "Podsjetnik za bilješku" else "Podsjetnik za zadatak")
            .setContentText(title)
            .setStyle(NotificationCompat.BigTextStyle().bigText(title))
            .setContentIntent(openApp)
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .build()
        manager.notify(notificationId, notification)
    }
}
