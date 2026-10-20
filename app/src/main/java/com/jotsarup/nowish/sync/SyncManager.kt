package com.jotsarup.nowish.sync

import android.util.Base64
import com.jotsarup.nowish.NowishApp
import com.jotsarup.nowish.data.Category
import com.jotsarup.nowish.data.ImageStore
import com.jotsarup.nowish.data.Note
import com.jotsarup.nowish.data.Space
import com.jotsarup.nowish.data.Task
import com.jotsarup.nowish.data.TaskList
import com.jotsarup.nowish.reminders.Notifications
import com.jotsarup.nowish.reminders.ReminderScheduler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.time.LocalDate
import java.time.LocalTime
import java.time.format.DateTimeFormatter

data class SyncState(
    val configured: Boolean,
    val syncing: Boolean = false,
    val lastSyncAt: Long = 0L,
    val error: String? = null,
)

/**
 * Syncs with a single `nowish.json` file in a private GitHub repo, which the web app reads and
 * writes too. Every record has a uid and updatedAt; the newer copy wins, and deletes are kept as
 * tombstones for 30 days so they propagate.
 */
class SyncManager(private val app: NowishApp) {
    private val dao get() = app.database.dao()
    private val prefs get() = app.prefs
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val mutex = Mutex()
    private var pending: Job? = null

    // Last remote snapshot, so polling can use ETags (304s don't count against the rate limit).
    private var remoteEtag: String? = null
    private var remoteSha: String? = null
    private var remoteCache: RemoteData? = null

    private val _state = MutableStateFlow(
        SyncState(prefs.syncConfigured, lastSyncAt = prefs.lastSyncAt, error = prefs.lastSyncError)
    )
    val state: StateFlow<SyncState> = _state.asStateFlow()

    /** Debounced sync after a local change. */
    @Volatile private var pendingWaiting = false

    fun requestSync(delayMs: Long = 1500) {
        if (!prefs.syncConfigured) return
        // Only restart the debounce; never cancel a sync that's already talking to GitHub.
        if (pendingWaiting) pending?.cancel()
        pendingWaiting = true
        pending = scope.launch {
            delay(delayMs)
            pendingWaiting = false
            sync()
        }
    }

    fun configure(repo: String, token: String) {
        prefs.syncRepo = repo
        prefs.syncToken = token
        prefs.lastSyncError = null
        remoteEtag = null
        remoteSha = null
        remoteCache = null
        checkedRepo = null
        _state.value = SyncState(prefs.syncConfigured, lastSyncAt = prefs.lastSyncAt)
        requestSync(0)
    }

    suspend fun sync(): Boolean = withContext(Dispatchers.IO) {
        if (!prefs.syncConfigured) return@withContext false
        mutex.withLock {
            _state.value = _state.value.copy(configured = true, syncing = true)
            var error: String? = null
            var ok = false
            for (attempt in 0 until 3) {
                try {
                    if (syncOnce()) {
                        ok = true
                        break
                    }
                    // Someone else wrote in between; refetch and merge again.
                    remoteEtag = null
                } catch (e: kotlinx.coroutines.CancellationException) {
                    _state.value = _state.value.copy(syncing = false)
                    throw e
                } catch (e: SyncException) {
                    error = e.message
                    break
                } catch (e: Exception) {
                    error = "Couldn't reach GitHub"
                    break
                }
            }
            if (ok) prefs.lastSyncAt = System.currentTimeMillis()
            prefs.lastSyncError = error
            _state.value = SyncState(true, false, prefs.lastSyncAt, error)
            ok
        }
    }

    private var checkedRepo: String? = null

    /** Refuses to sync into a public repo, since the file holds all your tasks and notes. */
    private fun ensurePrivateRepo() {
        val repo = prefs.syncRepo
        if (checkedRepo == repo) return
        val res = http("GET", "https://api.github.com/repos/$repo")
        when (res.code) {
            200 -> if (!JSONObject(res.body).optBoolean("private", false)) {
                throw SyncException("$repo is public. Use a private repo like nowish-data")
            }
            404 -> throw SyncException("Can't find repo $repo (or the token can't see it)")
            401, 403 -> throw SyncException("Token rejected. Check it has access to $repo")
            else -> throw SyncException("GitHub error ${res.code}")
        }
        checkedRepo = repo
    }

    /** Returns false on a write conflict (caller retries). */
    private suspend fun syncOnce(): Boolean {
        ensurePrivateRepo()
        val remote = fetchRemote()
        val now = System.currentTimeMillis()
        val purgeBefore = now - TOMBSTONE_TTL
        dao.purgeTasks(purgeBefore)
        dao.purgeLists(purgeBefore)

        var localChanged = false
        var needPush = remote == null

        // ---- lists ----
        val localLists = dao.allListsForSync().associateBy { it.uid }
        val remoteLists = remote?.lists.orEmpty()
        for (r in remoteLists) {
            val l = localLists[r.uid]
            when {
                l == null -> if (!r.deleted || r.updatedAt > purgeBefore) {
                    dao.insertList(r.copy(id = 0))
                    localChanged = true
                }
                r.updatedAt > l.updatedAt -> {
                    dao.updateList(r.copy(id = l.id))
                    localChanged = true
                }
                l.updatedAt > r.updatedAt -> needPush = true
            }
        }
        val remoteListUids = remoteLists.mapTo(HashSet()) { it.uid }
        if (localLists.keys.any { it !in remoteListUids }) needPush = true

        val listsNow = dao.allListsForSync()
        val listIdByUid = listsNow.associate { it.uid to it.id }
        val listUidById = listsNow.associate { it.id to it.uid }

        // ---- tasks ----
        val localTasks = dao.allTasksForSync().associateBy { it.uid }
        val remoteTasks = remote?.tasks.orEmpty()
        for (rt in remoteTasks) {
            val r = rt.task.copy(listId = rt.listUid?.let(listIdByUid::get))
            val l = localTasks[r.uid]
            val applied: Task? = when {
                l == null -> if (!r.deleted) r.copy(id = 0).let { it.copy(id = dao.insert(it)) } else null
                r.updatedAt > l.updatedAt -> r.copy(id = l.id).also { dao.update(it) }
                else -> {
                    if (l.updatedAt > r.updatedAt) needPush = true
                    null
                }
            }
            if (applied != null) {
                localChanged = true
                if (applied.done || applied.deleted) {
                    ReminderScheduler.cancel(app, applied.id)
                    Notifications.cancel(app, applied.id)
                } else {
                    ReminderScheduler.schedule(app, applied)
                }
            }
        }
        val remoteTaskUids = remoteTasks.mapTo(HashSet()) { it.task.uid }
        if (localTasks.keys.any { it !in remoteTaskUids }) needPush = true

        // ---- note categories ----
        val noteDao = app.database.noteDao()
        noteDao.purgeNotes(purgeBefore)
        noteDao.purgeCategories(purgeBefore)
        val localCats = noteDao.allCategoriesForSync().associateBy { it.uid }
        val remoteCats = remote?.categories.orEmpty()
        for (r in remoteCats) {
            val l = localCats[r.uid]
            when {
                l == null -> if (!r.deleted) {
                    noteDao.insertCategory(r.copy(id = 0))
                    localChanged = true
                }
                r.updatedAt > l.updatedAt -> {
                    noteDao.updateCategory(r.copy(id = l.id))
                    localChanged = true
                }
                l.updatedAt > r.updatedAt -> needPush = true
            }
        }
        val remoteCatUids = remoteCats.mapTo(HashSet()) { it.uid }
        if (localCats.keys.any { it !in remoteCatUids }) needPush = true

        // ---- notes ----
        val localNotes = noteDao.allNotesForSync().associateBy { it.uid }
        val remoteNotes = remote?.notes.orEmpty()
        for (r in remoteNotes) {
            val l = localNotes[r.uid]
            when {
                l == null -> if (!r.deleted) {
                    noteDao.insert(r.copy(id = 0))
                    localChanged = true
                }
                r.updatedAt > l.updatedAt -> {
                    noteDao.update(r.copy(id = l.id))
                    localChanged = true
                }
                l.updatedAt > r.updatedAt -> needPush = true
            }
        }
        val remoteNoteUids = remoteNotes.mapTo(HashSet()) { it.uid }
        if (localNotes.keys.any { it !in remoteNoteUids }) needPush = true

        // ---- notes PIN ----
        val rl = remote?.lock
        if (rl != null && rl.updatedAt > prefs.lockUpdatedAt) {
            prefs.lockSalt = rl.salt
            prefs.lockHash = rl.hash
            prefs.lockUpdatedAt = rl.updatedAt
            localChanged = true
        } else if (prefs.lockConfigured && prefs.lockUpdatedAt > (rl?.updatedAt ?: 0L)) {
            needPush = true
        }

        if (needPush) {
            val lock = if (prefs.lockConfigured) {
                RemoteLock(prefs.lockSalt!!, prefs.lockHash!!, prefs.lockUpdatedAt)
            } else null
            val data = RemoteData(
                tasks = dao.allTasksForSync().map { RemoteTask(it, it.listId?.let(listUidById::get)) },
                lists = dao.allListsForSync(),
                notes = noteDao.allNotesForSync(),
                categories = noteDao.allCategoriesForSync(),
                lock = lock,
            )
            if (!push(data)) return false
        }
        syncImages(noteDao.allNotesForSync())
        if (localChanged) app.onRemoteChange()
        return true
    }

    /** Uploads new note images and downloads ones added on the laptop. Failures retry next sync. */
    private fun syncImages(notes: List<Note>) {
        val wanted = notes.filter { !it.deleted }.flatMap { it.imageList }.toSet()
        val uploaded = prefs.uploadedImages.toMutableSet()
        var downloaded = false
        for (uid in wanted) {
            val file = ImageStore.file(app, uid)
            runCatching {
                if (!file.exists()) {
                    val bytes = httpBytes("https://api.github.com/repos/${prefs.syncRepo}/contents/images/$uid.jpg")
                    if (bytes != null) {
                        // Write then rename, so a cut-off download is never mistaken for a full image.
                        val tmp = java.io.File(file.path + ".part")
                        tmp.writeBytes(bytes)
                        if (!tmp.renameTo(file)) { tmp.delete(); throw java.io.IOException("rename failed") }
                        uploaded += uid
                        downloaded = true
                    }
                } else if (uid !in uploaded) {
                    val body = JSONObject()
                        .put("message", "Add image")
                        .put("content", Base64.encodeToString(file.readBytes(), Base64.NO_WRAP))
                    val url = "https://api.github.com/repos/${prefs.syncRepo}/contents/images/$uid.jpg"
                    val res = http("PUT", url, body = body.toString())
                    if (res.code in 200..299 || res.code == 422) uploaded += uid
                }
            }
        }
        prefs.uploadedImages = uploaded
        if (downloaded) ImageStore.version.value++
    }

    private fun httpBytes(url: String): ByteArray? {
        val c = URL(url).openConnection() as HttpURLConnection
        try {
            c.connectTimeout = 15_000
            c.readTimeout = 30_000
            c.setRequestProperty("Authorization", "Bearer ${prefs.syncToken}")
            c.setRequestProperty("Accept", "application/vnd.github.raw+json")
            c.setRequestProperty("X-GitHub-Api-Version", "2022-11-28")
            return if (c.responseCode == 200) c.inputStream.use { it.readBytes() } else null
        } finally {
            c.disconnect()
        }
    }

    // ---------- GitHub API ----------

    private fun fileUrl() = "https://api.github.com/repos/${prefs.syncRepo}/contents/$FILE"

    /** null means the file doesn't exist yet. */
    private fun fetchRemote(): RemoteData? {
        val res = http("GET", fileUrl(), etag = remoteEtag.takeIf { remoteCache != null })
        return when (res.code) {
            304 -> remoteCache
            200 -> {
                val json = JSONObject(res.body)
                val encoded = json.optString("content")
                // Over 1 MB the API omits the content; fetch the raw file instead.
                val content = if (encoded.isNotEmpty() && json.optString("encoding") != "none") {
                    String(Base64.decode(encoded, Base64.DEFAULT), Charsets.UTF_8)
                } else {
                    httpBytes(fileUrl())?.toString(Charsets.UTF_8) ?: throw SyncException("Couldn't download nowish.json")
                }
                remoteSha = json.getString("sha")
                remoteEtag = res.etag
                parse(content).also { remoteCache = it }
            }
            404 -> {
                remoteSha = null
                remoteCache = null
                null
            }
            401, 403 -> throw SyncException("Token rejected — check it has access to ${prefs.syncRepo}")
            else -> throw SyncException("GitHub error ${res.code}")
        }
    }

    private fun push(data: RemoteData): Boolean {
        val content = serialize(data)
        val body = JSONObject()
            .put("message", "Sync from phone")
            .put("content", Base64.encodeToString(content.toByteArray(Charsets.UTF_8), Base64.NO_WRAP))
        remoteSha?.let { body.put("sha", it) }
        val res = http("PUT", fileUrl(), body = body.toString())
        return when (res.code) {
            200, 201 -> {
                remoteSha = JSONObject(res.body).getJSONObject("content").getString("sha")
                remoteEtag = null
                remoteCache = data
                true
            }
            409, 422 -> false
            404 -> throw SyncException("Can't find repo ${prefs.syncRepo}")
            401, 403 -> throw SyncException("Token can't write to ${prefs.syncRepo}")
            else -> throw SyncException("GitHub error ${res.code}")
        }
    }

    private class Http(val code: Int, val body: String, val etag: String?)

    private fun http(method: String, url: String, body: String? = null, etag: String? = null): Http {
        val c = URL(url).openConnection() as HttpURLConnection
        try {
            c.requestMethod = method
            c.connectTimeout = 15_000
            c.readTimeout = 20_000
            c.useCaches = false
            c.setRequestProperty("Authorization", "Bearer ${prefs.syncToken}")
            c.setRequestProperty("Accept", "application/vnd.github+json")
            c.setRequestProperty("X-GitHub-Api-Version", "2022-11-28")
            if (etag != null) c.setRequestProperty("If-None-Match", etag)
            if (body != null) {
                c.doOutput = true
                c.setRequestProperty("Content-Type", "application/json")
                c.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
            }
            val code = c.responseCode
            val stream = if (code < 400) runCatching { c.inputStream }.getOrNull() else c.errorStream
            val text = stream?.bufferedReader()?.use { it.readText() } ?: ""
            return Http(code, text, c.getHeaderField("ETag"))
        } finally {
            c.disconnect()
        }
    }

    // ---------- JSON (shared format with the web app) ----------

    private class RemoteTask(val task: Task, val listUid: String?)
    private class RemoteLock(val salt: String, val hash: String, val updatedAt: Long)
    private class RemoteData(
        val tasks: List<RemoteTask>,
        val lists: List<TaskList>,
        val notes: List<Note>,
        val categories: List<Category>,
        val lock: RemoteLock?,
    )

    private fun parse(text: String): RemoteData {
        val root = JSONObject(text)
        val lists = root.optJSONArray("lists").objects().map { o ->
            TaskList(
                uid = o.getString("id"),
                name = o.optString("name"),
                emoji = o.optString("emoji", "📝"),
                space = space(o.optString("space")),
                createdAt = o.optLong("createdAt"),
                updatedAt = o.optLong("updatedAt"),
                deleted = o.optBoolean("deleted"),
            )
        }
        val tasks = root.optJSONArray("tasks").objects().map { o ->
            RemoteTask(
                Task(
                    uid = o.getString("id"),
                    title = o.optString("title"),
                    space = space(o.optString("space")),
                    dueDate = o.optStringOrNull("due")?.let { runCatching { LocalDate.parse(it) }.getOrNull() },
                    sticky = o.optBoolean("sticky", true),
                    reminderTime = o.optStringOrNull("time")?.let { runCatching { LocalTime.parse(it) }.getOrNull() },
                    done = o.optBoolean("done"),
                    completedAt = if (o.isNull("completedAt")) null else o.optLong("completedAt"),
                    createdAt = o.optLong("createdAt"),
                    updatedAt = o.optLong("updatedAt"),
                    deleted = o.optBoolean("deleted"),
                ),
                o.optStringOrNull("list"),
            )
        }
        val categories = root.optJSONArray("categories").objects().map { o ->
            Category(
                uid = o.getString("id"),
                name = o.optString("name"),
                emoji = o.optString("emoji", "📁"),
                color = o.optString("color", "default"),
                createdAt = o.optLong("createdAt"),
                updatedAt = o.optLong("updatedAt"),
                deleted = o.optBoolean("deleted"),
            )
        }
        val notes = root.optJSONArray("notes").objects().map { o ->
            Note(
                uid = o.getString("id"),
                title = o.optString("title"),
                content = o.optString("content"),
                checklist = o.optJSONArray("checklist")?.toString(),
                color = o.optString("color", "default"),
                theme = o.optStringOrNull("theme"),
                categoryUid = o.optStringOrNull("category"),
                pinned = o.optBoolean("pinned"),
                locked = o.optBoolean("locked"),
                images = o.optJSONArray("images")?.toString() ?: "[]",
                trashedAt = if (o.isNull("trashedAt")) null else o.optLong("trashedAt"),
                createdAt = o.optLong("createdAt"),
                updatedAt = o.optLong("updatedAt"),
                deleted = o.optBoolean("deleted"),
            )
        }
        val lock = root.optJSONObject("notesLock")?.let {
            RemoteLock(it.optString("salt"), it.optString("hash"), it.optLong("updatedAt"))
        }?.takeIf { it.salt.isNotEmpty() && it.hash.isNotEmpty() }
        return RemoteData(tasks, lists, notes, categories, lock)
    }

    private fun serialize(data: RemoteData): String {
        val lists = JSONArray()
        data.lists.sortedBy { it.createdAt }.forEach { l ->
            lists.put(
                JSONObject()
                    .put("id", l.uid)
                    .put("name", l.name)
                    .put("emoji", l.emoji)
                    .put("space", l.space.name)
                    .put("createdAt", l.createdAt)
                    .put("updatedAt", l.updatedAt)
                    .put("deleted", l.deleted)
            )
        }
        val tasks = JSONArray()
        data.tasks.sortedBy { it.task.createdAt }.forEach { rt ->
            val t = rt.task
            tasks.put(
                JSONObject()
                    .put("id", t.uid)
                    .put("title", t.title)
                    .put("space", t.space.name)
                    .put("list", rt.listUid ?: JSONObject.NULL)
                    .put("due", t.dueDate?.toString() ?: JSONObject.NULL)
                    .put("sticky", t.sticky)
                    .put("time", t.reminderTime?.format(HHMM) ?: JSONObject.NULL)
                    .put("done", t.done)
                    .put("completedAt", t.completedAt ?: JSONObject.NULL)
                    .put("createdAt", t.createdAt)
                    .put("updatedAt", t.updatedAt)
                    .put("deleted", t.deleted)
            )
        }
        val categories = JSONArray()
        data.categories.sortedBy { it.createdAt }.forEach { c ->
            categories.put(
                JSONObject()
                    .put("id", c.uid)
                    .put("name", c.name)
                    .put("emoji", c.emoji)
                    .put("color", c.color)
                    .put("createdAt", c.createdAt)
                    .put("updatedAt", c.updatedAt)
                    .put("deleted", c.deleted)
            )
        }
        val notes = JSONArray()
        data.notes.sortedBy { it.createdAt }.forEach { n ->
            notes.put(
                JSONObject()
                    .put("id", n.uid)
                    .put("title", n.title)
                    .put("content", n.content)
                    .put("checklist", n.checklist?.let { runCatching { JSONArray(it) }.getOrNull() } ?: JSONObject.NULL)
                    .put("color", n.color)
                    .put("theme", n.theme ?: JSONObject.NULL)
                    .put("category", n.categoryUid ?: JSONObject.NULL)
                    .put("pinned", n.pinned)
                    .put("locked", n.locked)
                    .put("images", runCatching { JSONArray(n.images) }.getOrDefault(JSONArray()))
                    .put("trashedAt", n.trashedAt ?: JSONObject.NULL)
                    .put("createdAt", n.createdAt)
                    .put("updatedAt", n.updatedAt)
                    .put("deleted", n.deleted)
            )
        }
        val lock: Any = data.lock?.let {
            JSONObject().put("salt", it.salt).put("hash", it.hash).put("iterations", 120_000).put("updatedAt", it.updatedAt)
        } ?: JSONObject.NULL
        return JSONObject()
            .put("schema", 2)
            .put("categories", categories)
            .put("notes", notes)
            .put("notesLock", lock)
            .put("updatedAt", System.currentTimeMillis())
            .put("lists", lists)
            .put("tasks", tasks)
            .toString()
    }

    private fun space(s: String) = runCatching { Space.valueOf(s) }.getOrDefault(Space.PERSONAL)

    private fun JSONArray?.objects(): List<JSONObject> =
        if (this == null) emptyList() else (0 until length()).mapNotNull { optJSONObject(it) }

    private fun JSONObject.optStringOrNull(key: String): String? =
        if (isNull(key)) null else optString(key).ifEmpty { null }

    private class SyncException(message: String) : Exception(message)

    companion object {
        private const val FILE = "nowish.json"
        private const val TOMBSTONE_TTL = 30L * 24 * 60 * 60 * 1000
        private val HHMM = DateTimeFormatter.ofPattern("HH:mm")
    }
}
