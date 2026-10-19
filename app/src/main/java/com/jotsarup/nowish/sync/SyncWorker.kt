package com.jotsarup.nowish.sync

import android.content.Context
import androidx.glance.appwidget.updateAll
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.jotsarup.nowish.nowish
import com.jotsarup.nowish.widget.NowishWidget

/** Every ~15 min: pull laptop changes and refresh widgets (so "Today" stays right past midnight). */
class SyncWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val app = applicationContext.nowish
        runCatching { app.sync.sync() }
        runCatching { NowishWidget().updateAll(applicationContext) }
        return Result.success()
    }
}
