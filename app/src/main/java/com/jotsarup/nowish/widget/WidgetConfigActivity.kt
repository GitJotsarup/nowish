package com.jotsarup.nowish.widget

import android.appwidget.AppWidgetManager
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.ExperimentalLayoutApi
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.border
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.glance.appwidget.GlanceAppWidgetManager
import androidx.glance.appwidget.state.getAppWidgetState
import androidx.glance.appwidget.state.updateAppWidgetState
import androidx.glance.state.PreferencesGlanceStateDefinition
import androidx.lifecycle.lifecycleScope
import com.jotsarup.nowish.data.Space
import com.jotsarup.nowish.data.Task
import com.jotsarup.nowish.data.TaskList
import com.jotsarup.nowish.nowish
import com.jotsarup.nowish.ui.theme.NowishTheme
import kotlinx.coroutines.launch
import java.time.LocalDate
import kotlin.math.roundToInt

/** Shown when a widget is placed (and from "Reconfigure" on Android 12+). */
class WidgetConfigActivity : ComponentActivity() {
    private var appWidgetId = AppWidgetManager.INVALID_APPWIDGET_ID

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        appWidgetId = intent?.extras?.getInt(AppWidgetManager.EXTRA_APPWIDGET_ID, AppWidgetManager.INVALID_APPWIDGET_ID)
            ?: AppWidgetManager.INVALID_APPWIDGET_ID
        if (appWidgetId == AppWidgetManager.INVALID_APPWIDGET_ID) {
            finish()
            return
        }
        setResult(RESULT_CANCELED, resultIntent())

        setContent {
            NowishTheme {
                var initial by remember { mutableStateOf<WidgetConfig?>(null) }
                var tasks by remember { mutableStateOf(emptyList<Task>()) }
                var lists by remember { mutableStateOf(emptyList<TaskList>()) }
                LaunchedEffect(Unit) {
                    val dao = nowish.database.dao()
                    tasks = dao.openTasks()
                    lists = dao.allLists()
                    initial = runCatching {
                        val glanceId = GlanceAppWidgetManager(this@WidgetConfigActivity).getGlanceIdBy(appWidgetId)
                        getAppWidgetState(this@WidgetConfigActivity, PreferencesGlanceStateDefinition, glanceId)
                            .toWidgetConfig()
                    }.getOrDefault(WidgetConfig())
                }
                Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.surface) {
                    initial?.let { start ->
                        ConfigScreen(start, tasks, lists, onSave = ::save)
                    }
                }
            }
        }
    }

    private fun resultIntent() = Intent().putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, appWidgetId)

    private fun save(config: WidgetConfig) {
        lifecycleScope.launch {
            val glanceId = GlanceAppWidgetManager(this@WidgetConfigActivity).getGlanceIdBy(appWidgetId)
            updateAppWidgetState(this@WidgetConfigActivity, glanceId) { it.write(config) }
            NowishWidget().update(this@WidgetConfigActivity, glanceId)
            setResult(RESULT_OK, resultIntent())
            finish()
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
private fun ConfigScreen(
    start: WidgetConfig,
    tasks: List<Task>,
    lists: List<TaskList>,
    onSave: (WidgetConfig) -> Unit,
) {
    var config by remember { mutableStateOf(start) }

    Column(
        Modifier
            .fillMaxSize()
            .statusBarsPadding()
            .navigationBarsPadding()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp, vertical = 16.dp)
    ) {
        Text("Customize widget", style = MaterialTheme.typography.headlineMedium)
        Spacer(Modifier.height(16.dp))
        WidgetPreview(config, tasks, lists)

        Label("Show")
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            listOf(WidgetView.TODAY, WidgetView.UPCOMING, WidgetView.LATER, WidgetView.ALL).forEach { v ->
                FilterChip(
                    selected = config.view == v,
                    onClick = { config = config.copy(view = v, listUid = null) },
                    label = { Text(v.label) },
                    shape = CircleShape,
                )
            }
            lists.forEach { list ->
                FilterChip(
                    selected = config.view == WidgetView.LIST && config.listUid == list.uid,
                    onClick = { config = config.copy(view = WidgetView.LIST, listUid = list.uid) },
                    label = { Text("${list.emoji} ${list.name}") },
                    shape = CircleShape,
                )
            }
        }

        if (config.view != WidgetView.LIST) {
            Label("Space")
            val options = listOf<Space?>(null, Space.WORK, Space.PERSONAL)
            SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                options.forEachIndexed { i, s ->
                    SegmentedButton(
                        selected = config.space == s,
                        onClick = { config = config.copy(space = s) },
                        shape = SegmentedButtonDefaults.itemShape(i, options.size),
                    ) { Text(s?.label ?: "Both") }
                }
            }
        }

        Label("Text size · ${config.fontSize}sp")
        Slider(
            value = config.fontSize.toFloat(),
            onValueChange = { config = config.copy(fontSize = it.roundToInt()) },
            valueRange = WidgetConfig.MIN_FONT.toFloat()..WidgetConfig.MAX_FONT.toFloat(),
            steps = WidgetConfig.MAX_FONT - WidgetConfig.MIN_FONT - 1,
        )

        Row(
            Modifier.fillMaxWidth().padding(top = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text("See-through background", style = MaterialTheme.typography.titleMedium)
                Text(
                    "Lets your wallpaper show through",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Switch(checked = config.translucent, onCheckedChange = { config = config.copy(translucent = it) })
        }

        Spacer(Modifier.height(24.dp))
        Button(onClick = { onSave(config) }, modifier = Modifier.fillMaxWidth().height(52.dp)) {
            Text("Save widget")
        }
        Text(
            "Tip: long-press the widget to resize it. Tap a circle to tick a task off.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 12.dp),
        )
    }
}

@Composable
private fun Label(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(top = 20.dp, bottom = 8.dp),
    )
}

/** A Compose look-alike of the widget so you can see the font size before saving. */
@Composable
private fun WidgetPreview(config: WidgetConfig, tasks: List<Task>, lists: List<TaskList>) {
    val context = LocalContext.current
    val rows = remember(config, tasks, lists) { buildWidgetRows(context, config, tasks, lists, LocalDate.now()) }
    val count = rows.count { it is WidgetTask }
    val fs = config.fontSize
    val scheme = MaterialTheme.colorScheme
    Box(
        Modifier
            .fillMaxWidth()
            .background(
                if (config.translucent) scheme.surfaceVariant.copy(alpha = 0.55f) else scheme.surfaceContainerHigh,
                RoundedCornerShape(24.dp),
            )
            .padding(start = 14.dp, end = 12.dp, top = 12.dp, bottom = 12.dp)
    ) {
        Column {
            Text(config.title(lists), fontSize = (fs + 3).sp, fontWeight = FontWeight.Bold, color = scheme.onSurface)
            Text(
                if (count == 0) "Nothing left" else "$count to do",
                fontSize = (fs - 3).sp,
                color = scheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(6.dp))
            if (count == 0) {
                Text("All clear ✨", fontSize = fs.sp, color = scheme.onSurfaceVariant)
            }
            rows.take(5).forEach { row ->
                when (row) {
                    is WidgetHeader -> Text(
                        row.title.uppercase(),
                        fontSize = (fs - 4).coerceAtLeast(9).sp,
                        fontWeight = FontWeight.Bold,
                        color = if (row.overdue) scheme.error else scheme.primary,
                        modifier = Modifier.padding(top = 6.dp, bottom = 2.dp),
                    )
                    is WidgetTask -> Row(
                        Modifier.padding(vertical = 5.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Box(
                            Modifier
                                .size((fs + 6).dp)
                                .border(2.dp, if (row.overdue) scheme.error else scheme.outline, CircleShape)
                        )
                        Spacer(Modifier.width(10.dp))
                        Column {
                            Text(
                                row.task.title,
                                fontSize = fs.sp,
                                color = scheme.onSurface,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis,
                            )
                            row.meta?.let {
                                Text(
                                    it,
                                    fontSize = (fs - 3).coerceAtLeast(9).sp,
                                    color = if (row.overdue) scheme.error else scheme.onSurfaceVariant,
                                    maxLines = 1,
                                )
                            }
                        }
                    }
                }
            }
            if (rows.size > 5) Text("…", color = scheme.onSurfaceVariant, fontSize = fs.sp)
        }
    }
}
