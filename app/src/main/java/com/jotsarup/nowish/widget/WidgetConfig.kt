package com.jotsarup.nowish.widget

import android.content.Context
import androidx.datastore.preferences.core.MutablePreferences
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import com.jotsarup.nowish.data.Space
import com.jotsarup.nowish.data.Task
import com.jotsarup.nowish.data.TaskList
import com.jotsarup.nowish.ui.formatTime
import com.jotsarup.nowish.ui.friendlyDate
import java.time.LocalDate
import java.time.LocalTime

enum class WidgetView(val label: String) {
    TODAY("Today"),
    UPCOMING("Upcoming"),
    LATER("Later"),
    ALL("Everything"),
    LIST("List"),
}

data class WidgetConfig(
    val view: WidgetView = WidgetView.TODAY,
    /** null = both Work and Personal. Ignored for list widgets. */
    val space: Space? = null,
    val listUid: String? = null,
    val fontSize: Int = 15,
    val translucent: Boolean = false,
) {
    fun title(lists: List<TaskList>): String {
        if (view == WidgetView.LIST) {
            val list = lists.find { it.uid == listUid }
            return list?.let { "${it.emoji} ${it.name}" } ?: "List"
        }
        return space?.let { "${view.label} · ${it.label}" } ?: view.label
    }

    companion object {
        const val MIN_FONT = 11
        const val MAX_FONT = 24
    }
}

private val KEY_VIEW = stringPreferencesKey("view")
private val KEY_SPACE = stringPreferencesKey("space")
private val KEY_LIST = stringPreferencesKey("list")
private val KEY_FONT = intPreferencesKey("font")
private val KEY_TRANSLUCENT = booleanPreferencesKey("translucent")

fun Preferences.toWidgetConfig() = WidgetConfig(
    view = this[KEY_VIEW]?.let { runCatching { WidgetView.valueOf(it) }.getOrNull() } ?: WidgetView.TODAY,
    space = this[KEY_SPACE]?.let { runCatching { Space.valueOf(it) }.getOrNull() },
    listUid = this[KEY_LIST],
    fontSize = this[KEY_FONT] ?: 15,
    translucent = this[KEY_TRANSLUCENT] ?: false,
)

fun MutablePreferences.write(config: WidgetConfig) {
    this[KEY_VIEW] = config.view.name
    if (config.space != null) this[KEY_SPACE] = config.space.name else remove(KEY_SPACE)
    if (config.listUid != null) this[KEY_LIST] = config.listUid else remove(KEY_LIST)
    this[KEY_FONT] = config.fontSize
    this[KEY_TRANSLUCENT] = config.translucent
}

sealed interface WidgetRow {
    val key: Long
}

data class WidgetHeader(val title: String, val overdue: Boolean, override val key: Long) : WidgetRow

data class WidgetTask(val task: Task, val meta: String?, val overdue: Boolean) : WidgetRow {
    override val key: Long get() = task.id
}

/** Turns open tasks into the rows a widget shows, with small section headers when it helps. */
fun buildWidgetRows(
    context: Context,
    config: WidgetConfig,
    tasks: List<Task>,
    lists: List<TaskList>,
    today: LocalDate,
): List<WidgetRow> {
    val listsById = lists.associateBy { it.id }
    val tomorrow = today.plusDays(1)
    val pool = when (config.view) {
        WidgetView.LIST -> tasks.filter { t -> t.listId?.let(listsById::get)?.uid == config.listUid }
        else -> tasks.filter { config.space == null || it.space == config.space }
    }.filter { t ->
        val d = t.dueDate
        when (config.view) {
            WidgetView.TODAY -> d != null && d <= today
            WidgetView.UPCOMING -> d != null && d > today
            WidgetView.LATER -> d == null && t.listId == null
            WidgetView.ALL, WidgetView.LIST -> true
        }
    }

    fun section(t: Task): Int {
        val d = t.dueDate
        return when {
            d == null -> 4
            d < today -> 0
            d == today -> 1
            d == tomorrow -> 2
            else -> 3
        }
    }
    val names = listOf("Overdue", "Today", "Tomorrow", "Upcoming", if (config.view == WidgetView.LIST) "Anytime" else "Later")
    val grouped = pool
        .sortedWith(compareBy<Task>({ it.dueDate ?: LocalDate.MAX }, { it.reminderTime ?: LocalTime.MAX }, { it.createdAt }))
        .groupBy(::section)
        .toSortedMap()
    val showHeaders = grouped.size > 1

    val rows = ArrayList<WidgetRow>()
    grouped.forEach { (index, items) ->
        if (showHeaders) rows += WidgetHeader(names[index], index == 0, key = -1L - index)
        items.forEach { t ->
            val parts = ArrayList<String>()
            val d = t.dueDate
            if (d != null && (index == 0 || index == 3)) parts += friendlyDate(d, today)
            t.reminderTime?.let { parts += formatTime(context, it) }
            if (config.view != WidgetView.LIST) {
                t.listId?.let(listsById::get)?.let { parts += "${it.emoji} ${it.name}" }
            }
            if (config.space == null && config.view != WidgetView.LIST) parts += t.space.label
            rows += WidgetTask(t, parts.joinToString(" · ").ifEmpty { null }, overdue = index == 0)
        }
    }
    return rows
}
