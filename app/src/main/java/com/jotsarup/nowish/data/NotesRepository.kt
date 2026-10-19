package com.jotsarup.nowish.data

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageDecoder
import android.net.Uri
import android.os.Build
import android.util.Base64
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.security.SecureRandom
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.PBEKeySpec

class NotesRepository(
    private val context: Context,
    private val dao: NoteDao,
    private val prefs: Prefs,
    private val onChanged: () -> Unit,
) {
    fun notes() = dao.notes()
    fun categories() = dao.categories()

    private fun now() = System.currentTimeMillis()

    /** Inserts or updates; returns the saved note (with its id). */
    suspend fun save(note: Note): Note {
        val stamped = note.copy(updatedAt = now())
        val saved = if (note.id == 0L) stamped.copy(id = dao.insert(stamped)) else stamped.also { dao.update(it) }
        onChanged()
        return saved
    }

    suspend fun trash(note: Note) = save(note.copy(trashedAt = now(), pinned = false))
    suspend fun restore(note: Note) = save(note.copy(trashedAt = null))

    suspend fun deleteForever(notes: List<Note>) {
        dao.updateAll(notes.filter { it.id != 0L }.map { it.copy(deleted = true, updatedAt = now()) })
        onChanged()
    }

    /** Trash empties itself after 30 days. */
    suspend fun purgeExpiredTrash() {
        val expired = dao.expiredTrash(now() - TRASH_DAYS * DAY)
        if (expired.isNotEmpty()) deleteForever(expired)
    }

    suspend fun addCategory(name: String, emoji: String, color: String): Category {
        val c = Category(name = name.trim(), emoji = emoji, color = color)
        val saved = c.copy(id = dao.insertCategory(c))
        onChanged()
        return saved
    }

    suspend fun saveCategory(category: Category) {
        dao.updateCategory(category.copy(updatedAt = now()))
        onChanged()
    }

    suspend fun deleteCategory(category: Category) {
        dao.updateAll(dao.notesInCategory(category.uid).map { it.copy(categoryUid = null, updatedAt = now()) })
        dao.updateCategory(category.copy(deleted = true, updatedAt = now()))
        onChanged()
    }

    // ---------- lock ----------

    fun setPin(pin: String) {
        val salt = ByteArray(16).also { SecureRandom().nextBytes(it) }
        prefs.lockSalt = Base64.encodeToString(salt, Base64.NO_WRAP)
        prefs.lockHash = hashPin(pin, salt)
        prefs.lockUpdatedAt = now()
        onChanged()
    }

    fun checkPin(pin: String): Boolean {
        val salt = prefs.lockSalt ?: return false
        return hashPin(pin, Base64.decode(salt, Base64.NO_WRAP)) == prefs.lockHash
    }

    companion object {
        const val TRASH_DAYS = 30L
        const val DAY = 24L * 60 * 60 * 1000
        const val PIN_ITERATIONS = 120_000

        /** PBKDF2-SHA256; the web app computes the same thing with WebCrypto. */
        fun hashPin(pin: String, salt: ByteArray): String {
            val spec = PBEKeySpec(pin.toCharArray(), salt, PIN_ITERATIONS, 256)
            val bytes = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).encoded
            return Base64.encodeToString(bytes, Base64.NO_WRAP)
        }
    }
}

/** Note images live in app storage as <uid>.jpg and sync to images/<uid>.jpg in the data repo. */
object ImageStore {
    private const val MAX_SIDE = 1600

    fun dir(context: Context) = File(context.filesDir, "note_images").apply { mkdirs() }
    fun file(context: Context, uid: String) = File(dir(context), "$uid.jpg")

    suspend fun import(context: Context, uri: Uri): String? = withContext(Dispatchers.IO) {
        runCatching {
            val bitmap = decode(context, uri)
            val uid = newUid()
            file(context, uid).outputStream().use { bitmap.compress(Bitmap.CompressFormat.JPEG, 85, it) }
            bitmap.recycle()
            uid
        }.getOrNull()
    }

    private fun decode(context: Context, uri: Uri): Bitmap {
        if (Build.VERSION.SDK_INT >= 28) {
            // ImageDecoder also applies the photo's EXIF rotation.
            val source = ImageDecoder.createSource(context.contentResolver, uri)
            return ImageDecoder.decodeBitmap(source) { decoder, info, _ ->
                val w = info.size.width
                val h = info.size.height
                val scale = minOf(1f, MAX_SIDE.toFloat() / maxOf(w, h))
                decoder.setTargetSize((w * scale).toInt().coerceAtLeast(1), (h * scale).toInt().coerceAtLeast(1))
                decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
            }
        }
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        context.contentResolver.openInputStream(uri).use { BitmapFactory.decodeStream(it, null, bounds) }
        var sample = 1
        while (maxOf(bounds.outWidth, bounds.outHeight) / (sample * 2) >= MAX_SIDE) sample *= 2
        val opts = BitmapFactory.Options().apply { inSampleSize = sample }
        return context.contentResolver.openInputStream(uri).use { BitmapFactory.decodeStream(it, null, opts) }
            ?: error("Couldn't read image")
    }
}
