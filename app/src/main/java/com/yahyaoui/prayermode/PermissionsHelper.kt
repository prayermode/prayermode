package com.yahyaoui.prayermode

import android.Manifest
import android.app.AlarmManager
import android.app.AlertDialog
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.util.Log
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.core.net.toUri
import androidx.core.text.HtmlCompat
import com.google.android.material.snackbar.Snackbar
private const val tag = "PermissionHelper"
class PermissionsHelper(private val context: Context) {
    fun checkLocationPermission(): Boolean {
        return ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED || ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED
    }
    fun checkNotificationPermission(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
        } else true
    }
    fun checkAlarmPermission(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val alarmManager = ActivityCompat.getSystemService(context, AlarmManager::class.java)
            alarmManager?.canScheduleExactAlarms() == true
        } else true
    }
    fun checkDNDPermission(context: Context): Boolean {
        val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        return notificationManager.isNotificationPolicyAccessGranted
    }
    fun checkBackgroundLocationPermission(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_BACKGROUND_LOCATION) == PackageManager.PERMISSION_GRANTED
        } else true
    }
    fun requestDNDPermission(activity: MainActivity) {
        val notificationManager = activity.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (!notificationManager.isNotificationPolicyAccessGranted) {
            AlertDialog.Builder(activity)
                .setTitle(R.string.dnd_permission_title)
                .setMessage(R.string.dnd_permission_message)
                .setCancelable(false)
                .setPositiveButton(R.string.open_settings) { _, _ ->
                    try {
                        val intent = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                            Intent("android.settings.NOTIFICATION_POLICY_ACCESS_DETAIL_SETTINGS").apply {
                                data = "package:${activity.packageName}".toUri()
                                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                            }
                        } else {
                            Intent(Settings.ACTION_NOTIFICATION_POLICY_ACCESS_SETTINGS).apply {
                                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                            }
                        }
                        activity.startActivity(intent)
                    } catch (_: Exception) {
                        Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                            data = Uri.fromParts("package", activity.packageName, null)
                            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                            activity.startActivity(this)
                        }
                    }
                }
                .setNegativeButton(R.string.cancel) { _, _ ->
                    activity.runOnUiThread { activity.switchStateOff(R.string.grant_dnd_permission) }
                }
                .setOnDismissListener {
                    if (!notificationManager.isNotificationPolicyAccessGranted) {
                        activity.runOnUiThread { activity.switchStateOff(R.string.grant_dnd_permission) }
                    }
                }
                .show()
        }
    }
    fun requestAlarmPermission(activity: MainActivity) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) {
            Snackbar.make(activity.findViewById(android.R.id.content), context.getString(R.string.alarm_permission_granted), Snackbar.LENGTH_SHORT).show()
            return
        }
        val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        if (!alarmManager.canScheduleExactAlarms()) {
            AlertDialog.Builder(activity)
                .setTitle(R.string.alarm_permission_title)
                .setMessage(R.string.alarm_permission_message)
                .setCancelable(false)
                .setPositiveButton(R.string.open_settings) { _, _ ->
                    try {
                        val intent = Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM).apply {
                            data = "package:${activity.packageName}".toUri()
                            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        }
                        activity.startActivity(intent)
                    } catch (_: Exception) {
                        Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                            data = Uri.fromParts("package", activity.packageName, null)
                            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                            activity.startActivity(this)
                        }
                    }
                }
                .setNegativeButton(R.string.cancel) { _, _ ->
                    activity.runOnUiThread { activity.switchStateOff(R.string.alarm_permission_denied_message) }
                }
                .setOnDismissListener {
                    if (!alarmManager.canScheduleExactAlarms()) {
                        activity.runOnUiThread { activity.switchStateOff(R.string.alarm_permission_denied_message) }
                    }
                }
                .show()
        } else Snackbar.make(activity.findViewById(android.R.id.content), context.getString(R.string.alarm_permission_granted), Snackbar.LENGTH_SHORT).show()
    }
    fun requestBackgroundLocationPermission(activity: MainActivity) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return
        logDisclosureShown()
        AlertDialog.Builder(activity)
            .setTitle(R.string.background_location_permission_title)
            .setMessage(HtmlCompat.fromHtml(activity.getString(R.string.background_location_permission_message), HtmlCompat.FROM_HTML_MODE_LEGACY))
            .setCancelable(false)
            .setPositiveButton(R.string.ok) { dialog, _ ->
                logDisclosureAccepted()
                try {
                    val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                        data = Uri.fromParts("package", activity.packageName, null)
                        putExtra("app", activity.packageName)
                        putExtra("fromPermissionSettings", true)
                    }
                    activity.startActivity(intent)
                } catch (e: Exception) {
                    Log.e(tag, "Failed to open specific location settings, falling back to general app settings: ${e.message}")
                    val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                        data = Uri.fromParts("package", activity.packageName, null)
                    }
                    activity.startActivity(intent)
                }
                dialog.dismiss()
            }
            .setNegativeButton(R.string.cancel) { dialog, _ ->
                logDisclosureDenied()
                dialog.dismiss()
                activity.runOnUiThread { activity.switchStateOff(R.string.background_location_denied_message) }
            }
            .show()
    }
    fun areLocNotifDNDGranted(): Boolean {
        return checkLocationPermission() && checkNotificationPermission() && checkDNDPermission(context)
    }
    fun areLocNotifDNDAlarmGranted(): Boolean {
        return checkLocationPermission() && checkNotificationPermission() && checkDNDPermission(context) && checkAlarmPermission()
    }
    fun areLocDNDAlarmBackgroundLocGranted(): Boolean {
        return checkLocationPermission() && checkDNDPermission(context) && checkAlarmPermission() && checkBackgroundLocationPermission()
    }
    fun areAllPermissionsGranted(): Boolean {
        return checkLocationPermission() && checkNotificationPermission() && checkDNDPermission(context) && checkAlarmPermission() && checkBackgroundLocationPermission()
    }
    private fun logDisclosureShown() {
        if (BuildConfig.DEBUG) Log.d(tag, "Background location disclosure shown")
    }
    private fun logDisclosureAccepted() {
        if (BuildConfig.DEBUG) Log.d(tag, "Background location disclosure accepted")
    }
    private fun logDisclosureDenied() {
        if (BuildConfig.DEBUG) Log.d(tag, "Background location disclosure denied")
    }
}