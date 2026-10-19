package com.jotsarup.nowish.widget

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.datastore.preferences.core.Preferences
import androidx.glance.ColorFilter
import androidx.glance.GlanceId
import androidx.glance.GlanceModifier
import androidx.glance.GlanceTheme
import androidx.glance.Image
import androidx.glance.ImageProvider
import androidx.glance.LocalContext
import androidx.glance.LocalSize
import androidx.glance.action.ActionParameters
import androidx.glance.action.actionParametersOf
import androidx.glance.action.clickable
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetReceiver
import androidx.glance.appwidget.SizeMode
import androidx.glance.appwidget.action.ActionCallback
import androidx.glance.appwidget.action.actionRunCallback
import androidx.glance.appwidget.action.actionStartActivity
import androidx.glance.appwidget.appWidgetBackground
import androidx.glance.appwidget.cornerRadius
import androidx.glance.appwidget.lazy.LazyColumn
import androidx.glance.appwidget.lazy.items
import androidx.glance.appwidget.provideContent
import androidx.glance.background
import androidx.glance.color.ColorProvider
import androidx.glance.currentState
import androidx.glance.layout.Alignment
import androidx.glance.layout.Box
import androidx.glance.layout.Column
import androidx.glance.layout.Row
import androidx.glance.layout.Spacer
import androidx.glance.layout.fillMaxSize
import androidx.glance.layout.fillMaxWidth
import androidx.glance.layout.height
import androidx.glance.layout.padding
import androidx.glance.layout.size
import androidx.glance.layout.width
import androidx.glance.state.PreferencesGlanceStateDefinition
import androidx.glance.text.FontWeight
import androidx.glance.text.Text
import androidx.glance.text.TextStyle
import com.jotsarup.nowish.R
import com.jotsarup.nowish.data.Task
import com.jotsarup.nowish.data.TaskList
import com.jotsarup.nowish.nowish
import com.jotsarup.nowish.ui.MainActivity
import java.time.LocalDate

class NowishWidget : GlanceAppWidget() {
    // Exact = re-lays out for whatever size you drag the widget to.
    override val sizeMode: SizeMode = SizeMode.Exact
    override val stateDefinition = PreferencesGlanceStateDefinition

    override suspend fun provideGlance(context: Context, id: GlanceId) {
        val dao = context.nowish.database.dao()
        val initialTasks = dao.openTasks()
        val initialLists = dao.allLists()
        provideContent {
            val tasksFlow = remember { dao.openTasksFlow() }
            val listsFlow = remember { dao.allListsFlow() }
            val tasks by tasksFlow.collectAsState(initialTasks)
            val lists by listsFlow.collectAsState(initialLists)
            val config = currentState<Preferences>().toWidgetConfig()
            GlanceTheme {
                WidgetContent(config, tasks, lists)
            }
        }
    }
}

class NowishWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = NowishWidget()
}

val TaskIdKey = ActionParameters.Key<Long>("taskId")

/** Tapping the circle in the widget completes the task (with the chime). */
class CompleteTaskAction : ActionCallback {
    override suspend fun onAction(context: Context, glanceId: GlanceId, parameters: ActionParameters) {
        val id = parameters[TaskIdKey] ?: return
        val app = context.nowish
        if (app.repository.complete(id)) app.sounds.success()
    }
}

private fun openIntent(context: Context, config: WidgetConfig, add: Boolean): Intent =
    Intent(context, MainActivity::class.java)
        .setData(Uri.parse("nowish://widget/${if (add) "add" else "open"}"))
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        .putExtra(MainActivity.EXTRA_ADD, add)
        .apply { config.space?.let { putExtra(MainActivity.EXTRA_SPACE, it.name) } }

@Composable
private fun WidgetContent(config: WidgetConfig, tasks: List<Task>, lists: List<TaskList>) {
    val context = LocalContext.current
    val size = LocalSize.current
    val rows = buildWidgetRows(context, config, tasks, lists, LocalDate.now())
    val count = rows.count { it is WidgetTask }
    val fs = config.fontSize
    val showHeader = size.height >= 96.dp
    val showMeta = size.width >= 150.dp
    val open = actionStartActivity(openIntent(context, config, add = false))
    val background = if (config.translucent) {
        ColorProvider(day = Color(0xB3FFFFFF), night = Color(0x99101014))
    } else {
        GlanceTheme.colors.widgetBackground
    }

    Column(
        modifier = GlanceModifier
            .fillMaxSize()
            .appWidgetBackground()
            .background(background)
            .cornerRadius(24.dp)
            .padding(start = 14.dp, end = 10.dp, top = if (showHeader) 12.dp else 6.dp, bottom = 4.dp),
    ) {
        if (showHeader) {
            Row(modifier = GlanceModifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Column(modifier = GlanceModifier.defaultWeight().clickable(open)) {
                    Text(
                        config.title(lists),
                        style = TextStyle(
                            color = GlanceTheme.colors.onSurface,
                            fontSize = (fs + 3).sp,
                            fontWeight = FontWeight.Bold,
                        ),
                        maxLines = 1,
                    )
                    Text(
                        if (count == 0) "Nothing left" else "$count to do",
                        style = TextStyle(color = GlanceTheme.colors.onSurfaceVariant, fontSize = (fs - 3).sp),
                        maxLines = 1,
                    )
                }
                Box(
                    modifier = GlanceModifier
                        .size(36.dp)
                        .cornerRadius(18.dp)
                        .background(GlanceTheme.colors.primaryContainer)
                        .clickable(actionStartActivity(openIntent(context, config, add = true))),
                    contentAlignment = Alignment.Center,
                ) {
                    Image(
                        ImageProvider(R.drawable.widget_add),
                        contentDescription = "Add task",
                        colorFilter = ColorFilter.tint(GlanceTheme.colors.onPrimaryContainer),
                        modifier = GlanceModifier.size(20.dp),
                    )
                }
            }
            Spacer(GlanceModifier.height(6.dp))
        }

        if (count == 0) {
            Box(GlanceModifier.fillMaxSize().clickable(open), contentAlignment = Alignment.Center) {
                Text(
                    "All clear ✨",
                    style = TextStyle(color = GlanceTheme.colors.onSurfaceVariant, fontSize = fs.sp),
                )
            }
        } else {
            LazyColumn(GlanceModifier.fillMaxSize()) {
                items(rows, itemId = { it.key }) { row ->
                    when (row) {
                        is WidgetHeader -> SectionLabel(row, fs)
                        is WidgetTask -> TaskLine(row, fs, showMeta, open)
                    }
                }
            }
        }
    }
}

@Composable
private fun SectionLabel(row: WidgetHeader, fs: Int) {
    Text(
        row.title.uppercase(),
        style = TextStyle(
            color = if (row.overdue) GlanceTheme.colors.error else GlanceTheme.colors.primary,
            fontSize = (fs - 4).coerceAtLeast(9).sp,
            fontWeight = FontWeight.Bold,
        ),
        modifier = GlanceModifier.padding(top = 6.dp, bottom = 2.dp),
    )
}

@Composable
private fun TaskLine(row: WidgetTask, fs: Int, showMeta: Boolean, open: androidx.glance.action.Action) {
    Row(
        modifier = GlanceModifier.fillMaxWidth().padding(vertical = 5.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Image(
            ImageProvider(R.drawable.widget_circle),
            contentDescription = "Mark done",
            colorFilter = ColorFilter.tint(
                if (row.overdue) GlanceTheme.colors.error else GlanceTheme.colors.outline
            ),
            modifier = GlanceModifier
                .size((fs + 6).dp)
                .clickable(actionRunCallback<CompleteTaskAction>(actionParametersOf(TaskIdKey to row.task.id))),
        )
        Spacer(GlanceModifier.width(10.dp))
        Column(modifier = GlanceModifier.defaultWeight().clickable(open)) {
            Text(
                row.task.title,
                style = TextStyle(color = GlanceTheme.colors.onSurface, fontSize = fs.sp),
                maxLines = 2,
            )
            if (showMeta && row.meta != null) {
                Text(
                    row.meta,
                    style = TextStyle(
                        color = if (row.overdue) GlanceTheme.colors.error else GlanceTheme.colors.onSurfaceVariant,
                        fontSize = (fs - 3).coerceAtLeast(9).sp,
                    ),
                    maxLines = 1,
                )
            }
        }
    }
}
