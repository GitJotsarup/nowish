package com.jotsarup.nowish.reminders

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import com.jotsarup.nowish.data.Task
import java.time.ZoneId

object ReminderScheduler {
    const val EXTRA_TASK_ID = "task_id"

    fun schedule(context: Context, task: Task) {
        cancel(context, task.id)
        if (task.done) return
        val at = task.reminderAt() ?: return
        val millis = at.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
        if (millis <= System.currentTimeMillis()) return
        scheduleAt(context, task.id, millis)
    }

    fun scheduleAt(context: Context, taskId: Long, millis: Long) {
        val alarms = context.getSystemService(AlarmManager::class.java)
        val pi = pendingIntent(context, taskId)
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S || alarms.canScheduleExactAlarms()) {
            alarms.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, millis, pi)
        } else {
            alarms.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, millis, pi)
        }
    }

    fun cancel(context: Context, taskId: Long) {
        context.getSystemService(AlarmManager::class.java).cancel(pendingIntent(context, taskId))
    }

    private fun pendingIntent(context: Context, taskId: Long): PendingIntent =
        PendingIntent.getBroadcast(
            context,
            taskId.toInt(),
            Intent(context, ReminderReceiver::class.java).putExtra(EXTRA_TASK_ID, taskId),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
}
