package com.jotsarup.nowish.data

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import androidx.room.TypeConverter
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.util.UUID

enum class Space(val label: String) {
    WORK("Work"),
    PERSONAL("Personal");

    val other: Space get() = if (this == WORK) PERSONAL else WORK
}

fun newUid(): String = UUID.randomUUID().toString()

/**
 * [uid] identifies a task across devices (phone + web). [updatedAt] decides which copy wins
 * when syncing, and [deleted] keeps a tombstone so deletes sync too.
 */
@Entity(
    tableName = "tasks",
    indices = [Index("space"), Index("listId"), Index(value = ["uid"], unique = true)],
)
data class Task(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val title: String,
    val space: Space,
    val listId: Long? = null,
    val dueDate: LocalDate? = null,
    val reminderTime: LocalTime? = null,
    val done: Boolean = false,
    val completedAt: Long? = null,
    val createdAt: Long = System.currentTimeMillis(),
    @ColumnInfo(defaultValue = "") val uid: String = newUid(),
    @ColumnInfo(defaultValue = "0") val updatedAt: Long = System.currentTimeMillis(),
    @ColumnInfo(defaultValue = "0") val deleted: Boolean = false,
) {
    fun reminderAt(): LocalDateTime? =
        if (dueDate != null && reminderTime != null) dueDate.atTime(reminderTime) else null
}

@Entity(
    tableName = "lists",
    indices = [Index("space"), Index(value = ["uid"], unique = true)],
)
data class TaskList(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val emoji: String,
    val space: Space,
    val createdAt: Long = System.currentTimeMillis(),
    @ColumnInfo(defaultValue = "") val uid: String = newUid(),
    @ColumnInfo(defaultValue = "0") val updatedAt: Long = System.currentTimeMillis(),
    @ColumnInfo(defaultValue = "0") val deleted: Boolean = false,
)

class Converters {
    @TypeConverter fun dateToLong(date: LocalDate?): Long? = date?.toEpochDay()
    @TypeConverter fun longToDate(value: Long?): LocalDate? = value?.let(LocalDate::ofEpochDay)
    @TypeConverter fun timeToInt(time: LocalTime?): Int? = time?.toSecondOfDay()
    @TypeConverter fun intToTime(value: Int?): LocalTime? = value?.let { LocalTime.ofSecondOfDay(it.toLong()) }
}
