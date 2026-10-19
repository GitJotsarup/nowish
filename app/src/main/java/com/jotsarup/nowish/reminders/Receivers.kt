package com.jotsarup.nowish.reminders

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.jotsarup.nowish.nowish
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

private val receiverScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

private fun BroadcastReceiver.runAsync(block: suspend () -> Unit) {
    val pending = goAsync()
    receiverScope.launch {
        try {
            block()
        } finally {
            pending.finish()
        }
    }
}

/** Fires when a reminder is due and posts the notification. */
class ReminderReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val id = intent.getLongExtra(ReminderScheduler.EXTRA_TASK_ID, -1)
        if (id < 0) return
        runAsync {
            val repo = context.nowish.repository
            val task = repo.task(id)
            if (task != null && !task.done && !task.deleted) {
                Notifications.show(context, task, task.listId?.let { repo.list(it) })
            }
        }
    }
}

/** Handles the "Mark done" and "Snooze 1h" buttons on a reminder notification. */
class TaskActionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val id = intent.getLongExtra(ReminderScheduler.EXTRA_TASK_ID, -1)
        if (id < 0) return
        val app = context.nowish
        when (intent.action) {
            ACTION_DONE -> runAsync {
                if (app.repository.complete(id)) app.sounds.success()
                Notifications.cancel(context, id)
            }
            ACTION_SNOOZE -> {
                Notifications.cancel(context, id)
                ReminderScheduler.scheduleAt(context, id, System.currentTimeMillis() + 60 * 60 * 1000L)
            }
        }
    }

    companion object {
        const val ACTION_DONE = "com.jotsarup.nowish.DONE"
        const val ACTION_SNOOZE = "com.jotsarup.nowish.SNOOZE"
    }
}

/** Alarms are wiped on reboot and time changes, so put them back. */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        runAsync { context.nowish.repository.rescheduleAll() }
    }
}
