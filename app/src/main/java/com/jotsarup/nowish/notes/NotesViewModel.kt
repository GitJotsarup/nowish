package com.jotsarup.nowish.notes

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.jotsarup.nowish.NowishApp
import com.jotsarup.nowish.data.Category
import com.jotsarup.nowish.data.ChecklistItem
import com.jotsarup.nowish.data.ImageStore
import com.jotsarup.nowish.data.Note
import com.jotsarup.nowish.data.imagesJson
import com.jotsarup.nowish.data.newUid
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

data class NotesFilter(
    val query: String = "",
    val category: String? = null,
    val showTrash: Boolean = false,
    val unlocked: Boolean = false,
    val lockConfigured: Boolean = false,
)

data class NotesState(
    val pinned: List<Note> = emptyList(),
    val others: List<Note> = emptyList(),
    val trash: List<Note> = emptyList(),
    val categories: List<Category> = emptyList(),
    val counts: Map<String?, Int> = emptyMap(),
    val filter: NotesFilter = NotesFilter(),
    val loaded: Boolean = false,
)

class NotesViewModel(application: Application) : AndroidViewModel(application) {
    private val app = application as NowishApp
    private val repo = app.notes

    private val filter = MutableStateFlow(NotesFilter(lockConfigured = app.prefs.lockConfigured))

    val state: StateFlow<NotesState> = combine(repo.notes(), repo.categories(), filter) { notes, cats, f ->
        val live = notes.filter { it.trashedAt == null }
        val q = f.query.trim()
        val visible = live
            .filter { f.category == null || it.categoryUid == f.category }
            .filter { n ->
                q.isEmpty() || (!n.locked || f.unlocked) && (
                    n.title.contains(q, true) || n.plainText.contains(q, true) ||
                        n.items.orEmpty().any { it.text.contains(q, true) }
                    )
            }
        NotesState(
            pinned = visible.filter { it.pinned },
            others = visible.filter { !it.pinned },
            trash = notes.filter { it.trashedAt != null }.sortedByDescending { it.trashedAt },
            categories = cats,
            counts = live.groupingBy { it.categoryUid }.eachCount() + (null to live.size),
            filter = f,
            loaded = true,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), NotesState())

    // ---------- filters ----------
    fun setQuery(q: String) = filter.update { it.copy(query = q) }
    fun selectCategory(uid: String?) = filter.update { it.copy(category = uid) }
    fun showTrash(show: Boolean) = filter.update { it.copy(showTrash = show) }

    // ---------- lock ----------
    fun refreshLock() = filter.update { it.copy(lockConfigured = app.prefs.lockConfigured) }
    fun unlock() = filter.update { it.copy(unlocked = true) }
    fun relock() = filter.update { it.copy(unlocked = false) }
    fun checkPin(pin: String): Boolean = repo.checkPin(pin).also { if (it) unlock() }
    fun setPin(pin: String) {
        repo.setPin(pin)
        filter.update { it.copy(lockConfigured = true, unlocked = true) }
    }
    val biometricEnabled: Boolean get() = app.prefs.biometricUnlock

    // ---------- editor ----------
    private val _editing = MutableStateFlow<Note?>(null)
    val editing: StateFlow<Note?> = _editing.asStateFlow()
    private var dirty = false
    private var saveJob: Job? = null
    private val saveMutex = Mutex()

    fun open(note: Note) {
        dirty = false
        _editing.value = note
    }

    fun newNote(checklist: Boolean = false) {
        dirty = false
        _editing.value = Note(
            categoryUid = filter.value.category,
            checklist = if (checklist) ChecklistItem.toJson(listOf(ChecklistItem())) else null,
        )
    }

    fun edit(transform: (Note) -> Note) {
        _editing.update { it?.let(transform) }
        dirty = true
        saveJob?.cancel()
        saveJob = viewModelScope.launch {
            delay(600)
            persist()
        }
    }

    private suspend fun persist() = saveMutex.withLock {
        val note = _editing.value ?: return@withLock
        if (!dirty || (note.id == 0L && note.isBlank)) return@withLock
        val saved = repo.save(note)
        dirty = false
        _editing.update { if (it?.uid == saved.uid && it.id == 0L) it.copy(id = saved.id) else it }
    }

    fun close() {
        val note = _editing.value ?: return
        val wasDirty = dirty
        _editing.value = null
        saveJob?.cancel()
        viewModelScope.launch {
            saveMutex.withLock {
                when {
                    note.isBlank -> if (note.id != 0L) repo.deleteForever(listOf(note))
                    wasDirty -> repo.save(note)
                }
            }
        }
    }

    /** Moves the open note to the trash and closes it (blank notes are just dropped). */
    fun trashEditing() {
        if (_editing.value?.isBlank == false) edit { it.copy(trashedAt = System.currentTimeMillis(), pinned = false) }
        close()
    }

    fun addImage(uri: Uri) {
        viewModelScope.launch {
            val uid = ImageStore.import(app, uri) ?: return@launch
            edit { it.copy(images = imagesJson(it.imageList + uid)) }
        }
    }

    fun removeImage(uid: String) = edit { it.copy(images = imagesJson(it.imageList - uid)) }

    /** Converts between free text and a checklist, keeping the lines. */
    fun toggleChecklist() = edit { n ->
        if (n.checklist == null) {
            val lines = n.plainText.lines().map { it.trim() }.filter { it.isNotEmpty() }
            n.copy(checklist = ChecklistItem.toJson(lines.map { ChecklistItem(text = it) }.ifEmpty { listOf(ChecklistItem()) }), content = "")
        } else {
            val html = n.items.orEmpty().filter { it.text.isNotBlank() }
                .joinToString("") { "<p>${it.text.escapeHtml()}</p>" }
            n.copy(checklist = null, content = html)
        }
    }

    // ---------- grid actions ----------
    fun save(note: Note) = viewModelScope.launch { repo.save(note) }
    fun togglePin(note: Note) = save(note.copy(pinned = !note.pinned))
    fun trash(note: Note) = viewModelScope.launch { repo.trash(note) }
    fun restore(note: Note) = viewModelScope.launch { repo.restore(note) }
    fun deleteForever(note: Note) = viewModelScope.launch { repo.deleteForever(listOf(note)) }
    fun emptyTrash() = viewModelScope.launch { repo.deleteForever(state.value.trash) }

    fun copy(note: Note) = viewModelScope.launch {
        repo.save(note.copy(id = 0, uid = newUid(), pinned = false, createdAt = System.currentTimeMillis()))
    }

    // ---------- categories ----------
    fun addCategory(name: String, emoji: String, color: String, then: (Category) -> Unit = {}) =
        viewModelScope.launch { then(repo.addCategory(name, emoji, color)) }

    fun saveCategory(category: Category) = viewModelScope.launch { repo.saveCategory(category) }

    fun deleteCategory(category: Category) = viewModelScope.launch {
        if (filter.value.category == category.uid) selectCategory(null)
        repo.deleteCategory(category)
    }
}

private fun String.escapeHtml() = replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
