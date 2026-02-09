package com.yahyaoui.prayermode

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.workDataOf
import java.util.concurrent.TimeUnit
class BootReceiver : BroadcastReceiver() {
    private val notificationId = 1001
    private val tag = "BootReceiver"
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_BOOT_COMPLETED) {
            if (BuildConfig.DEBUG) Log.d(tag, "Device rebooted. BootReceiver triggered.")

            val applicationContext = context.applicationContext
            val permissionsHelper = PermissionsHelper(applicationContext)
            val sharedHelper = SharedHelper(applicationContext)
            val wasAppControlledDnd = sharedHelper.getBoolean(SharedHelper.IS_APP_CONTROLLED_DND_ACTIVE, false)

            if (wasAppControlledDnd) {
                if (BuildConfig.DEBUG) Log.w("BootReceiver", "App-controlled DND was active before reboot - cleaning up")
                Tools(context).exitSilentMode()
            }

            val isAppReadyToSchedule = sharedHelper.getSwitchState() && permissionsHelper.areLocDNDAlarmBackgroundLocGranted()
            if (isAppReadyToSchedule) {
                if (BuildConfig.DEBUG) Log.d(tag, "Switch is on, location, alarm and DND permissions are granted. Initiating work.")

                val dailyWorkRequest = OneTimeWorkRequestBuilder<SilentModeWorker>()
                    .setInputData(workDataOf("prayerName" to "DailyWorker"))
                    .setInitialDelay(10, TimeUnit.SECONDS)
                    .addTag("DailyPrayerScheduleBoot")
                    .build()

                WorkManager.getInstance(applicationContext).enqueueUniqueWork("BootInitWork", ExistingWorkPolicy.REPLACE, dailyWorkRequest)
            } else {
                if (BuildConfig.DEBUG) Log.d(tag, "Switch is off or location, alarm and DND permissions are not granted.")
            }
            sendNotification(context)
        }
    }
    private fun sendNotification(context: Context) {
        val intent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
            putExtra("TOGGLE_SWITCH_TWICE", true)
        }

        val pendingIntent = PendingIntent.getActivity(context, 0, intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)

        NotificationHelper.sendNotificationWithAction(
            context = context,
            titleResId = R.string.location_title,
            messageResId = R.string.app_disabled_after_reboot,
            notificationId = notificationId,
            channelId = "location_switch_channel",
            pendingIntent = pendingIntent,
            actionTitleResId = R.string.open_prayer,
            actionPendingIntent = pendingIntent,
            autoCancel = true
        )
    }
}