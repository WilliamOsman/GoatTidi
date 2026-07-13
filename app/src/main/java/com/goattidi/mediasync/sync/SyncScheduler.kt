package com.goattidi.mediasync.sync

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import dagger.hilt.android.qualifiers.ApplicationContext
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class SyncScheduler @Inject constructor(
    @ApplicationContext private val context: Context
) {
    /** Kicks the queue. Wi-Fi-only is the default per spec; charging-only is opt-in. */
    fun scheduleNow(wifiOnly: Boolean = true, requireCharging: Boolean = false) {
        val constraints = Constraints.Builder()
            .setRequiredNetworkType(if (wifiOnly) NetworkType.UNMETERED else NetworkType.CONNECTED)
            .setRequiresCharging(requireCharging)
            .build()
        val request = OneTimeWorkRequestBuilder<SyncWorker>()
            .setConstraints(constraints)
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
            .build()
        // REPLACE, not APPEND: the worker derives all work from the DB, so replacing
        // loses nothing — and it re-applies current constraints (e.g. after the user
        // turns Wi-Fi-only off, the old UNMETERED-constrained request must not
        // keep blocking the queue).
        WorkManager.getInstance(context)
            .enqueueUniqueWork(UNIQUE_WORK_NAME, ExistingWorkPolicy.REPLACE, request)
    }

    fun cancelAll() {
        WorkManager.getInstance(context).cancelUniqueWork(UNIQUE_WORK_NAME)
    }

    companion object {
        const val UNIQUE_WORK_NAME = "goattidi-sync"
    }
}
