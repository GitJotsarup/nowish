package com.jotsarup.nowish.ui

import android.os.Build
import android.view.HapticFeedbackConstants
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Alarm
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.DeleteOutline
import androidx.compose.material.icons.rounded.Event
import androidx.compose.material.icons.automirrored.rounded.Undo
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.SwipeToDismissBox
import androidx.compose.material3.SwipeToDismissBoxValue
import androidx.compose.material3.Text
import androidx.compose.material3.rememberSwipeToDismissBoxState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.jotsarup.nowish.data.Task
import com.jotsarup.nowish.data.TaskList
import java.time.LocalDate

val DoneGreen = Color(0xFF2EB872)

private val RowShape = RoundedCornerShape(20.dp)

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun TaskRow(
    task: Task,
    today: LocalDate,
    showDate: Boolean,
    overdue: Boolean,
    list: TaskList?,
    editing: Boolean,
    onStartEdit: () -> Unit,
    onRename: (String) -> Unit,
    onFinishEdit: () -> Unit,
    onComplete: () -> Unit,
    onDelete: () -> Unit,
    onLongPress: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val view = LocalView.current
    val complete by rememberUpdatedState(onComplete)
    val delete by rememberUpdatedState(onDelete)
    var fired by remember { mutableStateOf(false) }

    val dismissState = rememberSwipeToDismissBoxState(
        confirmValueChange = { value ->
            when (value) {
                SwipeToDismissBoxValue.StartToEnd -> {
                    if (!fired) { fired = true; complete() }
                    true
                }
                SwipeToDismissBoxValue.EndToStart -> {
                    if (!fired) { fired = true; delete() }
                    true
                }
                SwipeToDismissBoxValue.Settled -> true
            }
        },
        positionalThreshold = { it * 0.35f },
    )

    // A little tick when you drag past the point of no return.
    LaunchedEffect(dismissState.targetValue) {
        if (dismissState.targetValue != SwipeToDismissBoxValue.Settled) {
            view.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
        }
    }

    SwipeToDismissBox(
        state = dismissState,
        modifier = modifier.padding(vertical = 4.dp),
        enableDismissFromStartToEnd = !editing,
        enableDismissFromEndToStart = !editing,
        backgroundContent = {
            SwipeBackground(
                direction = dismissState.dismissDirection,
                armed = dismissState.targetValue != SwipeToDismissBoxValue.Settled,
            )
        },
    ) {
        Surface(
            shape = RowShape,
            color = MaterialTheme.colorScheme.surfaceContainerLow,
            tonalElevation = 0.dp,
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .combinedClickable(
                        onClick = { if (!editing) onStartEdit() },
                        onLongClick = {
                            view.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
                            onLongPress()
                        },
                    )
                    .padding(horizontal = 14.dp, vertical = 14.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                CheckCircle(onClick = {
                    if (!fired) { fired = true; complete() }
                })
                Spacer(Modifier.width(14.dp))
                Column(Modifier.weight(1f)) {
                    if (editing) {
                        InlineEditor(task.title, onRename, onFinishEdit)
                    } else {
                        Text(
                            task.title,
                            style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.onSurface,
                        )
                    }
                    TaskMeta(task, today, showDate, overdue, list)
                }
            }
        }
    }
}

@Composable
private fun TaskMeta(task: Task, today: LocalDate, showDate: Boolean, overdue: Boolean, list: TaskList?) {
    val context = LocalContext.current
    val date = task.dueDate?.takeIf { showDate }
    val time = task.reminderTime
    if (date == null && time == null && list == null) return
    Row(
        modifier = Modifier.padding(top = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        val tint = if (overdue) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary
        if (date != null) MetaPill(Icons.Rounded.Event, friendlyDate(date, today), tint)
        if (time != null) MetaPill(Icons.Rounded.Alarm, formatTime(context, time), tint)
        if (list != null) MetaPill(null, "${list.emoji} ${list.name}", MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun MetaPill(icon: ImageVector?, text: String, tint: Color) {
    Row(
        modifier = Modifier
            .clip(CircleShape)
            .background(tint.copy(alpha = 0.10f))
            .padding(horizontal = 8.dp, vertical = 3.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (icon != null) {
            Icon(icon, null, Modifier.size(14.dp), tint = tint)
            Spacer(Modifier.width(4.dp))
        }
        Text(
            text,
            style = MaterialTheme.typography.labelMedium,
            color = tint,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun CheckCircle(onClick: () -> Unit) {
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val scale by animateFloatAsState(if (pressed) 0.85f else 1f, spring(dampingRatio = 0.4f), label = "check")
    Box(
        modifier = Modifier
            .size(26.dp)
            .scale(scale)
            .clip(CircleShape)
            .border(2.dp, MaterialTheme.colorScheme.outline, CircleShape)
            .background(if (pressed) DoneGreen.copy(alpha = 0.25f) else Color.Transparent)
            .clickable(interactionSource = interaction, indication = null, onClick = onClick),
    )
}

@Composable
private fun InlineEditor(initial: String, onRename: (String) -> Unit, onFinish: () -> Unit) {
    var value by remember { mutableStateOf(TextFieldValue(initial, TextRange(initial.length))) }
    val focus = remember { FocusRequester() }
    var hadFocus by remember { mutableStateOf(false) }
    val latest by rememberUpdatedState(value.text)

    LaunchedEffect(Unit) { focus.requestFocus() }
    // Save whatever was typed if the editor goes away (tapping another task, swiping, etc).
    DisposableEffect(Unit) { onDispose { onRename(latest) } }

    BasicTextField(
        value = value,
        onValueChange = {
            if (it.text.contains('\n')) {
                onFinish()
            } else {
                value = it
            }
        },
        textStyle = MaterialTheme.typography.bodyLarge.copy(color = MaterialTheme.colorScheme.onSurface),
        cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
        keyboardOptions = KeyboardOptions(
            capitalization = KeyboardCapitalization.Sentences,
            imeAction = ImeAction.Done,
        ),
        keyboardActions = KeyboardActions(onDone = { onFinish() }),
        modifier = Modifier
            .fillMaxWidth()
            .focusRequester(focus)
            .onFocusChanged {
                if (it.isFocused) hadFocus = true else if (hadFocus) onFinish()
            },
    )
}

@Composable
private fun SwipeBackground(direction: SwipeToDismissBoxValue, armed: Boolean) {
    val isDone = direction == SwipeToDismissBoxValue.StartToEnd
    val base = if (isDone) DoneGreen else MaterialTheme.colorScheme.error
    val color by animateColorAsState(
        when {
            direction == SwipeToDismissBoxValue.Settled -> Color.Transparent
            armed -> base
            else -> base.copy(alpha = 0.35f)
        },
        label = "swipeColor",
    )
    val iconScale by animateFloatAsState(if (armed) 1.25f else 0.85f, spring(dampingRatio = 0.45f), label = "swipeIcon")
    Box(
        modifier = Modifier
            .fillMaxSize()
            .clip(RowShape)
            .background(color)
            .padding(horizontal = 24.dp),
        contentAlignment = if (isDone) Alignment.CenterStart else Alignment.CenterEnd,
    ) {
        if (direction != SwipeToDismissBoxValue.Settled) {
            Icon(
                if (isDone) Icons.Rounded.Check else Icons.Rounded.DeleteOutline,
                contentDescription = if (isDone) "Done" else "Delete",
                tint = Color.White,
                modifier = Modifier.scale(iconScale),
            )
        }
    }
}

@Composable
fun CompletedRow(task: Task, onRestore: () -> Unit, onDelete: () -> Unit, modifier: Modifier = Modifier) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(vertical = 2.dp)
            .clip(RowShape)
            .padding(start = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier
                .size(26.dp)
                .clip(CircleShape)
                .background(DoneGreen),
            contentAlignment = Alignment.Center,
        ) {
            Icon(Icons.Rounded.Check, null, tint = Color.White, modifier = Modifier.size(18.dp))
        }
        Spacer(Modifier.width(14.dp))
        Text(
            task.title,
            modifier = Modifier.weight(1f),
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textDecoration = TextDecoration.LineThrough,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        IconButton(onClick = onRestore) {
            Icon(Icons.AutoMirrored.Rounded.Undo, "Restore", tint = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        IconButton(onClick = onDelete) {
            Icon(Icons.Rounded.DeleteOutline, "Delete", tint = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

fun android.view.View.confirmHaptic() {
    performHapticFeedback(
        if (Build.VERSION.SDK_INT >= 30) HapticFeedbackConstants.CONFIRM else HapticFeedbackConstants.VIRTUAL_KEY
    )
}
