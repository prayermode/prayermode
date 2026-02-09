package com.yahyaoui.prayermode

import android.content.Context
import android.util.Log
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters

class UpdateCheckWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

    private val tag = "UpdateCheckWorker"
    private val sharedHelper: SharedHelper by lazy { SharedHelper(applicationContext) }

    override suspend fun doWork(): Result {
        if (BuildConfig.DEBUG) Log.d(tag, "Daily update check started")
        val updateChecker = UpdateChecker(applicationContext)
        try {
            if (!sharedHelper.getSwitchState()) {
                if (BuildConfig.DEBUG) Log.d(tag, "Switch is off, canceling worker")
                return Result.success()
            }
            if (updateChecker.isInstalledFromFdroid()) {
                if (BuildConfig.DEBUG) Log.d(tag, "Skipping update check - installed from F-Droid")
                return Result.success()
            }
            updateChecker.checkForUpdate()
            if (BuildConfig.DEBUG) Log.d(tag, "Daily update check completed")
            return Result.success()
        } catch (e: Exception) {
            Log.e(tag, "Error during daily update check: ${e.message}")
            return Result.retry()
        }
    }
}