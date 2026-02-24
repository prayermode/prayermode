package com.yahyaoui.prayermode

import android.content.Context
import android.util.Log
import androidx.work.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import androidx.work.WorkerParameters
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale
class SilentModeWorker(appContext: Context, workerParams: WorkerParameters) : CoroutineWorker(appContext, workerParams) {
    private val sharedHelper: SharedHelper by lazy { SharedHelper(appContext) }
    private val tools: Tools by lazy { Tools(appContext) }
    private val tag = "SilentModeWorker"
    private val audioPlayerHelper: AudioPlayerHelper by lazy { AudioPlayerHelper(appContext) }

    override suspend fun doWork(): Result {
        val mode = inputData.getBoolean("mode", false)
        val prayerName = inputData.getString("prayerName") ?: "unknown"
        val isBackup = inputData.getBoolean("isBackup", false)
        val isImmediate = inputData.getBoolean("isImmediate", false)
        val ablutionEnabled = sharedHelper.getAblutionSwitchState()
        val isAblutionBefore = inputData.getBoolean("isAblutionBefore", false)

        if (BuildConfig.DEBUG) {
            if (isBackup) Log.w(tag, "BACKUP worker executing for $prayerName: mode=$mode")
            else if (isImmediate) Log.w(tag, "IMMEDIATE worker executing for $prayerName: mode=$mode")
            else Log.d(tag, "Executing worker for $prayerName: mode=$mode")
        }
        val shouldPlayAudio = when {
            prayerName == "jumua" -> {
                val beforeJumuaInt = sharedHelper.getIntValue(SharedHelper.DURATION_BEFORE_JUMUA, 0)
                if (BuildConfig.DEBUG) Log.i(tag, "Jumua: beforeDhuhr index=$beforeJumuaInt, ablution=$ablutionEnabled")
                beforeJumuaInt == 0 && !ablutionEnabled
            }
            prayerName == "Tahajjud" -> {
                val tahajjudInt = sharedHelper.getIntValue(SharedHelper.DURATION_TAHAJJUD, 0)
                if (BuildConfig.DEBUG) Log.i(tag, "Tahajjud: tahajjud index=$tahajjudInt, ablution=$ablutionEnabled")
                tahajjudInt == 0 && !ablutionEnabled
            }
            prayerName == "Eid" -> {
                if (BuildConfig.DEBUG) Log.i(tag, "Eid prayer: skipping takbir playback (special prayer)")
                false
            }
            ablutionEnabled && prayerName in listOf("Fajr", "Dhuhr", "Asr", "Maghrib", "Isha", "Taraweeh") -> {
                if (BuildConfig.DEBUG) Log.i(tag, "$prayerName: ablution enabled, skipping takbir")
                false
            }
            else -> true
        }

        return try {
            withContext(Dispatchers.IO) {
                if (!sharedHelper.getSwitchState()) {
                    if (BuildConfig.DEBUG) Log.d(tag, "Switch is off, canceling worker")
                    return@withContext Result.success()
                }

                if (prayerName == "DailyWorker") {
                    val calendar = Calendar.getInstance()
                    val gregorianDay = calendar.get(Calendar.DAY_OF_MONTH)
                    val monthFormat = SimpleDateFormat("MMMM", Locale.US)
                    val gregorianMonthName = monthFormat.format(calendar.time)
                    val selectedMethodIndex = sharedHelper.getIntValue(SharedHelper.SELECTED_METHOD_RES_ID, 0)
                    if (BuildConfig.DEBUG) Log.i(tag, "Daily worker processing, gregorian day is $gregorianDay")

                    if (gregorianDay == 1  || !tools.checkIfDataAvailable()) {
                        if (selectedMethodIndex != -1) {
                            if (BuildConfig.DEBUG) {
                                if (gregorianDay == 1) {
                                    Log.i(tag, "Updating for prayer times for $gregorianMonthName, using method $selectedMethodIndex.")
                                    NotificationHelper.sendNotification(applicationContext, R.string.fetch_title, R.string.monthly_update, 200, gregorianMonthName)
                                }
                                else Log.i(tag, "Data is obsolete.")
                            }
                            tools.findLocation(selectedMethodIndex)
                            AlarmScheduler(applicationContext).scheduleDailyAlarm()
                        } else {
                            if (BuildConfig.DEBUG) {
                                Log.i(tag, "Fetching for $gregorianMonthName prayer times, using default method.")
                                NotificationHelper.sendNotification(applicationContext, R.string.fetch_title, R.string.monthly_update_default_method, 200, gregorianMonthName)
                            }
                            tools.findLocation(4)
                            AlarmScheduler(applicationContext).scheduleDailyAlarm()
                        }
                    } else {
                        tools.processPrayerTimes()
                        AlarmScheduler(applicationContext).scheduleDailyAlarm()
                    }
                    return@withContext Result.success()
                } else {
                    val silentModeSetSuccess = if (mode) {
                        if (sharedHelper.getAudioSwitchState() && sharedHelper.getSwitchState() && shouldPlayAudio && !tools.isInCall() && !isImmediate) {
                            if (BuildConfig.DEBUG) Log.i(tag, "Main & Audio switches are on, Before Jumua or Tahajjud duration is 0, not in Call, playing audio...")
                            val audioPlayedSuccessfully = audioPlayerHelper.playAudioFromRaw(R.raw.takbir)
                            if (BuildConfig.DEBUG) Log.i(tag, "Audio playback result: $audioPlayedSuccessfully")
                        } else {
                            if (BuildConfig.DEBUG) {
                                val reasons = mutableListOf<String>()
                                if (!sharedHelper.getAudioSwitchState()) reasons.add("audio switch off")
                                if (!sharedHelper.getSwitchState()) reasons.add("main switch off")
                                if (!shouldPlayAudio) reasons.add("shouldPlayAudio false")
                                if (tools.isInCall()) reasons.add("in call")
                                if (isImmediate) reasons.add("immediate activation")
                                Log.i(tag, "Skipping audio for $prayerName: ${reasons.joinToString(", ")}")
                            }
                        }
                        if (BuildConfig.DEBUG) Log.i(tag, "Activating silent mode for $prayerName.")
                        tools.setSilentMode(true, prayerName, isAblutionBefore)
                    } else {
                        if (isBackup) {
                            val isDndActive = sharedHelper.getBoolean(SharedHelper.IS_APP_CONTROLLED_DND_ACTIVE, false)
                            if (isDndActive) {
                                if (BuildConfig.DEBUG) Log.w(tag, "BACKUP triggered: DND still active for $prayerName - forcing deactivation")
                            } else {
                                if (BuildConfig.DEBUG) Log.i(tag, "Backup worker ran but DND already off - all good")
                                return@withContext Result.success()
                            }
                        }
                        if (BuildConfig.DEBUG) Log.i(tag, "Deactivating silent mode for $prayerName.")
                        tools.setSilentMode(false, prayerName, false)
                    }

                    if (!silentModeSetSuccess) {
                        Log.e(tag, "Failed to set silent mode for $prayerName (likely DND permission missing or other issue). Worker failing.")
                        return@withContext Result.failure()
                    }
                    return@withContext Result.success()
                }
            }
        } catch (e: Exception) {
            if (BuildConfig.DEBUG) NotificationHelper.sendNotification(applicationContext, R.string.worker_failed, R.string.worker_failed, 222, "${e.message}")
            Log.e(tag, "Worker failed: ${e.message}.", e)
            return Result.failure()
        }
    }
}