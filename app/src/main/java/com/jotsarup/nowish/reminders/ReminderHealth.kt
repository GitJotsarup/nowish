package com.jotsarup.nowish.reminders

import android.app.AlarmManager
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.core.app.NotificationManagerCompat

/** Things on the phone that stop reminders from showing, each with the screen that fixes it. */
enum class ReminderProblem(val message: String) {
    NOTIFICATIONS_OFF("Notifications are off for Nowish, so reminders can't show."),
    CHANNEL_OFF("The Reminders notification category is turned off."),
    EXACT_ALARMS_OFF("\"Alarms & reminders\" is off for Nowish, so reminders may arrive late or not at all."),
}

object ReminderHealth {
    fun problem(context: Context): ReminderProblem? {
        val notifications = NotificationManagerCompat.from(context)
        if (!notifications.areNotificationsEnabled()) return ReminderProblem.NOTIFICATIONS_OFF
        val channel = context.getSystemService(NotificationManager::class.java).getNotificationChannel("reminders")
        if (channel != null && channel.importance == NotificationManager.IMPORTANCE_NONE) return ReminderProblem.CHANNEL_OFF
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
            !context.getSystemService(AlarmManager::class.java).canScheduleExactAlarms()
        ) return ReminderProblem.EXACT_ALARMS_OFF
        return null
    }

    fun fixIntent(context: Context, problem: ReminderProblem): Intent = when (problem) {
        ReminderProblem.NOTIFICATIONS_OFF ->
            Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
        ReminderProblem.CHANNEL_OFF ->
            Intent(Settings.ACTION_CHANNEL_NOTIFICATION_SETTINGS)
                .putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
                .putExtra(Settings.EXTRA_CHANNEL_ID, "reminders")
        ReminderProblem.EXACT_ALARMS_OFF ->
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM, Uri.parse("package:${context.packageName}"))
            } else {
                Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${context.packageName}"))
            }
    }.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
}
