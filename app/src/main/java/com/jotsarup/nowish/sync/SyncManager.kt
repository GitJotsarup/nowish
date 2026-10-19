package com.jotsarup.nowish.sync

import android.util.Base64
import com.jotsarup.nowish.NowishApp
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
    fun requestSync(delayMs: Long = 1500) {
        if (!prefs.syncConfigured) return
        pending?.cancel()
        pending = scope.launch {
            delay(delayMs)
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

    /** Returns false on a write conflict (caller retries). */
    private suspend fun syncOnce(): Boolean {
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

        if (needPush) {
            val data = RemoteData(
                tasks = dao.allTasksForSync().map { RemoteTask(it, it.listId?.let(listUidById::get)) },
                lists = dao.allListsForSync(),
            )
            if (!push(data)) return false
        }
        if (localChanged) app.onRemoteChange()
        return true
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
                val content = String(Base64.decode(json.getString("content"), Base64.DEFAULT), Charsets.UTF_8)
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
    private class RemoteData(val tasks: List<RemoteTask>, val lists: List<TaskList>)

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
        return RemoteData(tasks, lists)
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
                    .put("time", t.reminderTime?.format(HHMM) ?: JSONObject.NULL)
                    .put("done", t.done)
                    .put("completedAt", t.completedAt ?: JSONObject.NULL)
                    .put("createdAt", t.createdAt)
                    .put("updatedAt", t.updatedAt)
                    .put("deleted", t.deleted)
            )
        }
        return JSONObject()
            .put("schema", 1)
            .put("updatedAt", System.currentTimeMillis())
            .put("lists", lists)
            .put("tasks", tasks)
            .toString(1)
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
