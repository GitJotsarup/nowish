package com.jotsarup.nowish

import android.app.Application
import android.content.Context
import com.jotsarup.nowish.data.AppDatabase
import com.jotsarup.nowish.data.Prefs
import com.jotsarup.nowish.data.TaskRepository
import com.jotsarup.nowish.reminders.Notifications
import com.jotsarup.nowish.ui.SoundFx

class NowishApp : Application() {
    val database by lazy { AppDatabase.build(this) }
    val repository by lazy { TaskRepository(this, database.dao()) }
    val prefs by lazy { Prefs(this) }
    val sounds by lazy { SoundFx(this, prefs) }

    override fun onCreate() {
        super.onCreate()
        Notifications.createChannel(this)
    }
}

val Context.nowish: NowishApp get() = applicationContext as NowishApp
