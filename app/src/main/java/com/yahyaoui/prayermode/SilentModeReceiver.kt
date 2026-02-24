package com.yahyaoui.prayermode

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.work.Data
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import java.util.concurrent.TimeUnit
class SilentModeReceiver : BroadcastReceiver() {
    private val tag = "SilentModeReceiver"
    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action
        if (BuildConfig.DEBUG) Log.d(tag, "Received intent: ${intent.toUri(Intent.URI_INTENT_SCHEME)}")

        when (action) {
            "START_SILENT_MODE" -> {
                val prayerName = intent.getStringExtra("prayerName") ?: "unknown"
                val isImmediate = intent.getBooleanExtra("isImmediate", false)
                val isAblutionBefore = intent.getBooleanExtra("isAblutionBefore", false)
                handleSilentMode(context, mode = true, prayerName, isImmediate, isAblutionBefore)
            }
            "END_SILENT_MODE" -> {
                val prayerName = intent.getStringExtra("prayerName") ?: "unknown"
                handleSilentMode(context, mode = false, prayerName, isImmediate = false, isAblutionBefore = false)
            }
            "DAILY_WORKER_ALARM" -> {
                if (BuildConfig.DEBUG) Log.d(tag, "Handling DAILY_WORKER_ALARM...")
                scheduleDailyWorker(context)
            }
            else -> {
                Log.e(tag, "Unrecognized action: $action")
            }
        }
    }
    private fun handleSilentMode(context: Context, mode: Boolean, prayerName: String, isImmediate: Boolean, isAblutionBefore: Boolean) {
        val uniqueTag = "SilentModeWorker_${prayerName}_${if (mode) "Start" else "End"}"
        try {
            val inputData = Data.Builder()
                .putBoolean("mode", mode)
                .putString("prayerName", prayerName)
                .putBoolean("isImmediate", isImmediate)
                .putBoolean("isAblutionBefore", isAblutionBefore)
                .build()

            val workRequest = OneTimeWorkRequestBuilder<SilentModeWorker>()
                .setInputData(inputData)
                .setInitialDelay(0, TimeUnit.SECONDS)
                .addTag(uniqueTag)
                .build()
            WorkManager.getInstance(context).enqueueUniqueWork(uniqueTag, ExistingWorkPolicy.REPLACE, workRequest)
            if (BuildConfig.DEBUG) Log.i(tag, "Scheduled SilentModeWorker for $uniqueTag")
        } catch (e: Exception) {
            Log.e(tag, "Failed to set mode for $uniqueTag: ${e.message}",e)
        }
    }
    private fun scheduleDailyWorker(context: Context) {
        val inputData = Data.Builder()
            .putString("prayerName", "DailyWorker")
            .build()

        val workRequest = OneTimeWorkRequestBuilder<SilentModeWorker>()
            .setInputData(inputData)
            .addTag("DailyWorker")
            .build()

        WorkManager.getInstance(context).enqueueUniqueWork("DailyWorker", ExistingWorkPolicy.REPLACE, workRequest)
        if (BuildConfig.DEBUG) Log.i(tag, "Scheduled DailyWorker via WorkManager")
    }
}