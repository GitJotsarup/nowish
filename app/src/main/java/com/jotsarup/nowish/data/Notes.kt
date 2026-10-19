package com.jotsarup.nowish.data

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import org.json.JSONArray
import org.json.JSONObject

/**
 * A note. [content] is HTML (bold, lists, headings…) so the phone and web editors share it.
 * When [checklist] is non-null the note is a checklist instead of free text.
 * [trashedAt] puts it in the trash; [deleted] is the sync tombstone after it's gone for good.
 */
@Entity(tableName = "notes", indices = [Index(value = ["uid"], unique = true)])
data class Note(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val uid: String = newUid(),
    val title: String = "",
    val content: String = "",
    val checklist: String? = null,
    val color: String = "default",
    val theme: String? = null,
    val categoryUid: String? = null,
    val pinned: Boolean = false,
    val locked: Boolean = false,
    val images: String = "[]",
    val trashedAt: Long? = null,
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis(),
    val deleted: Boolean = false,
) {
    val imageList: List<String> get() = runCatching {
        JSONArray(images).let { a -> List(a.length()) { a.getString(it) } }
    }.getOrDefault(emptyList())

    val items: List<ChecklistItem>? get() = checklist?.let(ChecklistItem::parse)

    val plainText: String get() = htmlToText(content)

    val isBlank: Boolean
        get() = title.isBlank() && plainText.isBlank() && imageList.isEmpty() &&
            items.orEmpty().all { it.text.isBlank() }
}

@Entity(tableName = "categories", indices = [Index(value = ["uid"], unique = true)])
data class Category(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val uid: String = newUid(),
    val name: String,
    val emoji: String,
    val color: String = "default",
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis(),
    val deleted: Boolean = false,
)

data class ChecklistItem(val id: String = newUid(), val text: String = "", val done: Boolean = false) {
    companion object {
        fun parse(json: String): List<ChecklistItem> = runCatching {
            val a = JSONArray(json)
            List(a.length()) {
                val o = a.getJSONObject(it)
                ChecklistItem(o.optString("id", newUid()), o.optString("text"), o.optBoolean("done"))
            }
        }.getOrDefault(emptyList())

        fun toJson(items: List<ChecklistItem>): String = JSONArray().apply {
            items.forEach { put(JSONObject().put("id", it.id).put("text", it.text).put("done", it.done)) }
        }.toString()
    }
}

fun imagesJson(uids: List<String>): String = JSONArray(uids).toString()

/** Good-enough HTML → text for card previews and search. */
fun htmlToText(html: String): String = html
    .replace(Regex("(?i)<br\\s*/?>|</p>|</li>|</h[1-6]>|</div>"), "\n")
    .replace(Regex("<[^>]+>"), "")
    .replace("&nbsp;", " ").replace("&amp;", "&").replace("&lt;", "<").replace("&gt;", ">")
    .replace("&quot;", "\"").replace("&#39;", "'")
    .replace(Regex("\n{3,}"), "\n\n")
    .trim()
