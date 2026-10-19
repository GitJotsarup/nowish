package com.jotsarup.nowish.data

import android.content.Context
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.TypeConverters
import androidx.room.Update
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import kotlinx.coroutines.flow.Flow

@Dao
interface TaskDao {
    @Query("SELECT * FROM tasks WHERE space = :space AND deleted = 0")
    fun tasks(space: Space): Flow<List<Task>>

    @Query("SELECT * FROM lists WHERE space = :space AND deleted = 0 ORDER BY createdAt")
    fun lists(space: Space): Flow<List<TaskList>>

    @Query("SELECT * FROM tasks WHERE deleted = 0 AND done = 0")
    suspend fun openTasks(): List<Task>

    @Query("SELECT * FROM tasks WHERE deleted = 0 AND done = 0")
    fun openTasksFlow(): Flow<List<Task>>

    @Query("SELECT * FROM lists WHERE deleted = 0 ORDER BY createdAt")
    fun allListsFlow(): Flow<List<TaskList>>

    @Query("SELECT * FROM lists WHERE deleted = 0 ORDER BY createdAt")
    suspend fun allLists(): List<TaskList>

    @Query("SELECT * FROM tasks WHERE id = :id")
    suspend fun task(id: Long): Task?

    @Query("SELECT * FROM lists WHERE id = :id")
    suspend fun list(id: Long): TaskList?

    @Query("SELECT * FROM tasks WHERE done = 0 AND deleted = 0 AND dueDate IS NOT NULL AND reminderTime IS NOT NULL")
    suspend fun withReminders(): List<Task>

    @Query("SELECT * FROM tasks WHERE listId = :listId AND deleted = 0")
    suspend fun tasksInList(listId: Long): List<Task>

    // Sync: everything, including tombstones.
    @Query("SELECT * FROM tasks")
    suspend fun allTasksForSync(): List<Task>

    @Query("SELECT * FROM lists")
    suspend fun allListsForSync(): List<TaskList>

    @Query("DELETE FROM tasks WHERE deleted = 1 AND updatedAt < :before")
    suspend fun purgeTasks(before: Long)

    @Query("DELETE FROM lists WHERE deleted = 1 AND updatedAt < :before")
    suspend fun purgeLists(before: Long)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(task: Task): Long

    @Update
    suspend fun update(task: Task)

    @Update
    suspend fun updateAll(tasks: List<Task>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertList(list: TaskList): Long

    @Update
    suspend fun updateList(list: TaskList)
}

private val MIGRATION_1_2 = object : Migration(1, 2) {
    override fun migrate(db: SupportSQLiteDatabase) {
        for (table in listOf("tasks", "lists")) {
            db.execSQL("ALTER TABLE `$table` ADD COLUMN `uid` TEXT NOT NULL DEFAULT ''")
            db.execSQL("ALTER TABLE `$table` ADD COLUMN `updatedAt` INTEGER NOT NULL DEFAULT 0")
            db.execSQL("ALTER TABLE `$table` ADD COLUMN `deleted` INTEGER NOT NULL DEFAULT 0")
            db.execSQL("UPDATE `$table` SET uid = lower(hex(randomblob(16))), updatedAt = createdAt")
            db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS `index_${table}_uid` ON `$table` (`uid`)")
        }
    }
}

@Database(entities = [Task::class, TaskList::class], version = 2, exportSchema = false)
@TypeConverters(Converters::class)
abstract class AppDatabase : RoomDatabase() {
    abstract fun dao(): TaskDao

    companion object {
        fun build(context: Context): AppDatabase =
            Room.databaseBuilder(context, AppDatabase::class.java, "nowish.db")
                .addMigrations(MIGRATION_1_2)
                .build()
    }
}
