package com.jotsarup.nowish.reminders

import android.Manifest
import android.annotation.SuppressLint
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.jotsarup.nowish.R
import com.jotsarup.nowish.data.Task
import com.jotsarup.nowish.data.TaskList
import com.jotsarup.nowish.ui.MainActivity
import com.jotsarup.nowish.ui.formatTime

object Notifications {
    private const val CHANNEL = "reminders"

    fun createChannel(context: Context) {
        val channel = NotificationChannel(CHANNEL, "Reminders", NotificationManager.IMPORTANCE_HIGH).apply {
            description = "Task reminders"
            enableVibration(true)
        }
        context.getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    @SuppressLint("MissingPermission")
    fun show(context: Context, task: Task, list: TaskList?) {
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) return

        val id = task.id.toInt()
        val open = PendingIntent.getActivity(
            context,
            id,
            Intent(context, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
                .putExtra(MainActivity.EXTRA_SPACE, task.space.name),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val subtitle = buildString {
            append(task.space.label)
            list?.let { append(" · ${it.emoji} ${it.name}") }
            task.reminderTime?.let { append(" · ${formatTime(context, it)}") }
        }

        val notification = NotificationCompat.Builder(context, CHANNEL)
            .setSmallIcon(R.drawable.ic_stat_nowish)
            .setColor(ContextCompat.getColor(context, R.color.brand))
            .setContentTitle(task.title)
            .setContentText(subtitle)
            .setCategory(NotificationCompat.CATEGORY_REMINDER)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .setContentIntent(open)
            .addAction(R.drawable.ic_action_done, "Mark done", action(context, TaskActionReceiver.ACTION_DONE, task.id))
            .addAction(R.drawable.ic_action_snooze, "Snooze 1h", action(context, TaskActionReceiver.ACTION_SNOOZE, task.id))
            .build()

        NotificationManagerCompat.from(context).notify(id, notification)
    }

    fun cancel(context: Context, taskId: Long) {
        NotificationManagerCompat.from(context).cancel(taskId.toInt())
    }

    private fun action(context: Context, action: String, taskId: Long): PendingIntent =
        PendingIntent.getBroadcast(
            context,
            taskId.toInt(),
            Intent(context, TaskActionReceiver::class.java)
                .setAction(action)
                .putExtra(ReminderScheduler.EXTRA_TASK_ID, taskId),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
}
