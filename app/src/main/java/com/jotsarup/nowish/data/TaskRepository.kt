package com.jotsarup.nowish.data

import android.content.Context
import com.jotsarup.nowish.reminders.Notifications
import com.jotsarup.nowish.reminders.ReminderScheduler

class TaskRepository(private val context: Context, private val dao: TaskDao) {
    fun tasks(space: Space) = dao.tasks(space)
    fun lists(space: Space) = dao.lists(space)

    suspend fun task(id: Long) = dao.task(id)
    suspend fun list(id: Long) = dao.list(id)

    suspend fun add(task: Task): Task {
        val saved = task.copy(id = dao.insert(task))
        ReminderScheduler.schedule(context, saved)
        return saved
    }

    suspend fun save(task: Task) {
        dao.update(task)
        ReminderScheduler.schedule(context, task)
    }

    suspend fun setDone(task: Task, done: Boolean) {
        val updated = task.copy(done = done, completedAt = if (done) System.currentTimeMillis() else null)
        dao.update(updated)
        if (done) {
            ReminderScheduler.cancel(context, task.id)
            Notifications.cancel(context, task.id)
        } else {
            ReminderScheduler.schedule(context, updated)
        }
    }

    /** Used by the notification "Mark done" action. Returns true if the task was still open. */
    suspend fun complete(id: Long): Boolean {
        val task = dao.task(id)?.takeIf { !it.done } ?: return false
        setDone(task, true)
        return true
    }

    suspend fun delete(task: Task) {
        dao.delete(task)
        ReminderScheduler.cancel(context, task.id)
        Notifications.cancel(context, task.id)
    }

    /** Re-inserts a deleted task with its original id (undo). */
    suspend fun restore(task: Task) {
        dao.insert(task)
        ReminderScheduler.schedule(context, task)
    }

    suspend fun deleteAll(tasks: List<Task>) = dao.deleteAll(tasks)

    suspend fun addList(list: TaskList): Long = dao.insertList(list)

    suspend fun saveList(list: TaskList) = dao.updateList(list)

    suspend fun deleteList(list: TaskList) {
        val tasks = dao.tasksInList(list.id)
        tasks.forEach {
            ReminderScheduler.cancel(context, it.id)
            Notifications.cancel(context, it.id)
        }
        dao.deleteAll(tasks)
        dao.deleteList(list)
    }

    suspend fun rescheduleAll() {
        dao.withReminders().forEach { ReminderScheduler.schedule(context, it) }
    }
}
