package com.jotsarup.nowish.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.jotsarup.nowish.NowishApp
import com.jotsarup.nowish.data.Space
import com.jotsarup.nowish.data.Task
import com.jotsarup.nowish.data.TaskList
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId

enum class SectionKind { OVERDUE, TODAY, TOMORROW, UPCOMING, LATER }

data class Section(val kind: SectionKind, val title: String, val tasks: List<Task>)

data class HomeState(
    val space: Space,
    val list: TaskList? = null,
    val lists: List<TaskList> = emptyList(),
    val sections: List<Section> = emptyList(),
    val completed: List<Task> = emptyList(),
    val clearedToday: Int = 0,
    val today: LocalDate = LocalDate.now(),
    val loaded: Boolean = false,
)

private data class SpaceData(val space: Space, val tasks: List<Task>, val lists: List<TaskList>)

@OptIn(ExperimentalCoroutinesApi::class)
class NowishViewModel(application: Application) : AndroidViewModel(application) {
    private val app = application as NowishApp
    private val repo = app.repository

    private val space = MutableStateFlow(app.prefs.space)
    private val listId = MutableStateFlow<Long?>(null)

    private val _soundOn = MutableStateFlow(app.prefs.soundOn)
    val soundOn: StateFlow<Boolean> = _soundOn.asStateFlow()

    val syncState = app.sync.state
    val syncRepo: String get() = app.prefs.syncRepo
    val syncToken: String get() = app.prefs.syncToken

    /** Set when the widget's + button opened the app; the UI shows the add sheet and clears it. */
    val addRequested = MutableStateFlow(false)

    // Re-evaluated periodically so "Today" flips over at midnight while the app is open.
    private val today = flow {
        while (true) {
            emit(LocalDate.now())
            delay(30_000)
        }
    }.distinctUntilChanged()

    private val spaceData = space.flatMapLatest { s ->
        combine(repo.tasks(s), repo.lists(s)) { tasks, lists -> SpaceData(s, tasks, lists) }
    }

    val state: StateFlow<HomeState> = combine(spaceData, listId, today) { data, listId, today ->
        buildState(data, listId, today)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), HomeState(space = space.value))

    private fun buildState(data: SpaceData, listId: Long?, today: LocalDate): HomeState {
        val list = listId?.let { id -> data.lists.find { it.id == id } }
        // Home shows unlisted tasks plus anything dated from lists; a list shows only its own tasks.
        val inView = data.tasks.filter { t ->
            if (list != null) t.listId == list.id else t.listId == null || t.dueDate != null
        }
        val tomorrow = today.plusDays(1)
        val grouped = inView.filter { !it.done }.groupBy { t ->
            val d = t.effectiveDue(today)
            when {
                d == null -> SectionKind.LATER
                d < today -> SectionKind.OVERDUE
                d == today -> SectionKind.TODAY
                d == tomorrow -> SectionKind.TOMORROW
                else -> SectionKind.UPCOMING
            }
        }
        val byTime = compareBy<Task>({ it.effectiveDue(today) }, { it.reminderTime ?: LocalTime.MAX }, { it.createdAt })
        val sections = SectionKind.entries.mapNotNull { kind ->
            val tasks = grouped[kind].orEmpty()
            if (tasks.isEmpty()) return@mapNotNull null
            Section(
                kind = kind,
                title = when (kind) {
                    SectionKind.OVERDUE -> "Overdue"
                    SectionKind.TODAY -> "Today"
                    SectionKind.TOMORROW -> "Tomorrow"
                    SectionKind.UPCOMING -> "Upcoming"
                    SectionKind.LATER -> if (list != null) "Anytime" else "Later"
                },
                tasks = if (kind == SectionKind.LATER) tasks.sortedBy { it.createdAt } else tasks.sortedWith(byTime),
            )
        }
        val startOfToday = today.atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli()
        return HomeState(
            space = data.space,
            list = list,
            lists = data.lists,
            sections = sections,
            completed = inView.filter { it.done }.sortedByDescending { it.completedAt },
            clearedToday = data.tasks.count { it.done && (it.completedAt ?: 0) >= startOfToday },
            today = today,
            loaded = true,
        )
    }

    fun openSpace(s: Space) {
        space.value = s
        listId.value = null
        app.prefs.space = s
    }

    fun openList(id: Long?) {
        listId.value = id
    }

    fun configureSync(repo: String, token: String) = app.sync.configure(repo, token)

    fun syncNow() = viewModelScope.launch { app.sync.sync() }

    /** Ask MainActivity's shell to show the Reminders tab (notification / widget taps). */
    val showRemindersRequested = MutableStateFlow(false)

    fun setSound(on: Boolean) {
        app.prefs.soundOn = on
        _soundOn.value = on
    }

    fun add(title: String, date: LocalDate?, time: LocalTime?, sticky: Boolean) {
        val text = title.trim()
        if (text.isEmpty()) return
        viewModelScope.launch {
            repo.add(
                Task(
                    title = text,
                    space = space.value,
                    listId = listId.value,
                    dueDate = date,
                    reminderTime = if (date != null) time else null,
                    sticky = sticky,
                )
            )
        }
    }

    /** Field-level: re-reads the row so a stale copy can't undo a completion or another edit. */
    fun rename(task: Task, title: String) {
        val text = title.trim()
        if (text.isEmpty() || text == task.title) return
        viewModelScope.launch { repo.update(task.id) { it.copy(title = text) } }
    }

    /** Applies only what the options sheet edits (date, time, list, space) on top of the latest row. */
    fun saveOptions(task: Task) = viewModelScope.launch {
        repo.update(task.id) {
            it.copy(
                dueDate = task.dueDate,
                reminderTime = task.reminderTime,
                sticky = task.sticky,
                listId = task.listId,
                space = task.space,
            )
        }
    }

    fun moveOverdueToToday(tasks: List<Task>) = viewModelScope.launch {
        val today = LocalDate.now()
        tasks.forEach { t -> repo.update(t.id) { it.copy(dueDate = today, sticky = true) } }
    }

    fun complete(task: Task) {
        app.sounds.success()
        viewModelScope.launch { repo.setDone(task, true) }
    }

    fun uncomplete(task: Task) = viewModelScope.launch { repo.setDone(task, false) }

    fun delete(task: Task) = viewModelScope.launch { repo.delete(task) }

    fun restore(task: Task) = viewModelScope.launch { repo.restore(task) }

    fun clearCompleted() {
        val done = state.value.completed
        viewModelScope.launch { repo.deleteAll(done) }
    }

    fun createList(name: String, emoji: String) {
        viewModelScope.launch {
            listId.value = repo.addList(TaskList(name = name.trim(), emoji = emoji, space = space.value))
        }
    }

    fun editList(list: TaskList, name: String, emoji: String) =
        viewModelScope.launch { repo.saveList(list.copy(name = name.trim(), emoji = emoji)) }

    fun deleteList(list: TaskList) {
        listId.value = null
        viewModelScope.launch { repo.deleteList(list) }
    }
}
