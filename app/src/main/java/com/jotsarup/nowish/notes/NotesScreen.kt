package com.jotsarup.nowish.notes

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.staggeredgrid.LazyVerticalStaggeredGrid
import androidx.compose.foundation.lazy.staggeredgrid.StaggeredGridCells
import androidx.compose.foundation.lazy.staggeredgrid.StaggeredGridItemSpan
import androidx.compose.foundation.lazy.staggeredgrid.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.DeleteOutline
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.LockOpen
import androidx.compose.material.icons.rounded.Palette
import androidx.compose.material.icons.rounded.PushPin
import androidx.compose.material.icons.rounded.RestoreFromTrash
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.Sell
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil.compose.AsyncImage
import com.jotsarup.nowish.data.Category
import com.jotsarup.nowish.data.ImageStore
import com.jotsarup.nowish.data.Note
import com.jotsarup.nowish.ui.confirmHaptic

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NotesRoot(vm: NotesViewModel, bottomBar: @Composable () -> Unit) {
    val state by vm.state.collectAsStateWithLifecycle()
    val editing by vm.editing.collectAsStateWithLifecycle()
    val f = state.filter

    var actionsFor by remember { mutableStateOf<Note?>(null) }
    var trashDialog by remember { mutableStateOf<Note?>(null) }
    var categoryDialog by remember { mutableStateOf<CategoryDialogState?>(null) }
    var pendingUnlock by remember { mutableStateOf<(() -> Unit)?>(null) }
    var askSetPin by remember { mutableStateOf<(() -> Unit)?>(null) }

    /** Runs [action] once locked notes are unlocked (asks for PIN / fingerprint if needed). */
    fun withUnlock(action: () -> Unit) {
        when {
            f.unlocked -> action()
            // No PIN yet (e.g. a note locked on the laptop before the PIN synced): just let them in.
            !f.lockConfigured -> { vm.unlock(); action() }
            else -> pendingUnlock = action
        }
    }

    fun openNote(note: Note) {
        if (note.locked) withUnlock { vm.open(note) } else vm.open(note)
    }

    BackHandler(enabled = f.showTrash && editing == null) { vm.showTrash(false) }
    BackHandler(enabled = !f.showTrash && editing == null && (f.query.isNotEmpty() || f.category != null)) {
        vm.setQuery("")
        vm.selectCategory(null)
    }

    Box(Modifier.fillMaxSize()) {
        Scaffold(
            bottomBar = bottomBar,
            floatingActionButton = {
                if (!f.showTrash) {
                    FloatingActionButton(
                        onClick = { vm.newNote() },
                        containerColor = MaterialTheme.colorScheme.primaryContainer,
                        contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                        shape = MaterialTheme.shapes.large,
                    ) { Icon(Icons.Rounded.Add, "New note", Modifier.size(28.dp)) }
                }
            },
            topBar = {
                Column(
                    Modifier
                        .background(MaterialTheme.colorScheme.surface)
                        .statusBarsPadding()
                ) {
                    if (f.showTrash) {
                        TrashBar(
                            count = state.trash.size,
                            onBack = { vm.showTrash(false) },
                            onEmpty = { vm.emptyTrash() },
                        )
                    } else {
                        SearchBar(
                            query = f.query,
                            onQuery = vm::setQuery,
                            unlocked = f.unlocked,
                            onRelock = vm::relock,
                            onTrash = { vm.showTrash(true) },
                        )
                        CategoryRow(
                            categories = state.categories,
                            selected = f.category,
                            counts = state.counts,
                            onSelect = vm::selectCategory,
                            onEdit = { categoryDialog = CategoryDialogState(it) },
                            onAdd = { categoryDialog = CategoryDialogState(null) },
                        )
                    }
                }
            },
        ) { padding ->
            val cats = remember(state.categories) { state.categories.associateBy { it.uid } }
            val cards = if (f.showTrash) state.trash else state.pinned + state.others
            if (state.loaded && cards.isEmpty()) {
                EmptyNotes(
                    trash = f.showTrash,
                    searching = f.query.isNotBlank() || f.category != null,
                    modifier = Modifier.padding(padding),
                )
            }
            LazyVerticalStaggeredGrid(
                columns = StaggeredGridCells.Fixed(2),
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(
                    start = 12.dp,
                    end = 12.dp,
                    top = padding.calculateTopPadding() + 4.dp,
                    bottom = padding.calculateBottomPadding() + 96.dp,
                ),
                verticalItemSpacing = 10.dp,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                fun header(key: String, text: String) {
                    item(key = key, span = StaggeredGridItemSpan.FullLine) {
                        Text(
                            text,
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(start = 6.dp, top = 6.dp),
                        )
                    }
                }
                if (f.showTrash) {
                    header("trash-info", "Notes in the trash are deleted after 30 days")
                    items(state.trash, key = { it.uid }) { note ->
                        NoteCard(note, cats[note.categoryUid], f.unlocked, onClick = { trashDialog = note }, onLongClick = { trashDialog = note })
                    }
                } else {
                    if (state.pinned.isNotEmpty()) {
                        header("h-pinned", "PINNED")
                        items(state.pinned, key = { it.uid }) { note ->
                            NoteCard(note, cats[note.categoryUid], f.unlocked, onClick = { openNote(note) }, onLongClick = { actionsFor = note }, modifier = Modifier.animateItem())
                        }
                        if (state.others.isNotEmpty()) header("h-others", "OTHERS")
                    }
                    items(state.others, key = { it.uid }) { note ->
                        NoteCard(note, cats[note.categoryUid], f.unlocked, onClick = { openNote(note) }, onLongClick = { actionsFor = note }, modifier = Modifier.animateItem())
                    }
                }
            }
        }

        AnimatedVisibility(
            visible = editing != null,
            enter = slideInVertically { it / 6 } + fadeIn(),
            exit = slideOutVertically { it / 6 } + fadeOut(),
        ) {
            // Keep the last note while animating out.
            var last by remember { mutableStateOf<Note?>(null) }
            editing?.let { last = it }
            last?.let { note ->
                NoteEditor(
                    note = note,
                    categories = state.categories,
                    vm = vm,
                    onLockToggle = {
                        if (note.locked) vm.edit { it.copy(locked = false) }
                        else if (f.lockConfigured) vm.edit { it.copy(locked = true) }
                        else askSetPin = { vm.edit { it.copy(locked = true) } }
                    },
                    onNewCategory = { categoryDialog = CategoryDialogState(null, assignToEditing = true) },
                )
            }
        }
    }

    actionsFor?.let { note ->
        NoteActionsSheet(
            note = note,
            categories = state.categories,
            onDismiss = { actionsFor = null },
            onPin = { vm.togglePin(note) },
            onStyle = { color, theme -> vm.save(note.copy(color = color, theme = theme)) },
            onCategory = { vm.save(note.copy(categoryUid = it)) },
            onLock = {
                when {
                    note.locked -> withUnlock { vm.save(note.copy(locked = false)) }
                    f.lockConfigured -> vm.save(note.copy(locked = true))
                    else -> askSetPin = { vm.save(note.copy(locked = true)) }
                }
            },
            onCopy = { vm.copy(note) },
            onTrash = { vm.trash(note) },
        )
    }

    trashDialog?.let { note ->
        AlertDialog(
            onDismissRequest = { trashDialog = null },
            title = { Text(note.title.ifBlank { "This note" }) },
            text = { Text("Restore it, or delete it forever?") },
            confirmButton = {
                TextButton(onClick = { vm.restore(note); trashDialog = null }) {
                    Icon(Icons.Rounded.RestoreFromTrash, null)
                    Text(" Restore")
                }
            },
            dismissButton = {
                TextButton(onClick = { vm.deleteForever(note); trashDialog = null }) {
                    Text("Delete forever", color = MaterialTheme.colorScheme.error)
                }
            },
        )
    }

    categoryDialog?.let { d ->
        CategoryDialog(
            existing = d.category,
            onDismiss = { categoryDialog = null },
            onSave = { name, emoji, color ->
                if (d.category == null) {
                    vm.addCategory(name, emoji, color) { created ->
                        if (d.assignToEditing) vm.edit { it.copy(categoryUid = created.uid) } else vm.selectCategory(created.uid)
                    }
                } else {
                    vm.saveCategory(d.category.copy(name = name.trim(), emoji = emoji, color = color))
                }
                categoryDialog = null
            },
            onDelete = d.category?.let { c -> { vm.deleteCategory(c); categoryDialog = null } },
        )
    }

    pendingUnlock?.let { action ->
        UnlockDialog(
            checkPin = vm::checkPin,
            allowBiometric = vm.biometricEnabled,
            onUnlocked = { vm.unlock(); pendingUnlock = null; action() },
            onDismiss = { pendingUnlock = null },
        )
    }

    askSetPin?.let { then ->
        SetPinDialog(
            onSet = { pin -> vm.setPin(pin); askSetPin = null; then() },
            onDismiss = { askSetPin = null },
        )
    }
}

data class CategoryDialogState(val category: Category?, val assignToEditing: Boolean = false)

@Composable
private fun SearchBar(
    query: String,
    onQuery: (String) -> Unit,
    unlocked: Boolean,
    onRelock: () -> Unit,
    onTrash: () -> Unit,
) {
    Surface(
        shape = CircleShape,
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 10.dp)
            .height(52.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(start = 18.dp, end = 4.dp)) {
            Icon(Icons.Rounded.Search, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.width(12.dp))
            Box(Modifier.weight(1f)) {
                if (query.isEmpty()) {
                    Text("Search your notes", color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodyLarge)
                }
                BasicTextField(
                    value = query,
                    onValueChange = onQuery,
                    singleLine = true,
                    textStyle = MaterialTheme.typography.bodyLarge.copy(color = MaterialTheme.colorScheme.onSurface),
                    cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            if (query.isNotEmpty()) {
                IconButton(onClick = { onQuery("") }) { Icon(Icons.Rounded.Close, "Clear search") }
            }
            if (unlocked) {
                IconButton(onClick = onRelock) { Icon(Icons.Rounded.LockOpen, "Lock notes again") }
            }
            IconButton(onClick = onTrash) { Icon(Icons.Rounded.DeleteOutline, "Trash") }
        }
    }
}

@Composable
private fun TrashBar(count: Int, onBack: () -> Unit, onEmpty: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, "Back") }
        Text("Trash", style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
        if (count > 0) TextButton(onClick = onEmpty) { Text("Empty trash") }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun CategoryRow(
    categories: List<Category>,
    selected: String?,
    counts: Map<String?, Int>,
    onSelect: (String?) -> Unit,
    onEdit: (Category) -> Unit,
    onAdd: () -> Unit,
) {
    LazyRow(
        contentPadding = PaddingValues(horizontal = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        modifier = Modifier.padding(bottom = 6.dp),
    ) {
        item(key = "all") {
            CategoryCircle("✨", "All", "default", selected == null, counts[null] ?: 0, onClick = { onSelect(null) }, onLongClick = null)
        }
        items(categories, key = { it.uid }) { c ->
            CategoryCircle(
                c.emoji, c.name, c.color, selected == c.uid, counts[c.uid] ?: 0,
                onClick = { onSelect(if (selected == c.uid) null else c.uid) },
                onLongClick = { onEdit(c) },
            )
        }
        item(key = "add") {
            CategoryCircle("+", "New", "default", false, 0, onClick = onAdd, onLongClick = null, dashed = true)
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun CategoryCircle(
    emoji: String,
    label: String,
    color: String,
    selected: Boolean,
    count: Int,
    onClick: () -> Unit,
    onLongClick: (() -> Unit)?,
    dashed: Boolean = false,
) {
    val scale by animateFloatAsState(if (selected) 1.06f else 1f, label = "cat")
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier
            .width(74.dp)
            .clip(RoundedCornerShape(16.dp))
            .combinedClickable(onClick = onClick, onLongClick = onLongClick)
            .padding(vertical = 6.dp),
    ) {
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier
                .scale(scale)
                .size(58.dp)
                .clip(CircleShape)
                .background(noteColorValue(color, MaterialTheme.colorScheme.surfaceContainerHigh))
                .then(
                    if (selected) Modifier.border(3.dp, MaterialTheme.colorScheme.primary, CircleShape)
                    else if (dashed) Modifier.border(1.5.dp, MaterialTheme.colorScheme.outline, CircleShape)
                    else Modifier
                ),
        ) {
            Text(
                emoji,
                fontSize = if (dashed) 26.sp else 24.sp,
                color = if (dashed) MaterialTheme.colorScheme.onSurfaceVariant else Color.Unspecified,
            )
        }
        Spacer(Modifier.height(4.dp))
        Text(
            label,
            style = MaterialTheme.typography.labelMedium,
            fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium,
            color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        if (count > 0 && !dashed) {
            Text("$count", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.outline)
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun NoteCard(
    note: Note,
    category: Category?,
    unlocked: Boolean,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val view = LocalView.current
    val hidden = note.locked && !unlocked
    val ink = noteInk(note.color, note.theme)
    val outline = noteColor(note.color).key == "default" && note.theme == null
    NoteBackground(
        color = note.color,
        theme = note.theme,
        fallback = MaterialTheme.colorScheme.surface,
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(18.dp))
            .then(if (outline) Modifier.border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(18.dp)) else Modifier)
            .combinedClickable(onClick = onClick, onLongClick = { view.confirmHaptic(); onLongClick() }),
    ) {
        Column {
            val images = note.imageList
            if (!hidden && images.isNotEmpty()) {
                Box {
                    AsyncImage(
                        model = ImageStore.file(context, images.first()),
                        contentDescription = null,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier
                            .fillMaxWidth()
                            .aspectRatio(if (images.size > 1) 1.4f else 1.1f)
                            .background(Color.Black.copy(alpha = 0.06f)),
                    )
                    if (images.size > 1) {
                        Text(
                            "+${images.size - 1}",
                            color = Color.White,
                            style = MaterialTheme.typography.labelMedium,
                            modifier = Modifier
                                .align(Alignment.BottomEnd)
                                .padding(6.dp)
                                .background(Color.Black.copy(alpha = 0.5f), CircleShape)
                                .padding(horizontal = 8.dp, vertical = 2.dp),
                        )
                    }
                }
            }
            Column(Modifier.padding(14.dp)) {
                if (hidden) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Rounded.Lock, null, tint = ink, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(8.dp))
                        Text("Locked note", color = ink, style = MaterialTheme.typography.titleSmall)
                    }
                } else {
                    Row(verticalAlignment = Alignment.Top) {
                        if (note.title.isNotBlank()) {
                            Text(
                                note.title,
                                color = ink,
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.SemiBold,
                                maxLines = 3,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.weight(1f),
                            )
                        } else {
                            Spacer(Modifier.weight(1f))
                        }
                        if (note.pinned) Icon(Icons.Rounded.PushPin, "Pinned", tint = ink.copy(alpha = 0.7f), modifier = Modifier.size(16.dp))
                        if (note.locked) Icon(Icons.Rounded.Lock, "Locked", tint = ink.copy(alpha = 0.7f), modifier = Modifier.size(16.dp))
                    }
                    val items = note.items
                    if (items != null) {
                        val shown = items.filter { it.text.isNotBlank() }.sortedBy { it.done }.take(8)
                        shown.forEach { item ->
                            Text(
                                (if (item.done) "☑  " else "☐  ") + item.text,
                                color = ink.copy(alpha = if (item.done) 0.55f else 0.9f),
                                textDecoration = if (item.done) TextDecoration.LineThrough else null,
                                style = MaterialTheme.typography.bodyMedium,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.padding(top = 4.dp),
                            )
                        }
                        val more = items.count { it.text.isNotBlank() } - shown.size
                        if (more > 0) Text("+ $more more", color = ink.copy(alpha = 0.6f), style = MaterialTheme.typography.labelMedium)
                    } else {
                        val text = note.plainText
                        if (text.isNotBlank()) {
                            Text(
                                text,
                                color = ink.copy(alpha = 0.88f),
                                style = MaterialTheme.typography.bodyMedium,
                                maxLines = 10,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.padding(top = if (note.title.isNotBlank()) 6.dp else 0.dp),
                            )
                        }
                    }
                }
                if (category != null) {
                    Text(
                        "${category.emoji} ${category.name}",
                        color = ink,
                        style = MaterialTheme.typography.labelSmall,
                        maxLines = 1,
                        modifier = Modifier
                            .padding(top = 10.dp)
                            .background(ink.copy(alpha = 0.12f), CircleShape)
                            .padding(horizontal = 8.dp, vertical = 3.dp),
                    )
                }
            }
        }
    }
}

@Composable
private fun EmptyNotes(trash: Boolean, searching: Boolean, modifier: Modifier = Modifier) {
    Column(
        modifier.fillMaxWidth().padding(top = 80.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(if (trash) "🗑️" else if (searching) "🔍" else "💡", fontSize = 60.sp)
        Spacer(Modifier.height(12.dp))
        Text(
            when {
                trash -> "Trash is empty"
                searching -> "No matching notes"
                else -> "Notes you add appear here"
            },
            style = MaterialTheme.typography.titleLarge,
            textAlign = TextAlign.Center,
        )
        if (!trash && !searching) {
            Text(
                "Tap + to write one",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 4.dp),
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun NoteActionsSheet(
    note: Note,
    categories: List<Category>,
    onDismiss: () -> Unit,
    onPin: () -> Unit,
    onStyle: (String, String?) -> Unit,
    onCategory: (String?) -> Unit,
    onLock: () -> Unit,
    onCopy: () -> Unit,
    onTrash: () -> Unit,
) {
    var showStyle by remember { mutableStateOf(false) }
    var showCategory by remember { mutableStateOf(false) }
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(Modifier.padding(bottom = 16.dp)) {
            Text(
                note.title.ifBlank { note.plainText.take(40).ifBlank { "Note" } },
                style = MaterialTheme.typography.titleMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp),
            )
            ActionRow(Icons.Rounded.PushPin, if (note.pinned) "Unpin" else "Pin to top") { onPin(); onDismiss() }
            ActionRow(Icons.Rounded.Palette, "Color & background") { showStyle = true }
            ActionRow(Icons.Rounded.Sell, "Category") { showCategory = true }
            ActionRow(
                if (note.locked) Icons.Rounded.LockOpen else Icons.Rounded.Lock,
                if (note.locked) "Unlock note" else "Lock note",
            ) { onLock(); onDismiss() }
            ActionRow(Icons.Rounded.ContentCopy, "Make a copy") { onCopy(); onDismiss() }
            ActionRow(Icons.Rounded.DeleteOutline, "Move to trash") { onTrash(); onDismiss() }
        }
    }
    if (showStyle) {
        StyleSheet(note.color, note.theme, onPick = { c, t -> onStyle(c, t) }, onDismiss = { showStyle = false; onDismiss() })
    }
    if (showCategory) {
        CategoryPickerSheet(categories, note.categoryUid, onPick = { onCategory(it); showCategory = false; onDismiss() }, onNew = null, onDismiss = { showCategory = false })
    }
}

@Composable
private fun ActionRow(icon: ImageVector, text: String, onClick: () -> Unit) {
    ListItem(
        headlineContent = { Text(text) },
        leadingContent = { Icon(icon, null) },
        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
        modifier = Modifier.clickable(onClick = onClick),
    )
}
