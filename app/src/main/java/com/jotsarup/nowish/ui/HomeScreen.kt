package com.jotsarup.nowish.ui

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.CloudDone
import androidx.compose.material.icons.rounded.CloudOff
import androidx.compose.material.icons.rounded.SyncProblem
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import com.jotsarup.nowish.sync.SyncState
import androidx.compose.material.icons.rounded.BusinessCenter
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.ExpandMore
import androidx.compose.material.icons.rounded.Home
import androidx.compose.material.icons.rounded.Menu
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material.icons.rounded.DeleteOutline
import androidx.compose.material.icons.rounded.MusicNote
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.NavigationDrawerItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberDrawerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.jotsarup.nowish.data.Space
import com.jotsarup.nowish.data.Task
import com.jotsarup.nowish.data.TaskList
import kotlinx.coroutines.launch
import java.time.LocalTime

private sealed interface ListDialogState {
    data object New : ListDialogState
    data class Edit(val list: TaskList) : ListDialogState
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NowishRoot(vm: NowishViewModel) {
    val state by vm.state.collectAsStateWithLifecycle()
    val soundOn by vm.soundOn.collectAsStateWithLifecycle()
    val drawerState = rememberDrawerState(DrawerValue.Closed)
    val scope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }
    val view = LocalView.current

    var confetti by remember { mutableIntStateOf(0) }
    var showAdd by remember { mutableStateOf(false) }
    var optionsFor by remember { mutableStateOf<Task?>(null) }
    var editingId by remember { mutableStateOf<Long?>(null) }
    var listDialog by remember { mutableStateOf<ListDialogState?>(null) }
    var confirmDeleteList by remember { mutableStateOf<TaskList?>(null) }
    var showCompleted by rememberSaveable { mutableStateOf(false) }
    val syncState by vm.syncState.collectAsStateWithLifecycle()
    val addRequested by vm.addRequested.collectAsStateWithLifecycle()
    var showSync by remember { mutableStateOf(false) }
    var refreshing by remember { mutableStateOf(false) }

    LaunchedEffect(addRequested) {
        if (addRequested) {
            showAdd = true
            vm.addRequested.value = false
        }
    }
    LaunchedEffect(syncState.syncing) { if (!syncState.syncing) refreshing = false }

    RequestNotificationPermission()

    fun showUndo(message: String, onUndo: () -> Unit) {
        scope.launch {
            snackbar.currentSnackbarData?.dismiss()
            val result = snackbar.showSnackbar(message, actionLabel = "Undo", duration = SnackbarDuration.Short)
            if (result == SnackbarResult.ActionPerformed) onUndo()
        }
    }

    fun complete(task: Task) {
        editingId = null
        vm.complete(task)
        view.confirmHaptic()
        confetti++
        showUndo(randomCheer()) { vm.uncomplete(task) }
    }

    fun delete(task: Task) {
        editingId = null
        vm.delete(task)
        view.confirmHaptic()
        showUndo("Task deleted") { vm.restore(task) }
    }

    BackHandler(enabled = drawerState.isOpen) { scope.launch { drawerState.close() } }
    BackHandler(enabled = editingId != null) { editingId = null }
    BackHandler(enabled = state.list != null && editingId == null && drawerState.isClosed) { vm.openList(null) }

    ModalNavigationDrawer(
        drawerState = drawerState,
        drawerContent = {
            DrawerContent(
                state = state,
                soundOn = soundOn,
                onSound = vm::setSound,
                onOpenList = { id ->
                    vm.openList(id)
                    scope.launch { drawerState.close() }
                },
                onNewList = { listDialog = ListDialogState.New },
                syncState = syncState,
                onOpenSync = {
                    showSync = true
                    scope.launch { drawerState.close() }
                },
            )
        },
    ) {
        Box(Modifier.fillMaxSize()) {
            Scaffold(
                snackbarHost = { SnackbarHost(snackbar) },
                floatingActionButton = {
                    FloatingActionButton(
                        onClick = { editingId = null; showAdd = true },
                        containerColor = MaterialTheme.colorScheme.primaryContainer,
                        contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                        shape = MaterialTheme.shapes.large,
                    ) {
                        Icon(Icons.Rounded.Add, "Add task", Modifier.size(28.dp))
                    }
                },
                topBar = {
                    Header(
                        state = state,
                        onMenu = {
                            if (state.list != null) vm.openList(null)
                            else scope.launch { drawerState.open() }
                        },
                        onSpace = { editingId = null; vm.openSpace(it) },
                        onEditList = { state.list?.let { listDialog = ListDialogState.Edit(it) } },
                        onDeleteList = { confirmDeleteList = state.list },
                    )
                },
            ) { padding ->
                PullToRefreshBox(
                    isRefreshing = refreshing,
                    onRefresh = {
                        if (syncState.configured) {
                            refreshing = true
                            vm.syncNow()
                        } else {
                            showSync = true
                        }
                    },
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(top = padding.calculateTopPadding()),
                ) {
                    TaskListContent(
                        state = state,
                        padding = PaddingValues(bottom = padding.calculateBottomPadding()),
                        editingId = editingId,
                        showCompleted = showCompleted,
                        onToggleCompleted = { showCompleted = !showCompleted },
                        onStartEdit = { editingId = it.id },
                        onRename = vm::rename,
                        onFinishEdit = { task -> if (editingId == task.id) editingId = null },
                        onComplete = ::complete,
                        onDelete = ::delete,
                        onLongPress = { editingId = null; optionsFor = it },
                        onRestore = { vm.uncomplete(it) },
                        onDeleteCompleted = { vm.delete(it) },
                        onClearCompleted = vm::clearCompleted,
                    )
                }
            }
            ConfettiOverlay(confetti)
        }
    }

    if (showAdd) {
        AddTaskSheet(
            today = state.today,
            defaultDate = if (state.list == null) state.today else null,
            onAdd = vm::add,
            onDismiss = { showAdd = false },
        )
    }

    optionsFor?.let { task ->
        TaskOptionsSheet(
            task = task,
            today = state.today,
            lists = state.lists,
            onUpdate = { vm.save(it) },
            onComplete = ::complete,
            onDelete = ::delete,
            onDismiss = { optionsFor = null },
        )
    }

    listDialog?.let { dialog ->
        ListDialog(
            existing = (dialog as? ListDialogState.Edit)?.list,
            onDismiss = { listDialog = null },
            onSave = { name, emoji ->
                when (dialog) {
                    ListDialogState.New -> vm.createList(name, emoji)
                    is ListDialogState.Edit -> vm.editList(dialog.list, name, emoji)
                }
                listDialog = null
                scope.launch { drawerState.close() }
            },
        )
    }

    if (showSync) {
        SyncDialog(
            initialRepo = vm.syncRepo,
            initialToken = vm.syncToken,
            state = syncState,
            onSave = { repo, token -> vm.configureSync(repo, token) },
            onSyncNow = vm::syncNow,
            onDismiss = { showSync = false },
        )
    }

    confirmDeleteList?.let { list ->
        AlertDialog(
            onDismissRequest = { confirmDeleteList = null },
            title = { Text("Delete ${list.emoji} ${list.name}?") },
            text = { Text("All tasks in this list will be deleted too.") },
            confirmButton = {
                TextButton(onClick = { vm.deleteList(list); confirmDeleteList = null }) {
                    Text("Delete", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = { TextButton(onClick = { confirmDeleteList = null }) { Text("Cancel") } },
        )
    }
}

@Composable
private fun Header(
    state: HomeState,
    onMenu: () -> Unit,
    onSpace: (Space) -> Unit,
    onEditList: () -> Unit,
    onDeleteList: () -> Unit,
) {
    var menu by remember { mutableStateOf(false) }
    Column(
        Modifier
            .background(MaterialTheme.colorScheme.surface)
            .statusBarsPadding()
            .padding(horizontal = 8.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
            IconButton(onClick = onMenu) {
                if (state.list != null) Icon(Icons.AutoMirrored.Rounded.ArrowBack, "Back")
                else Icon(Icons.Rounded.Menu, "Lists")
            }
            Spacer(Modifier.weight(1f))
            if (state.list != null) {
                Box {
                    IconButton(onClick = { menu = true }) { Icon(Icons.Rounded.MoreVert, "List options") }
                    DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                        DropdownMenuItem(
                            text = { Text("Edit list") },
                            leadingIcon = { Icon(Icons.Rounded.Edit, null) },
                            onClick = { menu = false; onEditList() },
                        )
                        DropdownMenuItem(
                            text = { Text("Delete list") },
                            leadingIcon = { Icon(Icons.Rounded.DeleteOutline, null) },
                            onClick = { menu = false; onDeleteList() },
                        )
                    }
                }
            }
        }
        Column(Modifier.padding(horizontal = 16.dp)) {
            val subtitle = buildString {
                append(greeting(LocalTime.now().hour))
                append(" · ")
                append(headerDate(state.today))
            }
            Text(subtitle, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(
                state.list?.let { "${it.emoji} ${it.name}" } ?: state.space.label,
                style = MaterialTheme.typography.displaySmall,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (state.clearedToday > 0) {
                Text(
                    "✨ ${state.clearedToday} cleared today",
                    style = MaterialTheme.typography.labelLarge,
                    color = DoneGreen,
                    modifier = Modifier.padding(top = 2.dp),
                )
            }
        }
        SingleChoiceSegmentedButtonRow(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 12.dp)
        ) {
            Space.entries.forEachIndexed { index, space ->
                SegmentedButton(
                    selected = state.space == space,
                    onClick = { onSpace(space) },
                    shape = SegmentedButtonDefaults.itemShape(index, Space.entries.size),
                    icon = {
                        Icon(
                            if (space == Space.WORK) Icons.Rounded.BusinessCenter else Icons.Rounded.Home,
                            null,
                            Modifier.size(18.dp),
                        )
                    },
                ) {
                    Text(space.label)
                }
            }
        }
    }
}

@Composable
private fun TaskListContent(
    state: HomeState,
    padding: PaddingValues,
    editingId: Long?,
    showCompleted: Boolean,
    onToggleCompleted: () -> Unit,
    onStartEdit: (Task) -> Unit,
    onRename: (Task, String) -> Unit,
    onFinishEdit: (Task) -> Unit,
    onComplete: (Task) -> Unit,
    onDelete: (Task) -> Unit,
    onLongPress: (Task) -> Unit,
    onRestore: (Task) -> Unit,
    onDeleteCompleted: (Task) -> Unit,
    onClearCompleted: () -> Unit,
) {
    val listsById = remember(state.lists) { state.lists.associateBy { it.id } }
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(
            start = 16.dp,
            end = 16.dp,
            top = padding.calculateTopPadding(),
            bottom = padding.calculateBottomPadding() + 104.dp,
        ),
    ) {
        if (state.loaded && state.sections.isEmpty()) {
            item(key = "empty") { EmptyState(inList = state.list != null, Modifier.animateItem()) }
        }
        val onlyAnytime = state.list != null && state.sections.size == 1 && state.sections[0].kind == SectionKind.LATER
        state.sections.forEach { section ->
            if (!onlyAnytime) {
                item(key = "h-${section.kind}") {
                    SectionHeader(section, Modifier.animateItem())
                }
            }
            items(section.tasks, key = { "t-${it.id}" }) { task ->
                TaskRow(
                    task = task,
                    today = state.today,
                    showDate = section.kind == SectionKind.OVERDUE || section.kind == SectionKind.UPCOMING,
                    overdue = section.kind == SectionKind.OVERDUE,
                    list = if (state.list == null) task.listId?.let(listsById::get) else null,
                    editing = editingId == task.id,
                    onStartEdit = { onStartEdit(task) },
                    onRename = { onRename(task, it) },
                    onFinishEdit = { onFinishEdit(task) },
                    onComplete = { onComplete(task) },
                    onDelete = { onDelete(task) },
                    onLongPress = { onLongPress(task) },
                    modifier = Modifier.animateItem(),
                )
            }
        }
        if (state.completed.isNotEmpty()) {
            item(key = "done-header") {
                CompletedHeader(
                    count = state.completed.size,
                    expanded = showCompleted,
                    onToggle = onToggleCompleted,
                    onClear = onClearCompleted,
                    modifier = Modifier.animateItem(),
                )
            }
            if (showCompleted) {
                items(state.completed, key = { "d-${it.id}" }) { task ->
                    CompletedRow(
                        task = task,
                        onRestore = { onRestore(task) },
                        onDelete = { onDeleteCompleted(task) },
                        modifier = Modifier.animateItem(),
                    )
                }
            }
        }
    }
}

@Composable
private fun SectionHeader(section: Section, modifier: Modifier = Modifier) {
    val color = when (section.kind) {
        SectionKind.OVERDUE -> MaterialTheme.colorScheme.error
        SectionKind.TODAY -> MaterialTheme.colorScheme.primary
        SectionKind.TOMORROW -> MaterialTheme.colorScheme.tertiary
        else -> MaterialTheme.colorScheme.onSurfaceVariant
    }
    Row(
        modifier = modifier.padding(start = 6.dp, top = 18.dp, bottom = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(section.title, style = MaterialTheme.typography.titleSmall, color = color)
        Spacer(Modifier.width(8.dp))
        Box(
            Modifier
                .background(color.copy(alpha = 0.12f), CircleShape)
                .padding(horizontal = 8.dp, vertical = 1.dp)
        ) {
            Text("${section.tasks.size}", style = MaterialTheme.typography.labelMedium, color = color)
        }
    }
}

@Composable
private fun CompletedHeader(
    count: Int,
    expanded: Boolean,
    onToggle: () -> Unit,
    onClear: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val rotation by animateFloatAsState(if (expanded) 180f else 0f, label = "chevron")
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(top = 20.dp)
            .clickable(onClick = onToggle)
            .padding(start = 6.dp, top = 6.dp, bottom = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            "Completed · $count",
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Icon(
            Icons.Rounded.ExpandMore,
            null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.rotate(rotation),
        )
        Spacer(Modifier.weight(1f))
        if (expanded) TextButton(onClick = onClear) { Text("Clear all") }
    }
}

@Composable
private fun EmptyState(inList: Boolean, modifier: Modifier = Modifier) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(top = 72.dp, bottom = 24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(if (inList) "🌱" else "🌤️", fontSize = 64.sp)
        Spacer(Modifier.height(16.dp))
        Text(
            if (inList) "Nothing here yet" else "All clear",
            style = MaterialTheme.typography.headlineMedium,
        )
        Spacer(Modifier.height(6.dp))
        Text(
            if (inList) "Tap + to add the first thing." else "Nothing on your plate. Enjoy it ✨",
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
    }
}

@Composable
private fun DrawerContent(
    state: HomeState,
    soundOn: Boolean,
    onSound: (Boolean) -> Unit,
    onOpenList: (Long?) -> Unit,
    onNewList: () -> Unit,
    syncState: SyncState,
    onOpenSync: () -> Unit,
) {
    ModalDrawerSheet {
        Column(Modifier.padding(horizontal = 12.dp)) {
            Text(
                "Nowish",
                style = MaterialTheme.typography.headlineMedium,
                modifier = Modifier.padding(start = 16.dp, top = 24.dp, bottom = 4.dp),
            )
            Text(
                "${state.space.label} space",
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(start = 16.dp, bottom = 16.dp),
            )
            NavigationDrawerItem(
                label = { Text("All ${state.space.label.lowercase()} tasks") },
                icon = {
                    Icon(if (state.space == Space.WORK) Icons.Rounded.BusinessCenter else Icons.Rounded.Home, null)
                },
                selected = state.list == null,
                onClick = { onOpenList(null) },
            )
            HorizontalDivider(Modifier.padding(vertical = 12.dp, horizontal = 16.dp))
            Text(
                "Lists",
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(start = 16.dp, bottom = 8.dp),
            )
        }
        Column(
            Modifier
                .weight(1f)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 12.dp)
        ) {
            state.lists.forEach { list ->
                NavigationDrawerItem(
                    label = { Text(list.name, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                    icon = { Text(list.emoji, fontSize = 20.sp) },
                    selected = state.list?.id == list.id,
                    onClick = { onOpenList(list.id) },
                )
            }
            NavigationDrawerItem(
                label = { Text("New list") },
                icon = { Icon(Icons.Rounded.Add, null) },
                selected = false,
                onClick = onNewList,
            )
        }
        HorizontalDivider()
        Row(
            Modifier
                .fillMaxWidth()
                .clickable(onClick = onOpenSync)
                .padding(horizontal = 28.dp, vertical = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                when {
                    !syncState.configured -> Icons.Rounded.CloudOff
                    syncState.error != null -> Icons.Rounded.SyncProblem
                    else -> Icons.Rounded.CloudDone
                },
                null,
                tint = if (syncState.error != null) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.width(12.dp))
            Column {
                Text("Sync with laptop", style = MaterialTheme.typography.labelLarge)
                Text(
                    syncSummary(syncState),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        Row(
            Modifier
                .fillMaxWidth()
                .clickable { onSound(!soundOn) }
                .padding(horizontal = 28.dp, vertical = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Rounded.MusicNote, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.width(12.dp))
                Text("Success sound", style = MaterialTheme.typography.labelLarge)
            }
            Switch(checked = soundOn, onCheckedChange = onSound)
        }
    }
}

@Composable
private fun RequestNotificationPermission() {
    if (Build.VERSION.SDK_INT < 33) return
    val context = LocalContext.current
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { }
    LaunchedEffect(Unit) {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            launcher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }
}
