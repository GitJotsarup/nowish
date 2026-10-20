package com.jotsarup.nowish.ui

import android.text.format.DateFormat
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowForward
import androidx.compose.material.icons.rounded.Alarm
import androidx.compose.material.icons.rounded.ArrowUpward
import androidx.compose.material.icons.rounded.CalendarMonth
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.DeleteOutline
import androidx.compose.material.icons.rounded.Inbox
import androidx.compose.material.icons.rounded.NightsStay
import androidx.compose.material.icons.rounded.WbSunny
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.BasicAlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.material3.TimePicker
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.jotsarup.nowish.data.Space
import com.jotsarup.nowish.data.Task
import com.jotsarup.nowish.data.TaskList
import java.time.LocalDate
import java.time.LocalTime

/** If a reminder is set without a date, pick the next time that hour comes around. */
private fun dateForTime(time: LocalTime, today: LocalDate): LocalDate =
    if (time.isAfter(LocalTime.now())) today else today.plusDays(1)

/** A reminder for a time that's already passed today would never fire, so move it to tomorrow. */
private fun bumpIfPast(date: LocalDate?, time: LocalTime?, today: LocalDate): LocalDate? =
    if (date != null && time != null && date <= today && !date.atTime(time).isAfter(java.time.LocalDateTime.now())) today.plusDays(1) else date

private val nextHour: LocalTime get() = LocalTime.now().plusHours(1).withMinute(0).withSecond(0).withNano(0)

// ---------- Quick add ----------

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun AddTaskSheet(
    today: LocalDate,
    defaultDate: LocalDate?,
    onAdd: (String, LocalDate?, LocalTime?, Boolean) -> Unit,
    onDismiss: () -> Unit,
) {
    val view = LocalView.current
    val context = LocalContext.current
    var text by remember { mutableStateOf("") }
    var date by remember { mutableStateOf(defaultDate) }
    var sticky by remember { mutableStateOf(true) }
    var time by remember { mutableStateOf<LocalTime?>(null) }
    var pickDate by remember { mutableStateOf(false) }
    var pickTime by remember { mutableStateOf(false) }
    val focus = remember { FocusRequester() }

    fun submit() {
        if (text.isBlank()) return
        onAdd(text, bumpIfPast(date, time, today), time, sticky)
        view.confirmHaptic()
        text = ""
        time = null
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
    ) {
        Column(Modifier.padding(horizontal = 20.dp).padding(bottom = 20.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                TextField(
                    value = text,
                    onValueChange = { text = it.replace("\n", "") },
                    placeholder = { Text("What needs doing?") },
                    textStyle = MaterialTheme.typography.titleMedium,
                    colors = TextFieldDefaults.colors(
                        focusedContainerColor = Color.Transparent,
                        unfocusedContainerColor = Color.Transparent,
                        focusedIndicatorColor = Color.Transparent,
                        unfocusedIndicatorColor = Color.Transparent,
                    ),
                    keyboardOptions = KeyboardOptions(
                        capitalization = KeyboardCapitalization.Sentences,
                        imeAction = ImeAction.Send,
                    ),
                    keyboardActions = KeyboardActions(onSend = { submit() }),
                    modifier = Modifier.weight(1f).focusRequester(focus),
                )
                FilledIconButton(onClick = { submit() }, enabled = text.isNotBlank()) {
                    Icon(Icons.Rounded.ArrowUpward, "Add")
                }
            }
            Spacer(Modifier.height(8.dp))
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                WhenChip("Today", Icons.Rounded.WbSunny, sticky && date == today) { date = today; sticky = true }
                WhenChip("Tomorrow", Icons.Rounded.NightsStay, sticky && date == today.plusDays(1)) { date = today.plusDays(1); sticky = true }
                val custom = date?.takeIf { !sticky }
                WhenChip(custom?.let { friendlyDate(it, today) } ?: "Pick date", Icons.Rounded.CalendarMonth, custom != null) {
                    pickDate = true
                }
                WhenChip("Later", Icons.Rounded.Inbox, date == null) { date = null; time = null }
                WhenChip(time?.let { formatTime(context, it) } ?: "Remind me", Icons.Rounded.Alarm, time != null) {
                    pickTime = true
                }
            }
        }
    }

    LaunchedEffect(Unit) { focus.requestFocus() }

    if (pickDate) {
        DatePickDialog(date ?: today, onDismiss = { pickDate = false }) { date = it; sticky = false; pickDate = false }
    }
    if (pickTime) {
        TimePickDialog(time ?: nextHour, onDismiss = { pickTime = false }) {
            time = it
            if (date == null) { date = dateForTime(it, today); sticky = true }
            pickTime = false
        }
    }
}

@Composable
private fun WhenChip(label: String, icon: ImageVector, selected: Boolean, onClick: () -> Unit) {
    FilterChip(
        selected = selected,
        onClick = onClick,
        label = { Text(label) },
        leadingIcon = { Icon(icon, null, Modifier.size(FilterChipDefaults.IconSize)) },
        shape = CircleShape,
    )
}

// ---------- Long-press options ----------

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun TaskOptionsSheet(
    task: Task,
    today: LocalDate,
    lists: List<TaskList>,
    onUpdate: (Task) -> Unit,
    onComplete: (Task) -> Unit,
    onDelete: (Task) -> Unit,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    var current by remember { mutableStateOf(task) }
    var pickDate by remember { mutableStateOf(false) }
    var pickTime by remember { mutableStateOf(false) }

    fun apply(t: Task) {
        current = t
        onUpdate(t)
    }

    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(Modifier.padding(horizontal = 24.dp).padding(bottom = 24.dp)) {
            Text(current.title, style = MaterialTheme.typography.titleLarge)

            SheetLabel("When")
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                val eff = current.effectiveDue(today)
                WhenChip("Today", Icons.Rounded.WbSunny, current.sticky && eff == today) {
                    apply(current.copy(dueDate = today, sticky = true))
                }
                WhenChip("Tomorrow", Icons.Rounded.NightsStay, current.sticky && eff == today.plusDays(1)) {
                    apply(current.copy(dueDate = today.plusDays(1), sticky = true))
                }
                val custom = current.dueDate?.takeIf { !current.sticky }
                WhenChip(custom?.let { friendlyDate(it, today) } ?: "Pick date", Icons.Rounded.CalendarMonth, custom != null) {
                    pickDate = true
                }
                WhenChip("Later", Icons.Rounded.Inbox, current.dueDate == null) {
                    apply(current.copy(dueDate = null, reminderTime = null))
                }
            }

            SheetLabel("Reminder")
            Row(verticalAlignment = Alignment.CenterVertically) {
                val time = current.reminderTime
                FilterChip(
                    selected = time != null,
                    onClick = { pickTime = true },
                    label = { Text(time?.let { formatTime(context, it) } ?: "Add a reminder") },
                    leadingIcon = { Icon(Icons.Rounded.Alarm, null, Modifier.size(FilterChipDefaults.IconSize)) },
                    trailingIcon = if (time != null) {
                        {
                            Icon(
                                Icons.Rounded.Close,
                                "Remove reminder",
                                Modifier
                                    .size(FilterChipDefaults.IconSize)
                                    .clickable { apply(current.copy(reminderTime = null)) },
                            )
                        }
                    } else null,
                    shape = CircleShape,
                )
            }

            SheetLabel("List")
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                FilterChip(
                    selected = current.listId == null,
                    onClick = { apply(current.copy(listId = null)) },
                    label = { Text("None") },
                    shape = CircleShape,
                )
                lists.forEach { list ->
                    FilterChip(
                        selected = current.listId == list.id,
                        onClick = { apply(current.copy(listId = list.id)) },
                        label = { Text("${list.emoji} ${list.name}") },
                        shape = CircleShape,
                    )
                }
            }

            Spacer(Modifier.height(24.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                Button(
                    onClick = { onComplete(current); onDismiss() },
                    colors = ButtonDefaults.buttonColors(containerColor = DoneGreen, contentColor = Color.White),
                    modifier = Modifier.weight(1f),
                ) {
                    Icon(Icons.Rounded.Check, null, Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("Done")
                }
                val other: Space = current.space.other
                OutlinedButton(
                    onClick = { onUpdate(current.copy(space = other, listId = null)); onDismiss() },
                    modifier = Modifier.weight(1f),
                ) {
                    Text("To ${other.label}")
                    Spacer(Modifier.width(6.dp))
                    Icon(Icons.AutoMirrored.Rounded.ArrowForward, null, Modifier.size(18.dp))
                }
            }
            TextButton(
                onClick = { onDelete(current); onDismiss() },
                colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error),
                modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
            ) {
                Icon(Icons.Rounded.DeleteOutline, null, Modifier.size(18.dp))
                Spacer(Modifier.width(6.dp))
                Text("Delete task")
            }
        }
    }

    if (pickDate) {
        DatePickDialog(current.dueDate ?: today, onDismiss = { pickDate = false }) {
            apply(current.copy(dueDate = it, sticky = false))
            pickDate = false
        }
    }
    if (pickTime) {
        TimePickDialog(current.reminderTime ?: nextHour, onDismiss = { pickTime = false }) {
            val date = current.effectiveDue(today) ?: dateForTime(it, today)
            val bumped = bumpIfPast(date, it, today)
            apply(current.copy(reminderTime = it, dueDate = bumped, sticky = if (current.dueDate == null || bumped != date) true else current.sticky))
            pickTime = false
        }
    }
}

@Composable
private fun SheetLabel(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(top = 20.dp, bottom = 6.dp),
    )
}

// ---------- Pickers ----------

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DatePickDialog(initial: LocalDate, onDismiss: () -> Unit, onPick: (LocalDate) -> Unit) {
    val state = rememberDatePickerState(initialSelectedDateMillis = initial.toPickerMillis())
    DatePickerDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            TextButton(onClick = { state.selectedDateMillis?.let { onPick(it.fromPickerMillis()) } ?: onDismiss() }) {
                Text("Set")
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    ) {
        DatePicker(state)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TimePickDialog(initial: LocalTime, onDismiss: () -> Unit, onPick: (LocalTime) -> Unit) {
    val context = LocalContext.current
    val state = rememberTimePickerState(initial.hour, initial.minute, DateFormat.is24HourFormat(context))
    BasicAlertDialog(onDismissRequest = onDismiss) {
        Surface(shape = MaterialTheme.shapes.large, tonalElevation = 6.dp) {
            Column(Modifier.padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Text(
                    "Remind me at",
                    style = MaterialTheme.typography.labelLarge,
                    modifier = Modifier.fillMaxWidth().padding(bottom = 20.dp),
                )
                TimePicker(state)
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    TextButton(onClick = onDismiss) { Text("Cancel") }
                    TextButton(onClick = { onPick(LocalTime.of(state.hour, state.minute)) }) { Text("Set") }
                }
            }
        }
    }
}

// ---------- Lists ----------

private val listEmojis = listOf("📝", "🎬", "📚", "🛒", "✈️", "🎁", "💡", "🎮", "🎵", "🍿", "🏋️", "🍳", "💼", "🏠", "❤️", "⭐")

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ListDialog(
    existing: TaskList?,
    onDismiss: () -> Unit,
    onSave: (String, String) -> Unit,
) {
    var name by remember { mutableStateOf(existing?.name ?: "") }
    var emoji by remember { mutableStateOf(existing?.emoji ?: listEmojis.first()) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (existing == null) "New list" else "Edit list") },
        text = {
            Column {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it.replace("\n", "") },
                    label = { Text("Name") },
                    placeholder = { Text("To watch") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(16.dp))
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    listEmojis.forEach { e ->
                        Box(
                            modifier = Modifier
                                .size(40.dp)
                                .clip(CircleShape)
                                .background(
                                    if (e == emoji) MaterialTheme.colorScheme.primaryContainer else Color.Transparent
                                )
                                .clickable { emoji = e },
                            contentAlignment = Alignment.Center,
                        ) {
                            Text(e, fontSize = 20.sp)
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { onSave(name, emoji) }, enabled = name.isNotBlank()) {
                Text(if (existing == null) "Create" else "Save")
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}
