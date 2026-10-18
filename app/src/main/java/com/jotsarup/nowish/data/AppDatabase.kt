package com.jotsarup.nowish.data

import android.content.Context
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.TypeConverters
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Dao
interface TaskDao {
    @Query("SELECT * FROM tasks WHERE space = :space")
    fun tasks(space: Space): Flow<List<Task>>

    @Query("SELECT * FROM lists WHERE space = :space ORDER BY createdAt")
    fun lists(space: Space): Flow<List<TaskList>>

    @Query("SELECT * FROM tasks WHERE id = :id")
    suspend fun task(id: Long): Task?

    @Query("SELECT * FROM lists WHERE id = :id")
    suspend fun list(id: Long): TaskList?

    @Query("SELECT * FROM tasks WHERE done = 0 AND dueDate IS NOT NULL AND reminderTime IS NOT NULL")
    suspend fun withReminders(): List<Task>

    @Query("SELECT * FROM tasks WHERE listId = :listId")
    suspend fun tasksInList(listId: Long): List<Task>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(task: Task): Long

    @Update
    suspend fun update(task: Task)

    @Delete
    suspend fun delete(task: Task)

    @Delete
    suspend fun deleteAll(tasks: List<Task>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertList(list: TaskList): Long

    @Update
    suspend fun updateList(list: TaskList)

    @Delete
    suspend fun deleteList(list: TaskList)
}

@Database(entities = [Task::class, TaskList::class], version = 1, exportSchema = false)
@TypeConverters(Converters::class)
abstract class AppDatabase : RoomDatabase() {
    abstract fun dao(): TaskDao

    companion object {
        fun build(context: Context): AppDatabase =
            Room.databaseBuilder(context, AppDatabase::class.java, "nowish.db").build()
    }
}
