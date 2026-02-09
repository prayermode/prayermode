package com.yahyaoui.prayermode

import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.core.content.pm.PackageInfoCompat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.URL
import androidx.core.net.toUri
import android.app.*
import android.content.pm.InstallSourceInfo
import android.os.Build
data class AppVersion(
    val versionCode: Int,
    val versionName: String,
    val apkUrl: String,
    val minRequired: Boolean = false
)
object NotificationIds {
    const val UPDATE_AVAILABLE = 1
}
class UpdateChecker(private val context: Context) {
    private companion object {
        const val VERSION_JSON_URL = "https://prayermode.github.io/apk/version.json"
    }
    private val tag = "UpdateChecker"
    private suspend fun getLatestVersion(): AppVersion? {
        return try {
            val jsonString = withContext(Dispatchers.IO) {
                URL(VERSION_JSON_URL).readText()
            }
            val jsonObject = JSONObject(jsonString)
            AppVersion(
                versionCode = jsonObject.getInt("versionCode"),
                versionName = jsonObject.getString("versionName"),
                apkUrl = jsonObject.getString("apkUrl"),
                minRequired = jsonObject.optBoolean("minRequired", false)
            )
        } catch (e: Exception) {
            Log.e(tag, "Error fetching version: ${e.message}")
            null
        }
    }
    private fun getCurrentVersionCode(): Int {
        return try {
            PackageInfoCompat.getLongVersionCode(context.packageManager.getPackageInfo(context.packageName, 0)).toInt()
        } catch (e: Exception) {
            Log.e(tag, "Error getting current version: ${e.message}")
            -1
        }
    }
    suspend fun checkForUpdate() {
        if (BuildConfig.DEBUG) Log.d(tag, "Checking for updates...")
        val latestVersion = getLatestVersion() ?: return
        if (isInstalledFromFdroid()) {
            if (BuildConfig.DEBUG) Log.d(tag, "Skipping update check - installed from F-Droid")
            return
        }
        val currentVersion = getCurrentVersionCode()
        if (BuildConfig.DEBUG) Log.d(tag, "Current: $currentVersion, Latest: ${latestVersion.versionCode}")
        if (latestVersion.versionCode > currentVersion) {
            if (BuildConfig.DEBUG) Log.i(tag, "New version found: ${latestVersion.versionName}")
            showUpdateNotification(latestVersion)
        } else {
            if (BuildConfig.DEBUG) Log.d(tag, "Already on latest version")
        }
    }
    fun isInstalledFromFdroid(): Boolean {
        val knownFdroidInstallers = listOf("org.fdroid.fdroid", "org.fdroid.fdroid.privileged", "com.fdroid", "fdroid")
        return try {
            val packageName = context.packageName
            val packageManager = context.packageManager
            val installerPackageName = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                val installSourceInfo: InstallSourceInfo? = packageManager.getInstallSourceInfo(packageName)
                installSourceInfo?.installingPackageName
            } else {
                @Suppress("DEPRECATION")
                packageManager.getInstallerPackageName(packageName)
            }
            if (BuildConfig.DEBUG) Log.d(tag, "Installer package detected: $installerPackageName")

            installerPackageName?.let { installer ->
                knownFdroidInstallers.any { known ->
                    installer.equals(known, ignoreCase = true) || (known.length > 3 && installer.contains(known, ignoreCase = true))
                }
            } ?: false
        } catch (e: Exception) {
            Log.e(tag, "Error checking installer package: ${e.message}")
            false
        }
    }
    private fun showUpdateNotification(version: AppVersion) {
        val localizedVersionName = LocaleHelper.formatStringWithLocaleNumerals(context, version.versionName)
        val updateIntent = Intent(Intent.ACTION_VIEW, version.apkUrl.toUri())
        val updatePendingIntent = PendingIntent.getActivity(context, 1, updateIntent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)

        NotificationHelper.sendNotificationWithAction(
            context = context,
            titleResId = R.string.update_notification_title,
            messageResId = R.string.update_notification_message,
            notificationId = NotificationIds.UPDATE_AVAILABLE,
            channelId = "update_channel",
            pendingIntent = null,
            actionTitleResId = R.string.update,
            actionPendingIntent = updatePendingIntent,
            autoCancel = true,
            localizedVersionName
        )
    }
}