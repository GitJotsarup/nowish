package com.jotsarup.nowish.notes

import android.text.format.DateUtils
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.ExperimentalLayoutApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.AddPhotoAlternate
import androidx.compose.material.icons.rounded.CheckBox
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Code
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.DeleteOutline
import androidx.compose.material.icons.rounded.FormatBold
import androidx.compose.material.icons.rounded.FormatClear
import androidx.compose.material.icons.rounded.FormatItalic
import androidx.compose.material.icons.rounded.FormatListBulleted
import androidx.compose.material.icons.rounded.FormatListNumbered
import androidx.compose.material.icons.rounded.FormatUnderlined
import androidx.compose.material.icons.rounded.Highlight
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.LockOpen
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material.icons.rounded.Palette
import androidx.compose.material.icons.rounded.PushPin
import androidx.compose.material.icons.rounded.Sell
import androidx.compose.material.icons.rounded.StrikethroughS
import androidx.compose.material.icons.rounded.TextFields
import androidx.compose.material.icons.outlined.PushPin
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.imageResource
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import coil.compose.AsyncImage
import com.jotsarup.nowish.data.Category
import com.jotsarup.nowish.data.ChecklistItem
import com.jotsarup.nowish.data.ImageStore
import com.jotsarup.nowish.data.Note
import com.mohamedrejeb.richeditor.model.rememberRichTextState
import com.mohamedrejeb.richeditor.ui.BasicRichTextEditor

private val BoldStyle = SpanStyle(fontWeight = FontWeight.Bold)
private val ItalicStyle = SpanStyle(fontStyle = FontStyle.Italic)
private val UnderlineStyle = SpanStyle(textDecoration = TextDecoration.Underline)
private val StrikeStyle = SpanStyle(textDecoration = TextDecoration.LineThrough)
private val H1Style = SpanStyle(fontSize = 1.5.em, fontWeight = FontWeight.Bold)
private val H2Style = SpanStyle(fontSize = 1.2.em, fontWeight = FontWeight.Bold)
private val MarkStyle = SpanStyle(background = Color.Yellow) // exported as <mark>

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NoteEditor(
    note: Note,
    categories: List<Category>,
    vm: NotesViewModel,
    onLockToggle: () -> Unit,
    onNewCategory: () -> Unit,
) {
    val ink = noteInk(note.color, note.theme)
    var showStyle by remember { mutableStateOf(false) }
    var showCategory by remember { mutableStateOf(false) }
    var showFormat by remember { mutableStateOf(true) }
    var menu by remember { mutableStateOf(false) }
    var viewing by remember { mutableStateOf<String?>(null) }
    val rich = rememberRichTextState()
    val bodyFocus = remember { FocusRequester() }

    BackHandler { vm.close() }

    // Load the HTML once per note, then push edits back as HTML.
    LaunchedEffect(note.uid) {
        rich.setHtml(note.content)
        var baseline = rich.toHtml()
        snapshotFlow { rich.annotatedString }.collect {
            val html = rich.toHtml()
            if (html != baseline) {
                baseline = html
                vm.edit { n -> n.copy(content = html) }
            }
        }
    }

    val pickImage = rememberLauncherForActivityResult(ActivityResultContracts.PickMultipleVisualMedia(6)) { uris ->
        uris.forEach(vm::addImage)
    }

    NoteBackground(
        color = note.color,
        theme = note.theme,
        fallback = MaterialTheme.colorScheme.surface,
        modifier = Modifier.fillMaxSize(),
    ) {
        Column(
            Modifier
                .fillMaxSize()
                .statusBarsPadding()
                .navigationBarsPadding()
                .imePadding()
        ) {
            // ---- top bar ----
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(horizontal = 4.dp)) {
                IconButton(onClick = vm::close) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, "Back", tint = ink) }
                Spacer(Modifier.weight(1f))
                IconButton(onClick = { vm.edit { it.copy(pinned = !it.pinned) } }) {
                    Icon(if (note.pinned) Icons.Rounded.PushPin else Icons.Outlined.PushPin, if (note.pinned) "Unpin" else "Pin", tint = ink)
                }
                IconButton(onClick = onLockToggle) {
                    Icon(if (note.locked) Icons.Rounded.Lock else Icons.Rounded.LockOpen, if (note.locked) "Unlock note" else "Lock note", tint = ink)
                }
                Box {
                    IconButton(onClick = { menu = true }) { Icon(Icons.Rounded.MoreVert, "More", tint = ink) }
                    DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                        DropdownMenuItem(
                            text = { Text("Make a copy") },
                            leadingIcon = { Icon(Icons.Rounded.ContentCopy, null) },
                            onClick = { menu = false; vm.copy(note) },
                        )
                        DropdownMenuItem(
                            text = { Text("Move to trash") },
                            leadingIcon = { Icon(Icons.Rounded.DeleteOutline, null) },
                            onClick = {
                                menu = false
                                vm.close()
                                if (!note.isBlank) vm.trash(note)
                            },
                        )
                    }
                }
            }

            // ---- body ----
            Column(
                Modifier
                    .weight(1f)
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 20.dp)
            ) {
                val images = note.imageList
                if (images.isNotEmpty()) {
                    ImageGrid(images, onOpen = { viewing = it })
                    Spacer(Modifier.height(12.dp))
                }
                BasicTextField(
                    value = note.title,
                    onValueChange = { t -> vm.edit { it.copy(title = t.replace("\n", "")) } },
                    textStyle = MaterialTheme.typography.headlineSmall.copy(color = ink, fontWeight = FontWeight.SemiBold),
                    cursorBrush = SolidColor(ink),
                    keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences, imeAction = ImeAction.Next),
                    keyboardActions = KeyboardActions(onNext = { runCatching { bodyFocus.requestFocus() } }),
                    modifier = Modifier.fillMaxWidth(),
                    decorationBox = { inner ->
                        if (note.title.isEmpty()) Text("Title", style = MaterialTheme.typography.headlineSmall, color = ink.copy(alpha = 0.45f))
                        inner()
                    },
                )
                Spacer(Modifier.height(10.dp))
                val items = note.items
                if (items != null) {
                    ChecklistEditor(items, ink) { updated -> vm.edit { it.copy(checklist = ChecklistItem.toJson(updated)) } }
                } else {
                    Box {
                        if (rich.annotatedString.text.isEmpty()) {
                            Text("Note", style = MaterialTheme.typography.bodyLarge, color = ink.copy(alpha = 0.45f))
                        }
                        BasicRichTextEditor(
                            state = rich,
                            textStyle = MaterialTheme.typography.bodyLarge.copy(color = ink, fontSize = 17.sp, lineHeight = 25.sp),
                            cursorBrush = SolidColor(ink),
                            keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
                            minLines = 6,
                            modifier = Modifier.fillMaxWidth().focusRequester(bodyFocus),
                        )
                    }
                }
                val category = categories.find { it.uid == note.categoryUid }
                Row(Modifier.padding(top = 20.dp, bottom = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        category?.let { "${it.emoji} ${it.name}" } ?: "＋ Category",
                        color = ink,
                        style = MaterialTheme.typography.labelLarge,
                        modifier = Modifier
                            .clip(CircleShape)
                            .background(ink.copy(alpha = 0.12f))
                            .clickable { showCategory = true }
                            .padding(horizontal = 12.dp, vertical = 6.dp),
                    )
                }
            }

            // ---- formatting ----
            if (showFormat && note.items == null) {
                Row(
                    Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState())
                        .padding(horizontal = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    val s = rich.currentSpanStyle
                    FormatButton(Icons.Rounded.FormatBold, "Bold", s.fontWeight == FontWeight.Bold, ink) { rich.toggleSpanStyle(BoldStyle) }
                    FormatButton(Icons.Rounded.FormatItalic, "Italic", s.fontStyle == FontStyle.Italic, ink) { rich.toggleSpanStyle(ItalicStyle) }
                    FormatButton(Icons.Rounded.FormatUnderlined, "Underline", s.textDecoration?.contains(TextDecoration.Underline) == true, ink) { rich.toggleSpanStyle(UnderlineStyle) }
                    FormatButton(Icons.Rounded.StrikethroughS, "Strikethrough", s.textDecoration?.contains(TextDecoration.LineThrough) == true, ink) { rich.toggleSpanStyle(StrikeStyle) }
                    TextFormatButton("H1", s.fontSize == H1Style.fontSize, ink) { rich.toggleSpanStyle(H1Style) }
                    TextFormatButton("H2", s.fontSize == H2Style.fontSize, ink) { rich.toggleSpanStyle(H2Style) }
                    FormatButton(Icons.Rounded.Highlight, "Highlight", s.background == Color.Yellow, ink) { rich.toggleSpanStyle(MarkStyle) }
                    @Suppress("DEPRECATION")
                    FormatButton(Icons.Rounded.FormatListBulleted, "Bulleted list", rich.isUnorderedList, ink) { rich.toggleUnorderedList() }
                    FormatButton(Icons.Rounded.FormatListNumbered, "Numbered list", rich.isOrderedList, ink) { rich.toggleOrderedList() }
                    FormatButton(Icons.Rounded.Code, "Code", rich.isCodeSpan, ink) { rich.toggleCodeSpan() }
                    FormatButton(Icons.Rounded.FormatClear, "Clear formatting", false, ink) {
                        listOf(BoldStyle, ItalicStyle, UnderlineStyle, StrikeStyle, H1Style, H2Style, MarkStyle)
                            .forEach { rich.removeSpanStyle(it) }
                    }
                }
            }

            // ---- bottom bar ----
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 2.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(onClick = { showStyle = true }) { Icon(Icons.Rounded.Palette, "Color & background", tint = ink) }
                IconButton(onClick = { pickImage.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) }) {
                    Icon(Icons.Rounded.AddPhotoAlternate, "Add image", tint = ink)
                }
                IconButton(onClick = vm::toggleChecklist) {
                    Icon(if (note.items != null) Icons.Rounded.TextFields else Icons.Rounded.CheckBox, if (note.items != null) "Turn into text" else "Turn into checklist", tint = ink)
                }
                if (note.items == null) {
                    IconButton(onClick = { showFormat = !showFormat }) {
                        Icon(Icons.Rounded.TextFields, "Formatting", tint = if (showFormat) ink else ink.copy(alpha = 0.5f))
                    }
                }
                Spacer(Modifier.weight(1f))
                Text(
                    "Edited " + DateUtils.getRelativeTimeSpanString(note.updatedAt, System.currentTimeMillis(), DateUtils.MINUTE_IN_MILLIS),
                    color = ink.copy(alpha = 0.7f),
                    style = MaterialTheme.typography.labelMedium,
                    modifier = Modifier.padding(end = 16.dp),
                )
            }
        }
    }

    if (showStyle) {
        StyleSheet(note.color, note.theme, onPick = { c, t -> vm.edit { it.copy(color = c, theme = t) } }, onDismiss = { showStyle = false })
    }
    if (showCategory) {
        CategoryPickerSheet(
            categories, note.categoryUid,
            onPick = { uid -> vm.edit { it.copy(categoryUid = uid) }; showCategory = false },
            onNew = { showCategory = false; onNewCategory() },
            onDismiss = { showCategory = false },
        )
    }
    viewing?.let { uid ->
        ImageViewer(uid, onRemove = { vm.removeImage(uid); viewing = null }, onDismiss = { viewing = null })
    }
}

@Composable
private fun FormatButton(icon: ImageVector, label: String, active: Boolean, ink: Color, onClick: () -> Unit) {
    IconButton(
        onClick = onClick,
        modifier = Modifier
            .size(44.dp)
            .clip(CircleShape)
            .background(if (active) ink.copy(alpha = 0.16f) else Color.Transparent),
    ) { Icon(icon, label, tint = ink) }
}

@Composable
private fun TextFormatButton(label: String, active: Boolean, ink: Color, onClick: () -> Unit) {
    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier
            .size(44.dp)
            .clip(CircleShape)
            .background(if (active) ink.copy(alpha = 0.16f) else Color.Transparent)
            .clickable(onClick = onClick),
    ) { Text(label, color = ink, fontWeight = FontWeight.Bold, fontSize = 15.sp) }
}

@Composable
private fun ImageGrid(images: List<String>, onOpen: (String) -> Unit) {
    val context = LocalContext.current
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        images.chunked(if (images.size == 1) 1 else 2).forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                row.forEach { uid ->
                    AsyncImage(
                        model = ImageStore.file(context, uid),
                        contentDescription = "Image",
                        contentScale = ContentScale.Crop,
                        modifier = Modifier
                            .weight(1f)
                            .aspectRatio(if (images.size == 1) 1.3f else 1f)
                            .clip(RoundedCornerShape(14.dp))
                            .background(Color.Black.copy(alpha = 0.08f))
                            .clickable { onOpen(uid) },
                    )
                }
                if (row.size == 1 && images.size > 1) Spacer(Modifier.weight(1f))
            }
        }
    }
}

@Composable
private fun ImageViewer(uid: String, onRemove: () -> Unit, onDismiss: () -> Unit) {
    val context = LocalContext.current
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Box(Modifier.fillMaxSize().background(Color.Black)) {
            AsyncImage(
                model = ImageStore.file(context, uid),
                contentDescription = "Image",
                contentScale = ContentScale.Fit,
                modifier = Modifier.fillMaxSize(),
            )
            Row(Modifier.statusBarsPadding().padding(8.dp)) {
                IconButton(onClick = onDismiss) { Icon(Icons.Rounded.Close, "Close", tint = Color.White) }
                Spacer(Modifier.weight(1f))
                IconButton(onClick = onRemove) { Icon(Icons.Rounded.DeleteOutline, "Remove image", tint = Color.White) }
            }
        }
    }
}

@Composable
private fun ChecklistEditor(items: List<ChecklistItem>, ink: Color, onChange: (List<ChecklistItem>) -> Unit) {
    var focusId by remember { mutableStateOf<String?>(null) }
    val open = items.filter { !it.done }
    val done = items.filter { it.done }

    val update: (ChecklistItem) -> Unit = { item -> onChange(items.map { if (it.id == item.id) item else it }) }
    val remove: (ChecklistItem) -> Unit = { item -> onChange(items.filter { it.id != item.id }) }
    val addAfter: (ChecklistItem?) -> Unit = { item ->
        val new = ChecklistItem()
        val index = item?.let { i -> items.indexOfFirst { it.id == i.id } + 1 } ?: items.size
        onChange(items.toMutableList().apply { add(index.coerceIn(0, size), new) })
        focusId = new.id
    }

    Column {
        open.forEach { item ->
            key(item.id) {
                ChecklistRow(item, ink, focusId == item.id, { focusId = null }, update, addAfter, remove)
            }
        }
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .clip(RoundedCornerShape(8.dp))
                .clickable { addAfter(open.lastOrNull()) }
                .padding(start = 12.dp, top = 8.dp, bottom = 8.dp, end = 12.dp),
        ) {
            Icon(Icons.Rounded.Add, null, tint = ink.copy(alpha = 0.7f))
            Spacer(Modifier.width(12.dp))
            Text("List item", color = ink.copy(alpha = 0.7f), style = MaterialTheme.typography.bodyLarge)
        }
        if (done.isNotEmpty()) {
            Text(
                "${done.size} checked item${if (done.size == 1) "" else "s"}",
                color = ink.copy(alpha = 0.6f),
                style = MaterialTheme.typography.labelLarge,
                modifier = Modifier.padding(start = 12.dp, top = 12.dp, bottom = 4.dp),
            )
            done.forEach { item ->
                key(item.id) {
                    ChecklistRow(item, ink, false, {}, update, addAfter, remove)
                }
            }
        }
    }
}

@Composable
private fun ChecklistRow(
    item: ChecklistItem,
    ink: Color,
    requestFocus: Boolean,
    onFocused: () -> Unit,
    update: (ChecklistItem) -> Unit,
    addAfter: (ChecklistItem?) -> Unit,
    remove: (ChecklistItem) -> Unit,
) {
    val focus = remember { FocusRequester() }
    LaunchedEffect(requestFocus) {
        if (requestFocus) {
            runCatching { focus.requestFocus() }
            onFocused()
        }
    }
    Row(verticalAlignment = Alignment.CenterVertically) {
        Checkbox(
            checked = item.done,
            onCheckedChange = { update(item.copy(done = it)) },
            colors = CheckboxDefaults.colors(
                checkedColor = ink,
                uncheckedColor = ink.copy(alpha = 0.7f),
                checkmarkColor = if (ink == Color.White) Color.Black else Color.White,
            ),
        )
        BasicTextField(
            value = item.text,
            onValueChange = { t -> if (t.contains('\n')) addAfter(item) else update(item.copy(text = t)) },
            textStyle = MaterialTheme.typography.bodyLarge.copy(
                color = if (item.done) ink.copy(alpha = 0.55f) else ink,
                textDecoration = if (item.done) TextDecoration.LineThrough else null,
            ),
            cursorBrush = SolidColor(ink),
            keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences, imeAction = ImeAction.Next),
            keyboardActions = KeyboardActions(onNext = { addAfter(item) }),
            modifier = Modifier.weight(1f).focusRequester(focus),
        )
        IconButton(onClick = { remove(item) }) {
            Icon(Icons.Rounded.Close, "Remove item", tint = ink.copy(alpha = 0.5f), modifier = Modifier.size(18.dp))
        }
    }
}

// ---------- color & background picker ----------

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun StyleSheet(color: String, theme: String?, onPick: (String, String?) -> Unit, onDismiss: () -> Unit) {
    var c by remember { mutableStateOf(color) }
    var t by remember { mutableStateOf(theme) }
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(Modifier.padding(bottom = 24.dp)) {
            Text("Color", style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(start = 24.dp, bottom = 10.dp))
            LazyRow(contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 20.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                items(NoteColors, key = { it.key }) { nc ->
                    val selected = c == nc.key
                    Box(
                        contentAlignment = Alignment.Center,
                        modifier = Modifier
                            .size(46.dp)
                            .clip(CircleShape)
                            .background(noteColorValue(nc.key, MaterialTheme.colorScheme.surface))
                            .border(if (selected) 3.dp else 1.dp, if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant, CircleShape)
                            .clickable { c = nc.key; onPick(c, t) },
                    ) {
                        if (nc.key == "default") Text("⊘", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 20.sp)
                    }
                }
            }
            Text("Background", style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(start = 24.dp, top = 20.dp, bottom = 10.dp))
            LazyRow(contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 20.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                item(key = "none") {
                    Box(
                        contentAlignment = Alignment.Center,
                        modifier = Modifier
                            .size(width = 64.dp, height = 100.dp)
                            .clip(RoundedCornerShape(14.dp))
                            .background(MaterialTheme.colorScheme.surfaceContainerHigh)
                            .border(if (t == null) 3.dp else 0.dp, MaterialTheme.colorScheme.primary, RoundedCornerShape(14.dp))
                            .clickable { t = null; onPick(c, t) },
                    ) { Text("None", style = MaterialTheme.typography.labelMedium) }
                }
                items(NoteThemes, key = { it.key }) { th ->
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        PixelImage(
                            ImageBitmap.imageResource(th.res),
                            Modifier
                                .size(width = 64.dp, height = 100.dp)
                                .clip(RoundedCornerShape(14.dp))
                                .border(if (t == th.key) 3.dp else 0.dp, MaterialTheme.colorScheme.primary, RoundedCornerShape(14.dp))
                                .clickable { t = th.key; onPick(c, t) },
                            alignment = Alignment.Center,
                        )
                        Text(th.name, style = MaterialTheme.typography.labelSmall, modifier = Modifier.padding(top = 4.dp), maxLines = 1)
                    }
                }
            }
        }
    }
}

// ---------- categories ----------

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun CategoryPickerSheet(
    categories: List<Category>,
    selected: String?,
    onPick: (String?) -> Unit,
    onNew: (() -> Unit)?,
    onDismiss: () -> Unit,
) {
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(Modifier.padding(start = 24.dp, end = 24.dp, bottom = 24.dp)) {
            Text("Category", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(bottom = 12.dp))
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                FilterChip(selected = selected == null, onClick = { onPick(null) }, label = { Text("None") }, shape = CircleShape)
                categories.forEach { c ->
                    FilterChip(selected = selected == c.uid, onClick = { onPick(c.uid) }, label = { Text("${c.emoji} ${c.name}") }, shape = CircleShape)
                }
                if (onNew != null) {
                    FilterChip(selected = false, onClick = onNew, label = { Text("＋ New category") }, leadingIcon = { Icon(Icons.Rounded.Sell, null, Modifier.size(16.dp)) }, shape = CircleShape)
                }
            }
        }
    }
}

private val categoryEmojis = listOf(
    "📁", "💼", "☬", "🙏", "📿", "📖", "🎓", "💡", "🧠", "❤️", "🏠", "🛒", "✈️", "🎬", "🎵", "🍳",
    "🏋️", "💰", "🌱", "🎨", "💻", "📝", "⭐", "🔥", "🌙", "☀️", "🐾", "🎮", "📷", "🧾",
)

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun CategoryDialog(
    existing: Category?,
    onDismiss: () -> Unit,
    onSave: (String, String, String) -> Unit,
    onDelete: (() -> Unit)?,
) {
    var name by remember { mutableStateOf(existing?.name ?: "") }
    var emoji by remember { mutableStateOf(existing?.emoji ?: categoryEmojis.first()) }
    var color by remember { mutableStateOf(existing?.color ?: "default") }
    var confirmDelete by remember { mutableStateOf(false) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (existing == null) "New category" else "Edit category") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it.replace("\n", "") },
                    label = { Text("Name") },
                    placeholder = { Text("Sikhi notes") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Words),
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(14.dp))
                FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    categoryEmojis.forEach { e ->
                        Box(
                            contentAlignment = Alignment.Center,
                            modifier = Modifier
                                .size(40.dp)
                                .clip(CircleShape)
                                .background(if (e == emoji) MaterialTheme.colorScheme.primaryContainer else Color.Transparent)
                                .clickable { emoji = e },
                        ) { Text(e, fontSize = 20.sp) }
                    }
                }
                Spacer(Modifier.height(14.dp))
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    NoteColors.forEach { nc ->
                        Box(
                            Modifier
                                .size(32.dp)
                                .clip(CircleShape)
                                .background(noteColorValue(nc.key, MaterialTheme.colorScheme.surfaceContainerHigh))
                                .border(if (color == nc.key) 3.dp else 1.dp, if (color == nc.key) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant, CircleShape)
                                .clickable { color = nc.key }
                        )
                    }
                }
                if (onDelete != null) {
                    TextButton(onClick = { confirmDelete = true }, modifier = Modifier.padding(top = 8.dp)) {
                        Text("Delete category", color = MaterialTheme.colorScheme.error)
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { onSave(name, emoji, color) }, enabled = name.isNotBlank()) {
                Text(if (existing == null) "Create" else "Save")
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
    if (confirmDelete && onDelete != null) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text("Delete ${existing?.name}?") },
            text = { Text("Notes in it stay, they just won't have a category.") },
            confirmButton = { TextButton(onClick = onDelete) { Text("Delete", color = MaterialTheme.colorScheme.error) } },
            dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("Cancel") } },
        )
    }
}

