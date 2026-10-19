package com.jotsarup.nowish

import android.app.Application
import android.content.Context
import androidx.glance.appwidget.updateAll
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import com.jotsarup.nowish.data.AppDatabase
import com.jotsarup.nowish.data.NotesRepository
import com.jotsarup.nowish.data.Prefs
import com.jotsarup.nowish.data.TaskRepository
import com.jotsarup.nowish.reminders.Notifications
import com.jotsarup.nowish.sync.SyncManager
import com.jotsarup.nowish.sync.SyncWorker
import com.jotsarup.nowish.ui.SoundFx
import com.jotsarup.nowish.widget.NowishWidget
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.util.concurrent.TimeUnit

class NowishApp : Application() {
    val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    val database by lazy { AppDatabase.build(this) }
    val repository by lazy { TaskRepository(this, database.dao(), ::onLocalChange) }
    val notes by lazy { NotesRepository(this, database.noteDao(), prefs) { sync.requestSync() } }
    val prefs by lazy { Prefs(this) }
    val sounds by lazy { SoundFx(this, prefs) }
    val sync by lazy { SyncManager(this) }

    override fun onCreate() {
        super.onCreate()
        Notifications.createChannel(this)
        WorkManager.getInstance(this).enqueueUniquePeriodicWork(
            "sync",
            ExistingPeriodicWorkPolicy.KEEP,
            PeriodicWorkRequestBuilder<SyncWorker>(15, TimeUnit.MINUTES).build(),
        )
        appScope.launch { runCatching { notes.purgeExpiredTrash() } }
    }

    private fun onLocalChange() {
        sync.requestSync()
        refreshWidgets()
    }

    /** Called by sync after it pulled changes from the laptop. */
    fun onRemoteChange() = refreshWidgets()

    fun refreshWidgets() {
        appScope.launch { runCatching { NowishWidget().updateAll(this@NowishApp) } }
    }
}

val Context.nowish: NowishApp get() = applicationContext as NowishApp
