package com.jotsarup.nowish.data

import android.content.Context
import com.jotsarup.nowish.reminders.Notifications
import com.jotsarup.nowish.reminders.ReminderScheduler

/**
 * All writes stamp [Task.updatedAt] (so sync knows which copy is newest) and call [onChanged],
 * which kicks off a sync and refreshes the home-screen widgets.
 */
class TaskRepository(
    private val context: Context,
    private val dao: TaskDao,
    private val onChanged: () -> Unit,
) {
    fun tasks(space: Space) = dao.tasks(space)
    fun lists(space: Space) = dao.lists(space)

    suspend fun task(id: Long) = dao.task(id)
    suspend fun list(id: Long) = dao.list(id)
    suspend fun openTasks() = dao.openTasks()
    suspend fun allLists() = dao.allLists()

    private fun now() = System.currentTimeMillis()

    suspend fun add(task: Task): Task {
        val saved = task.copy(id = dao.insert(task))
        ReminderScheduler.schedule(context, saved)
        onChanged()
        return saved
    }

    suspend fun save(task: Task) {
        val updated = task.copy(updatedAt = now())
        dao.update(updated)
        ReminderScheduler.schedule(context, updated)
        onChanged()
    }

    suspend fun setDone(task: Task, done: Boolean) {
        val updated = task.copy(
            done = done,
            completedAt = if (done) now() else null,
            updatedAt = now(),
        )
        dao.update(updated)
        if (done) {
            ReminderScheduler.cancel(context, task.id)
            Notifications.cancel(context, task.id)
        } else {
            ReminderScheduler.schedule(context, updated)
        }
        onChanged()
    }

    /** Used by the notification and widget "done" actions. Returns true if the task was still open. */
    suspend fun complete(id: Long): Boolean {
        val task = dao.task(id)?.takeIf { !it.done && !it.deleted } ?: return false
        setDone(task, true)
        return true
    }

    suspend fun delete(task: Task) {
        dao.update(task.copy(deleted = true, updatedAt = now()))
        ReminderScheduler.cancel(context, task.id)
        Notifications.cancel(context, task.id)
        onChanged()
    }

    /** Brings back a deleted task (undo). */
    suspend fun restore(task: Task) {
        val restored = task.copy(deleted = false, updatedAt = now())
        dao.insert(restored)
        ReminderScheduler.schedule(context, restored)
        onChanged()
    }

    suspend fun deleteAll(tasks: List<Task>) {
        dao.updateAll(tasks.map { it.copy(deleted = true, updatedAt = now()) })
        onChanged()
    }

    suspend fun addList(list: TaskList): Long {
        val id = dao.insertList(list)
        onChanged()
        return id
    }

    suspend fun saveList(list: TaskList) {
        dao.updateList(list.copy(updatedAt = now()))
        onChanged()
    }

    suspend fun deleteList(list: TaskList) {
        val tasks = dao.tasksInList(list.id)
        tasks.forEach {
            ReminderScheduler.cancel(context, it.id)
            Notifications.cancel(context, it.id)
        }
        dao.updateAll(tasks.map { it.copy(deleted = true, updatedAt = now()) })
        dao.updateList(list.copy(deleted = true, updatedAt = now()))
        onChanged()
    }

    suspend fun rescheduleAll() {
        dao.withReminders().forEach { ReminderScheduler.schedule(context, it) }
    }
}
