package com.yahyaoui.prayermode

import android.app.AlarmManager
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationManager
import android.media.AudioManager
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Looper
import android.util.Log
import androidx.core.content.ContextCompat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.*
import org.json.JSONObject
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.suspendCancellableCoroutine
import java.io.File
import kotlin.coroutines.resumeWithException
import kotlin.coroutines.resume
import com.ibm.icu.util.IslamicCalendar
import java.util.TimeZone
import java.util.*
import androidx.work.WorkInfo
import androidx.work.WorkManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.json.JSONException
import kotlin.math.pow
import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.app.Dialog
import android.os.Build
import android.location.LocationListener
import android.os.Handler
import android.view.LayoutInflater
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import com.yahyaoui.prayermode.LocationService.Companion.MIN_LOCATION_ACCURACY_METERS
import com.yahyaoui.prayermode.LocationService.Companion.SIGNIFICANT_DISPLACEMENT_KM
import com.yahyaoui.prayermode.LocationService.Companion.PREF_LAST_FETCH_TIME_MS
class Tools(private val context: Context) {
    private val tag = "Tools"
    private val sharedHelper = SharedHelper(context)
    private var alarmManager: AlarmManager
    private val pendingIntentMap = HashMap<String, PendingIntent>()
    private val prayerNames = listOf("Fajr", "Dhuhr", "Asr", "Maghrib", "Isha","Taraweeh", "Tahajjud", "jumua","Eid")
    private var isExitSilent = false
    private val prayerNameMap = mapOf(
        "Eid" to R.string.eid,
        "Imsak" to R.string.imsak,
        "Fajr" to R.string.fajr,
        "Sunrise" to R.string.sunrise,
        "Dhuhr" to R.string.dhuhr,
        "Asr" to R.string.asr,
        "Maghrib" to R.string.maghrib,
        "Isha" to R.string.isha,
        "jumua" to R.string.jumua,
        "Taraweeh" to R.string.taraweeh,
        "Tahajjud" to R.string.tahajjud,
        "Ramadan" to R.string.ramadan
    )

    init { alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager }
    companion object {
        private const val MAX_LOCATION_AGE_MS = 5 * 60000L
    }
    suspend fun findLocation(selectedMethod: Int): Boolean {
        return try {
            val location = getCurrentLocation() ?: getLastLocation()
            if (location == null) {
                NotificationHelper.sendNotification(context, R.string.location_title, R.string.location_service_disabled, 291, "")
                Log.w(tag, "Both current and last locations are unavailable")
                return false
            }
            sharedHelper.saveDouble(LocationService.PREF_LAST_FETCH_LATITUDE, location.latitude)
            sharedHelper.saveDouble(LocationService.PREF_LAST_FETCH_LONGITUDE, location.longitude)
            val locationName = GeocodingHelper.getLocationName(context, location)
            if (locationName == null) {
                if (BuildConfig.DEBUG) Log.w(tag, "Geocoding returned null for location")
            }
            val displayName = locationName ?: "Unknown Location"
            if (BuildConfig.DEBUG) Log.i(tag, "Actual location is $displayName")
            NotificationHelper.sendNotification(context, R.string.location_title, R.string.location_name, 270, displayName)
            withContext(Dispatchers.IO) {
                fetchPrayerTimes(location.latitude, location.longitude, selectedMethod)
            }
            true
        } catch (e: SecurityException) {
            Log.e(tag, "A Security Exception occurred while trying to fetch prayer times: ${e.message}", e)
            if (BuildConfig.DEBUG) NotificationHelper.sendNotification(context, R.string.location_title, R.string.location_permission_denied_title, 294, "")
            false
        } catch (e: Exception) {
            Log.e(tag, "An unexpected error occurred in fetch location: ${e.message}", e)
            false
        }
    }
    private fun isLocationEnabled(): Boolean {
        val locationManager = context.getSystemService(Context.LOCATION_SERVICE) as LocationManager
        val isEnabled = locationManager.isProviderEnabled(LocationManager.GPS_PROVIDER) || locationManager.isProviderEnabled(LocationManager.NETWORK_PROVIDER)
        return isEnabled
    }
    suspend fun getCurrentLocation(timeoutMillis: Long = 30000): Location? =
        suspendCancellableCoroutine { continuation ->
            val locationManager = context.getSystemService(LocationManager::class.java)

            if (!isLocationEnabled()) {
                Log.e(tag, "Location services are disabled")
                continuation.resume(null)
                return@suspendCancellableCoroutine
            }

            val hasFineLocation = ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
            val hasCoarseLocation = ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED

            if (!hasFineLocation && !hasCoarseLocation) {
                Log.e(tag, "Location permissions are missing")
                continuation.resume(null)
                return@suspendCancellableCoroutine
            }

            val provider = if (hasFineLocation && locationManager.isProviderEnabled(LocationManager.GPS_PROVIDER)) {
                LocationManager.GPS_PROVIDER
            } else if (hasCoarseLocation && locationManager.isProviderEnabled(LocationManager.NETWORK_PROVIDER)) {
                LocationManager.NETWORK_PROVIDER
            } else {
                Log.e(tag, "No available location providers")
                continuation.resume(null)
                return@suspendCancellableCoroutine
            }

            val handler = Handler(Looper.getMainLooper())
            var isCompleted = false

            val timeoutRunnable = Runnable {
                if (isCompleted) return@Runnable
                isCompleted = true

                Log.w(tag, "Location request timed out after ${timeoutMillis}ms")

                val lastLocation = locationManager.getLastKnownLocation(provider)
                if (lastLocation != null && isLocationAccurate(lastLocation)) {
                    if (BuildConfig.DEBUG) Log.d(tag, "Using last known location after timeout")

                    continuation.resume(lastLocation)
                } else {
                    if (BuildConfig.DEBUG) Log.w(tag, "Last known location unavailable or inaccurate")
                    continuation.resume(null)
                }
            }

            val locationListener = object : LocationListener {
                override fun onLocationChanged(location: Location) {
                    if (isCompleted) return

                    locationManager.removeUpdates(this)
                    handler.removeCallbacks(timeoutRunnable)
                    isCompleted = true

                    if (BuildConfig.DEBUG) Log.d(tag, "Location fetched: ${location.latitude}, ${location.longitude}")
                    if (continuation.isActive) continuation.resume(location)
                }

                override fun onProviderDisabled(provider: String) {
                    Log.w(tag, "Location provider disabled: $provider")
                }

                override fun onProviderEnabled(provider: String) {
                    Log.d(tag, "Location provider enabled: $provider")
                }
            }

            handler.postDelayed(timeoutRunnable, timeoutMillis)

            try {
                val lastKnownLocation = locationManager.getLastKnownLocation(provider)
                lastKnownLocation?.let { location ->
                    val locationAge = System.currentTimeMillis() - location.time
                    if (locationAge < MAX_LOCATION_AGE_MS && isLocationAccurate(location)) {
                        locationManager.removeUpdates(locationListener)
                        handler.removeCallbacks(timeoutRunnable)
                        isCompleted = true

                        if (continuation.isActive) {
                            if (BuildConfig.DEBUG) Log.d(tag, "Using recent last known location  ${location.latitude}, ${location.longitude}")
                            continuation.resume(location)
                            return@let
                        }
                    }
                }

                if (!isCompleted) {
                    locationManager.requestLocationUpdates(provider, 0L, 0f, locationListener, Looper.getMainLooper())
                }

            } catch (securityException: SecurityException) {
                handler.removeCallbacks(timeoutRunnable)
                continuation.resumeWithException(securityException)
            } catch (_: IllegalArgumentException) {
                handler.removeCallbacks(timeoutRunnable)
                continuation.resume(null)
            }

            continuation.invokeOnCancellation {
                try {
                    if (!isCompleted) {
                        locationManager.removeUpdates(locationListener)
                        handler.removeCallbacks(timeoutRunnable)
                        if (BuildConfig.DEBUG) Log.d(tag, "Location request cancelled.")
                    }
                } catch (e: Exception) {
                    Log.e(tag, "Error during location request cancellation: ${e.message}")
                }
            }
        }
    private fun isLocationAccurate(location: Location): Boolean {
        return location.accuracy <= MIN_LOCATION_ACCURACY_METERS
    }
    suspend fun getLastLocation(): Location? = suspendCancellableCoroutine { cont ->
        val locationManager = context.getSystemService(Context.LOCATION_SERVICE) as LocationManager
        val hasFineLocation = ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
        val hasCoarseLocation = ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED

        if (!hasFineLocation && !hasCoarseLocation) {
            cont.resume(null)
            return@suspendCancellableCoroutine
        }

        try {
            val gpsLocation = if (hasFineLocation && locationManager.isProviderEnabled(LocationManager.GPS_PROVIDER)) {
                locationManager.getLastKnownLocation(LocationManager.GPS_PROVIDER)
            } else null

            val networkLocation = if (hasCoarseLocation && locationManager.isProviderEnabled(LocationManager.NETWORK_PROVIDER)) {
                locationManager.getLastKnownLocation(LocationManager.NETWORK_PROVIDER)
            } else null

            val bestLocation = selectBestLocation(gpsLocation, networkLocation)

            if (BuildConfig.DEBUG) {
                if (bestLocation != null) {
                    val age = (System.currentTimeMillis() - bestLocation.time) / 1000
                    Log.d(tag, "Last location: ${bestLocation.latitude}, ${bestLocation.longitude}, Age: ${age}s, Acc: ${bestLocation.accuracy}m")
                } else {
                    Log.w(tag, "No valid last known location found.")
                }
            }

            cont.resume(bestLocation)

        } catch (e: SecurityException) {
            Log.e(tag, "Location permission missing", e)
            cont.resume(null)
        }
    }
    private fun selectBestLocation(location1: Location?, location2: Location?): Location? {
        if (location1 == null) return location2
        if (location2 == null) return location1

        if (location1.accuracy < location2.accuracy && isLocationAccurate(location1)) {
            return location1
        } else if (isLocationAccurate(location2)) {
            return location2
        }

        val newerLocation = if (location1.time > location2.time) location1 else location2
        return if (isLocationStale(newerLocation)) null else newerLocation
    }
    private fun isLocationStale(location: Location): Boolean {
        val locationAge = System.currentTimeMillis() - location.time
        return locationAge > MAX_LOCATION_AGE_MS * 2
    }
    private fun fetchPrayerTimes(latitude: Double, longitude: Double, method: Int) {
        val methodId = getMethodId(method)
        val currentDate = Calendar.getInstance()
        val year = currentDate.get(Calendar.YEAR)
        val month = currentDate.get(Calendar.MONTH) + 1
        if (BuildConfig.DEBUG) Log.i(tag, "Fetch prayer times function - method id: $methodId")
        val url = "https://api.aladhan.com/v1/calendar?latitude=$latitude&longitude=$longitude&method=$methodId&month=$month&year=$year"
        val request = Request.Builder().url(url).build()
        val client = OkHttpClient()
        retryFetch(client, request, 0)
    }
    private fun retryFetch(client: OkHttpClient, request: Request, attempt: Int) {
        if (!sharedHelper.getSwitchState()) {
            if (BuildConfig.DEBUG) Log.i(tag, "Skipping times fetch : main switch is off.")
            return
        }
        if (!isInternetAvailable()) {
            if (BuildConfig.DEBUG) Log.e(tag, "No internet connection detected for attempt ${attempt + 1}/3")
            handleRetryOrFinalFailure(attempt, client, request, FailureType.NO_INTERNET)
            return
        }
        if (BuildConfig.DEBUG) Log.i(tag, "Attempting fetch ${attempt + 1}/3 for URL: ${request.url}")
        client.newCall(request).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                Log.e(tag, "Network error during attempt ${attempt + 1}/3: ${e.message} for URL: ${request.url}", e)
                handleRetryOrFinalFailure(attempt, client, request, FailureType.NETWORK_OR_SERVER_ERROR)
            }
            override fun onResponse(call: Call, response: Response) {
                response.use { res ->
                    if (!res.isSuccessful) {
                        Log.e(tag, "API request unsuccessful: ${res.code} during attempt ${attempt + 1}/3 for URL: ${request.url}")
                        if (res.code in 500..599) {
                            handleRetryOrFinalFailure(attempt, client, request, FailureType.NETWORK_OR_SERVER_ERROR)
                        } else {
                            Log.e(tag, "API error: ${res.code} for URL: ${request.url}. Fetch failed.")
                            NotificationHelper.sendNotification(context, R.string.fetch_title, R.string.fetch_failed, 50, "")
                        }
                        return
                    }

                    val responseBody: String? = res.body.string()
                    if (responseBody.isNullOrEmpty()) {
                        Log.e(tag, "Prayer times response body is null/empty for URL: ${request.url}. Fetch failed.")
                        NotificationHelper.sendNotification(context, R.string.fetch_title, R.string.fetch_failed, 50, "")
                    } else {
                        if (BuildConfig.DEBUG) Log.i(tag, "Prayer times fetched successfully on attempt ${attempt + 1}/3 for URL: ${request.url}")
                        writeToFile(responseBody)
                    }
                }
            }
        })
    }
    enum class FailureType { NO_INTERNET, NETWORK_OR_SERVER_ERROR }
    private fun handleRetryOrFinalFailure(currentAttempt: Int, client: OkHttpClient, request: Request, failureType: FailureType) {
        val maxRetries = 2
        val delay = 5000L * 2.0.pow(currentAttempt.toDouble()).toLong()
        if (currentAttempt < maxRetries) {
            if (BuildConfig.DEBUG) Log.i(tag, "Retrying in ${delay / 1000} seconds...")
            CoroutineScope(Dispatchers.IO).launch {
                delay(delay)
                retryFetch(client, request, currentAttempt + 1)
            }
        } else {
            Log.e(tag, "Max retries reached for URL: ${request.url}. Fetch failed.")
            when (failureType) {
                FailureType.NO_INTERNET -> {
                    NotificationHelper.sendNotification(context, R.string.fetch_title, R.string.fetch_failed_no_internet, 50, "")
                }
                FailureType.NETWORK_OR_SERVER_ERROR -> {
                    NotificationHelper.sendNotification(context, R.string.fetch_title, R.string.fetch_failed, 51, "")
                }
            }
        }
    }
    fun writeToFile(data: String) {
        val file = File(context.filesDir, "prayer_times.txt")
        try {
            file.writeText(data)
            if (BuildConfig.DEBUG) Log.d(tag, "Data successfully written to file: ${file.absolutePath}")
            sharedHelper.saveLong(PREF_LAST_FETCH_TIME_MS, System.currentTimeMillis())
            processPrayerTimes()
        } catch (e: IOException) {
            NotificationHelper.sendNotification(context, R.string.fetch_title, R.string.failed_writing, 440, "")
            Log.e(tag, "Failed to write to file: ${e.message}",e)
        } catch (e: SecurityException) {
            NotificationHelper.sendNotification(context, R.string.fetch_title, R.string.permission_denied_writing, 441, "")
            Log.e(tag, "Permission denied while writing to file: ${e.message}",e)
        }
    }
    fun processPrayerTimes() {
        val file = File(context.filesDir, "prayer_times.txt")
        val methodId = getMethodId(sharedHelper.getIntValue(SharedHelper.SELECTED_METHOD_RES_ID, 0))
        if (BuildConfig.DEBUG) Log.d(tag, "Process function - selected method Id : $methodId")

        try {
            val calendar = Calendar.getInstance()
            val gregorianDate = SimpleDateFormat("dd-MM-yyyy", Locale.US).format(calendar.time)
            val hijriDate = getCurrentHijriDate()
            val hijriDay = hijriDate.optInt("day", 0)
            val hijriMonth = hijriDate.optInt("month", 0)
            val hijriYear = hijriDate.optInt("year", 0)
            val lastDay = hijriDate.optInt("daysInMonth", 0)

            val fileContent = file.bufferedReader().use { it.readText() }
            val fileJson = JSONObject(fileContent)
            val dataArray = fileJson.getJSONArray("data")
            if (BuildConfig.DEBUG) Log.i(tag, "Processing for: $gregorianDate - Hijri: $hijriDay-$hijriMonth-$hijriYear - $lastDay days in Hijri month")

            var prayerTimes: JSONObject? = null
            for (i in 0 until dataArray.length()) {
                val dayData = dataArray.getJSONObject(i)
                val gregorian = dayData.getJSONObject("date").getJSONObject("gregorian")
                val date = gregorian.getString("date")

                if (gregorianDate == date) {
                    prayerTimes = dayData.getJSONObject("timings")
                    if (BuildConfig.DEBUG) Log.i(tag, "Found prayer times for Gregorian date: $date")
                    break
                }
            }

            if (prayerTimes != null) {
                if (BuildConfig.DEBUG) {
                    Log.d(tag, "Current prayerTimes JSON: $prayerTimes")
                    Log.i(tag, "Prayer times for date $gregorianDate - Hijri $hijriDay-$hijriMonth-$hijriYear retrieved successfully")
                }
                val timesList = listOf("Fajr", "Dhuhr", "Asr", "Maghrib", "Isha")
                val currentWeekday = getCurrentWeekday()
                val dhuhrTime = prayerTimes.optString("Dhuhr")
                val fajrTime = prayerTimes.optString("Fajr")
                val ishaTime = prayerTimes.optString("Isha")
                val sunriseTime = prayerTimes.optString("Sunrise")
                val imsakTime = prayerTimes.optString("Imsak")
                val currentTime = Calendar.getInstance()

                val isLastDayOfShaban = (hijriMonth == 8 && hijriDay == lastDay)
                val isRamadan = (hijriMonth == 9)
                val isLastDayOfRamadan = (isRamadan && hijriDay == lastDay)
                val isTaraweehPeriod = isLastDayOfShaban || (isRamadan && !isLastDayOfRamadan)
                val isTahajjudPeriod = isRamadan
                val isEidAlFitr = (hijriMonth == 10 && hijriDay == 1)
                val isEidAlAdha = (hijriMonth == 12 && hijriDay == 10)
                val ablutionEnabled = sharedHelper.getAblutionSwitchState()

                if (currentWeekday == "Friday") {
                    if (!pendingIntentMap.containsKey(dhuhrTime)) {
                        val dhuhrCalendar = getPrayerCalendar(dhuhrTime)
                        if (dhuhrCalendar != null){
                            val cleanedDhuhrTime = "${dhuhrCalendar.get(Calendar.HOUR_OF_DAY)}:${dhuhrCalendar.get(Calendar.MINUTE)}"
                            val durationBeforeInt = getBeforeJumuaDurationId(sharedHelper.getIntValue(SharedHelper.DURATION_BEFORE_JUMUA, 0))
                            val durationAfterInt = getAfterJumuaDurationId(sharedHelper.getIntValue(SharedHelper.DURATION_AFTER_JUMUA, 3))
                            val effectiveBefore = if (durationBeforeInt > 0) durationBeforeInt else (if (ablutionEnabled) 10 else 0)
                            if (BuildConfig.DEBUG) Log.i(tag, "Jumua duration $durationBeforeInt min before - ablution is $ablutionEnabled, activated $effectiveBefore min before, $durationAfterInt min after")

                            val prayerTimeMillis = dhuhrCalendar.timeInMillis
                            val currentMillis = currentTime.timeInMillis
                            val startTimeMillis = prayerTimeMillis - TimeUnit.MINUTES.toMillis(effectiveBefore.toLong())
                            val endTimeMillis = prayerTimeMillis + TimeUnit.MINUTES.toMillis(durationAfterInt.toLong())
                            val silentModeRange = startTimeMillis..endTimeMillis

                            val formattedDhuhrTime = LocaleHelper.formatTimeForNotification(context, dhuhrCalendar)
                            if (BuildConfig.DEBUG) Log.i(tag, "Today Al-jumua is at $formattedDhuhrTime")
                            val isAblutionBefore = (durationBeforeInt == 0 && ablutionEnabled)

                            if (currentMillis in silentModeRange || dhuhrCalendar.after(currentTime)) {
                                NotificationHelper.sendNotification(context, R.string.schedule_title, R.string.today_dhuhr_time_is, 150, formattedDhuhrTime)
                                scheduleSilentMode(cleanedDhuhrTime, effectiveBefore, durationAfterInt, "jumua", isAblutionBefore)
                            } else {
                                if (BuildConfig.DEBUG) Log.i(tag, "Skipping jumua silent mode until after worker execution")
                            }
                        } else Log.w(tag, "Prayer calendar is null for jumua")
                    } else {
                        if (BuildConfig.DEBUG) Log.i(tag, "Silent mode already scheduled for jumua at $dhuhrTime")
                    }
                }
                if (isTaraweehPeriod) {
                    if (!pendingIntentMap.containsKey(ishaTime)) {
                        val ishaCalendar = getPrayerCalendar(ishaTime)
                        if (ishaCalendar != null) {
                            val cleanedIshaTime = "${ishaCalendar.get(Calendar.HOUR_OF_DAY)}:${ishaCalendar.get(Calendar.MINUTE)}"
                            val durationTaraweehInt = getTaraweehDurationId(sharedHelper.getIntValue(SharedHelper.DURATION_TARAWEEH, 4))
                            val durationBeforeInt = if (ablutionEnabled) 10 else 0
                            if (BuildConfig.DEBUG) Log.i(tag, "Taraweeh: $durationBeforeInt min before (ablution), $durationTaraweehInt min after")

                            val prayerTimeMillis = ishaCalendar.timeInMillis
                            val endTimeMillis = prayerTimeMillis + TimeUnit.MINUTES.toMillis(durationTaraweehInt.toLong())
                            val currentMillis = currentTime.timeInMillis
                            val silentModeRange = prayerTimeMillis..endTimeMillis
                            val isAblutionBefore = ablutionEnabled

                            if (currentMillis in silentModeRange || ishaCalendar.after(currentTime)) {
                                scheduleSilentMode(cleanedIshaTime, durationBeforeInt, durationTaraweehInt, "Taraweeh", isAblutionBefore)
                            } else {
                                if (BuildConfig.DEBUG) Log.i(tag, "Skipping Taraweeh silent mode until after worker execution")
                            }
                        } else Log.w(tag, "Prayer calendar is null for Taraweeh")
                    } else {
                        if (BuildConfig.DEBUG) Log.i(tag, "Silent mode already scheduled for Taraweeh 60 min after $ishaTime")
                    }
                }
                if (isTahajjudPeriod) {
                     if (!pendingIntentMap.containsKey(fajrTime)) {
                        val fajrCalendar = getPrayerCalendar(fajrTime)
                        if (fajrCalendar != null) {
                            val cleanedFajrTime = "${fajrCalendar.get(Calendar.HOUR_OF_DAY)}:${fajrCalendar.get(Calendar.MINUTE)}"
                            val durationTahajjudInt = getTahajjudDurationId(sharedHelper.getIntValue(SharedHelper.DURATION_TAHAJJUD, 0))
                            val durationFajrInt = getDurationId(sharedHelper.getIntValue(SharedHelper.DURATION_FAJR, 3))
                            val effectiveBefore = if (durationTahajjudInt > 0) durationTahajjudInt else (if (ablutionEnabled) 10 else 0)
                            if (BuildConfig.DEBUG) Log.i(tag, "Tahajjud duration $durationTahajjudInt min - ablution is $ablutionEnabled, activated $effectiveBefore min before, $durationFajrInt min after")

                            val prayerTimeMillis = fajrCalendar.timeInMillis
                            val currentMillis = currentTime.timeInMillis
                            val startTimeMillis = prayerTimeMillis - TimeUnit.MINUTES.toMillis(effectiveBefore.toLong())
                            val endTimeMillis = prayerTimeMillis + TimeUnit.MINUTES.toMillis(durationFajrInt.toLong())
                            val silentModeRange = startTimeMillis..endTimeMillis
                            val isAblutionBefore = (durationTahajjudInt == 0 && ablutionEnabled)

                            if (currentMillis in silentModeRange || fajrCalendar.after(currentTime)) {
                                scheduleSilentMode(cleanedFajrTime, effectiveBefore, durationFajrInt, "Tahajjud", isAblutionBefore)
                                val imsakCalendar = getPrayerCalendar(imsakTime)

                                if (imsakCalendar != null) {
                                    val displayedImsakTime  = LocaleHelper.formatTimeForNotification(context, imsakCalendar)
                                    NotificationHelper.sendNotification(context, R.string.schedule_title, R.string.today_imsak_time_is, 155, displayedImsakTime)
                                }
                            } else {
                                if (BuildConfig.DEBUG) Log.i(tag, "Skipping tahajjud silent mode until after worker execution")
                            }
                        } else Log.w(tag, "Prayer calendar is null for Tahajjud")
                    } else {
                        if (BuildConfig.DEBUG) Log.i(tag, "Silent mode already scheduled for Tahajjud 60 min before $fajrTime")
                    }
                }
                if (isEidAlFitr || isEidAlAdha) {
                    if (hijriDay == 1) NotificationHelper.sendNotification(context, R.string.schedule_title, R.string.happy_eid_alfitr, 650, "")
                    else NotificationHelper.sendNotification(context, R.string.schedule_title, R.string.happy_eid_aladha, 650, "")

                    val eidTimeIndex = sharedHelper.getIntValue(SharedHelper.SELECTED_TIME_EID, 0)
                    val eidDurationIndex = sharedHelper.getIntValue(SharedHelper.DURATION_EID, 2)

                    if (eidDurationIndex == 0) {
                        if (BuildConfig.DEBUG) Log.i(tag, "Eid duration is set to 0 (Off). Skipping silent mode scheduling for Eid.")
                    } else {
                        val eidTime = when (eidTimeIndex) {
                            0 -> {
                                val sunriseCalendar = getPrayerCalendar(sunriseTime)
                                if (sunriseCalendar != null) {
                                    val cleanedSunriseTime = "${sunriseCalendar.get(Calendar.HOUR_OF_DAY)}:${sunriseCalendar.get(Calendar.MINUTE)}"
                                    val sunriseCalendarPlus20 = Calendar.getInstance()
                                    val parsedTime = SimpleDateFormat("HH:mm", Locale.US).parse(cleanedSunriseTime)
                                    if (parsedTime != null) {
                                        sunriseCalendarPlus20.time = parsedTime
                                        sunriseCalendarPlus20.add(Calendar.MINUTE, 20)
                                        val finalStartTime = String.format(Locale.US, "%02d:%02d", sunriseCalendarPlus20.get(Calendar.HOUR_OF_DAY), sunriseCalendarPlus20.get(Calendar.MINUTE))
                                        if (BuildConfig.DEBUG) Log.i(tag, "Eid start time (20 minutes after sunrise): $finalStartTime")
                                        finalStartTime
                                    } else {
                                        Log.e(tag, "Failed to parse sunrise time: $cleanedSunriseTime; defaulting to 06:00")
                                        "06:00"
                                    }
                                } else {
                                    Log.e(tag, "Failed to get sunrise time; defaulting to 06:00")
                                    "06:00"
                                }
                            }
                            1 -> "06:00"
                            2 -> "06:30"
                            3 -> "07:00"
                            4 -> "07:30"
                            5 -> "08:00"
                            6 -> "08:30"
                            7 -> "09:00"
                            8 -> "10:00"
                            else -> "06:00"
                        }
                        val eidDuration = when (eidDurationIndex) {
                            1 -> 20
                            2 -> 30
                            3 -> 40
                            4 -> 50
                            5 -> 60
                            else -> 30
                        }
                        if (BuildConfig.DEBUG) Log.i(tag, "Selected Eid time: $eidTime and Eid duration $eidDuration minutes")

                        val eidCalendar = getPrayerCalendar(eidTime)
                        if (eidCalendar == null) {
                            Log.e(tag, "Failed to create Calendar instance for eidTime: $eidTime")
                            return
                        }
                        val eidTimeMillis = eidCalendar.timeInMillis
                        val currentMillis = Calendar.getInstance().timeInMillis
                        val startTimeMillis = eidTimeMillis - TimeUnit.MINUTES.toMillis(0)
                        val endTimeMillis = eidTimeMillis + TimeUnit.MINUTES.toMillis(eidDuration.toLong())
                        val silentModeRange = startTimeMillis..endTimeMillis

                        if (BuildConfig.DEBUG) Log.i(tag, "Silent mode range: Start=$startTimeMillis, End=$endTimeMillis, Current=$currentMillis")

                        if (currentMillis in silentModeRange || !pendingIntentMap.containsKey(eidTime)) {
                            scheduleSilentMode(eidTime, 0, eidDuration, "Eid")
                            if (BuildConfig.DEBUG) Log.i(tag, "Silent mode scheduled for Eid at start time: $eidTime, duration: $eidDuration minutes")
                        } else {
                            if (BuildConfig.DEBUG) Log.i(tag, "Silent mode already scheduled for Eid at $eidTime")
                        }
                    }
                }
                timesList.forEach { key ->
                    val isFridayDhuhr = (currentWeekday == "Friday" && key == "Dhuhr")
                    val shouldSkipIsha = (key == "Isha" && isTaraweehPeriod)
                    val shouldSkipFajr = (key == "Fajr" && isTahajjudPeriod)

                    if (!isFridayDhuhr && !shouldSkipIsha && !shouldSkipFajr) {
                        val time = prayerTimes.optString(key)
                        val prayerCalendar = getPrayerCalendar(time)
                        if (prayerCalendar != null) {
                            val cleanedTime = "${prayerCalendar.get(Calendar.HOUR_OF_DAY)}:${prayerCalendar.get(Calendar.MINUTE)}"
                            if (BuildConfig.DEBUG) Log.d(tag, "Processing $key at $cleanedTime")

                            val prayerTimeMillis = prayerCalendar.timeInMillis
                            val durationValueInt = getPrayerDuration(key, sharedHelper)
                            val durationBeforeInt = if (ablutionEnabled) 10 else 0
                            if (BuildConfig.DEBUG) Log.i(tag, "Duration for $key: $durationBeforeInt min before, $durationValueInt min after")

                            val startTimeMillis = prayerTimeMillis - TimeUnit.MINUTES.toMillis(durationBeforeInt.toLong())
                            val endTimeMillis = prayerTimeMillis + TimeUnit.MINUTES.toMillis(durationValueInt.toLong())
                            val currentMillis = currentTime.timeInMillis
                            val silentModeRange = startTimeMillis..endTimeMillis
                            val isAblutionBefore = ablutionEnabled

                            if (currentMillis in silentModeRange || prayerCalendar.after(currentTime)) {
                                if (!pendingIntentMap.containsKey(cleanedTime)) {
                                    scheduleSilentMode(cleanedTime, durationBeforeInt, durationValueInt, key, isAblutionBefore)
                                    if (key == "Fajr") {
                                        val fajrCalendar = getPrayerCalendar(fajrTime)

                                        if (fajrCalendar != null) {
                                            val displayedFajrTime = LocaleHelper.formatTimeForNotification(context, fajrCalendar)
                                            NotificationHelper.sendNotification(context, R.string.schedule_title, R.string.today_fajr_time_is, 151, displayedFajrTime)
                                        }
                                    }
                                    if (BuildConfig.DEBUG) Log.i(tag, "Scheduling silent mode $key for $durationValueInt minutes")
                                } else {
                                    if (BuildConfig.DEBUG) Log.i(tag, "Silent mode already scheduled for $key at $cleanedTime")
                                }
                            } else {
                                if (BuildConfig.DEBUG) Log.i(tag, "Skipping $key silent mode until after worker execution")
                            }
                        } else Log.w(tag, "Prayer calendar is null for $key")
                    }
                }
            } else Log.e(tag, "Prayer times for date $gregorianDate / Hijri $hijriDay $hijriMonth not found in file")
        } catch (e: IOException) {
            if (BuildConfig.DEBUG) NotificationHelper.sendNotification(context, R.string.process_prayer, R.string.fail_read_file, 442, "${e.message}")
            Log.e(tag, "Failed to read from file: ${e.message}",e)
        } catch (e: JSONException) {
            if (BuildConfig.DEBUG) NotificationHelper.sendNotification(context, R.string.process_prayer, R.string.fail_read_file, 443, "${e.message}")
            Log.e(tag, "Failed to parse JSON data: ${e.message}",e)
        } catch (e: Exception) {
            Log.e(tag, "An unexpected error occurred while processing prayer times: ${e.message}",e)
        }
    }
    private fun getPrayerDuration(prayerName: String, sharedHelper: SharedHelper): Int {
        val durationIndex = when (prayerName) {
            "Fajr" -> sharedHelper.getIntValue(SharedHelper.DURATION_FAJR, 3)
            "Dhuhr" -> sharedHelper.getIntValue(SharedHelper.DURATION_DHUHR, 3)
            "Asr" -> sharedHelper.getIntValue(SharedHelper.DURATION_ASR, 3)
            "Maghrib" -> sharedHelper.getIntValue(SharedHelper.DURATION_MAGHRIB, 3)
            "Isha" -> sharedHelper.getIntValue(SharedHelper.DURATION_ISHA, 3)
            else -> 3
        }
        return getDurationId(durationIndex)
    }
    private fun scheduleSilentMode(time: String, durationBefore: Int, durationAfter: Int, prayerName: String, isAblutionBefore: Boolean = false) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            if (!alarmManager.canScheduleExactAlarms()) {
                if (BuildConfig.DEBUG) Log.w(tag, "Exact alarm permission not granted. Cannot schedule silent mode precisely for $prayerName.")
                return
            }
        }
        try {
            val timeParts = time.split(":")
            if (timeParts.size != 2) {
                Log.e(tag, "Invalid time format for $prayerName: $time")
                return
            }
            val hour = timeParts[0].toIntOrNull() ?: run {
                Log.e(tag, "Invalid hour in time for $prayerName: $time")
                return
            }
            val minute = timeParts[1].toIntOrNull() ?: run {
                Log.e(tag, "Invalid minute in time for $prayerName: $time")
                return
            }
            val prayerCalendar = Calendar.getInstance(TimeZone.getDefault()).apply {
                set(Calendar.HOUR_OF_DAY, hour)
                set(Calendar.MINUTE, minute)
                set(Calendar.SECOND, 0)
            }
            val currentTimeInMillis = System.currentTimeMillis()
            val prayerTimeInMillis = prayerCalendar.timeInMillis
            val startTimeInMillis = prayerTimeInMillis - TimeUnit.MINUTES.toMillis(durationBefore.toLong())
            val endTimeInMillis = prayerTimeInMillis + TimeUnit.MINUTES.toMillis(durationAfter.toLong())

            if (currentTimeInMillis in startTimeInMillis..endTimeInMillis) {
                if (BuildConfig.DEBUG) Log.i(tag, "Current time is within the silent mode duration for $prayerName. Activating silent mode immediately.")
                val immediateIntent = Intent(context, SilentModeReceiver::class.java).apply {
                    action = "START_SILENT_MODE"
                    putExtra("mode", true)
                    putExtra("prayerName", prayerName)
                    putExtra("isImmediate", true)
                    putExtra("isAblutionBefore", isAblutionBefore)
                }
                context.sendBroadcast(immediateIntent)
            }

            if (currentTimeInMillis < startTimeInMillis) {
                val startIntent = Intent(context, SilentModeReceiver::class.java).apply {
                    action = "START_SILENT_MODE"
                    putExtra("mode", true)
                    putExtra("prayerName", prayerName)
                    putExtra("isAblutionBefore", isAblutionBefore)
                }
                val startPendingIntent = PendingIntent.getBroadcast(
                    context, (prayerName.hashCode() * 10) + 1, startIntent,
                    PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
                )
                alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
                alarmManager.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, startTimeInMillis, startPendingIntent)
                if (BuildConfig.DEBUG) Log.i(tag, "Silent mode START scheduled for $prayerName at $startTimeInMillis ms")
                pendingIntentMap[prayerName + "_start"] = startPendingIntent
            } else {
                if (BuildConfig.DEBUG) Log.i(tag, "Start time for $prayerName has already passed: $startTimeInMillis ms")
            }

            if (currentTimeInMillis < endTimeInMillis) {
                val endIntent = Intent(context, SilentModeReceiver::class.java).apply {
                    action = "END_SILENT_MODE"
                    putExtra("mode", false)
                    putExtra("prayerName", prayerName)
                }
                val endPendingIntent = PendingIntent.getBroadcast(
                    context, (prayerName.hashCode() * 10) + 2, endIntent,
                    PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
                )
                alarmManager.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, endTimeInMillis, endPendingIntent)
                if (BuildConfig.DEBUG) Log.i(tag, "Silent mode END scheduled for $prayerName at $endTimeInMillis ms")
                pendingIntentMap[prayerName + "_end"] = endPendingIntent

                val backupBuffer = 5
                val backupEndTimeMillis = endTimeInMillis + TimeUnit.MINUTES.toMillis(backupBuffer.toLong())
                val backupEndIntent = Intent(context, SilentModeReceiver::class.java).apply {
                    action = "END_SILENT_MODE"
                    putExtra("mode", false)
                    putExtra("prayerName", prayerName)
                    putExtra("isBackup", true)
                }
                val backupEndPendingIntent = PendingIntent.getBroadcast(
                    context, (prayerName.hashCode() * 10) + 3, backupEndIntent,
                    PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
                )
                alarmManager.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, backupEndTimeMillis, backupEndPendingIntent)
                if (BuildConfig.DEBUG) Log.i(tag, "BACKUP END alarm scheduled for $prayerName at $backupEndTimeMillis ms (+$backupBuffer min)")
                pendingIntentMap[prayerName + "_backup"] = backupEndPendingIntent
            } else {
                if (BuildConfig.DEBUG) Log.i(tag, "End time for $prayerName has already passed: $endTimeInMillis ms")
            }
            NotificationHelper.sendNotification(context, R.string.schedule_title, R.string.silent_schedule_success, 430, "")
        } catch (e: SecurityException) {
            Log.e(tag, "SecurityException while setting exact alarm for $prayerName: ${e.message}. Permission might have been revoked unexpectedly.")
            if (BuildConfig.DEBUG) NotificationHelper.sendNotification(context, R.string.error, R.string.failed_schedule_silent_mode, 449, "${e.message}")
        } catch (e: Exception) {
            Log.e(tag, "Failed to schedule silent mode for $prayerName at $time.", e)
            if (BuildConfig.DEBUG) NotificationHelper.sendNotification(context, R.string.error, R.string.failed_schedule_silent_mode, 444, "${e.message}")
        }
    }
    fun setSilentMode(enable: Boolean, prayerName: String, isAblutionBefore: Boolean = false): Boolean {
        val localizedPrayerName = getLocalizedPrayerName(prayerName)
        val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager

        val hasDndAccess = notificationManager.isNotificationPolicyAccessGranted
        if (!hasDndAccess) {
            if (BuildConfig.DEBUG) Log.w(tag, "DND access not granted, cannot enable/disable silent mode.")
            NotificationHelper.sendNotification(context, R.string.dnd_permission_title, R.string.dnd_permission_message, 224, "")
            return false
        }

        return try {
            if (enable) {
                val isAlreadyInOurDesiredDNDState = (notificationManager.currentInterruptionFilter == NotificationManager.INTERRUPTION_FILTER_ALARMS && audioManager.ringerMode == AudioManager.RINGER_MODE_SILENT)
                val isAnyOtherDNDActive = (notificationManager.currentInterruptionFilter != NotificationManager.INTERRUPTION_FILTER_ALL && notificationManager.currentInterruptionFilter != NotificationManager.INTERRUPTION_FILTER_ALARMS)

                if (isAlreadyInOurDesiredDNDState) {
                    if (BuildConfig.DEBUG) Log.i(tag, "Device already in desired DND state (Alarms only). Not taking control.")
                    NotificationHelper.sendNotification(context, R.string.schedule_title, R.string.dnd_already_on, 210, localizedPrayerName)
                    true
                } else if (isAnyOtherDNDActive) {
                    if (BuildConfig.DEBUG) Log.i(tag, "Another DND mode is active. Not overriding existing settings.")
                    NotificationHelper.sendNotification(context, R.string.schedule_title, R.string.dnd_already_on, 210, localizedPrayerName)
                    true
                } else {
                    sharedHelper.saveIntValue("music_volume", audioManager.getStreamVolume(AudioManager.STREAM_MUSIC))
                    sharedHelper.saveIntValue("notification_volume", audioManager.getStreamVolume(AudioManager.STREAM_NOTIFICATION))
                    sharedHelper.saveIntValue("ring_volume", audioManager.getStreamVolume(AudioManager.STREAM_RING))
                    sharedHelper.saveIntValue("system_volume", audioManager.getStreamVolume(AudioManager.STREAM_SYSTEM))
                    if (BuildConfig.DEBUG) Log.i(tag, "Current volumes saved before DND activation.")

                    audioManager.ringerMode = AudioManager.RINGER_MODE_SILENT
                    notificationManager.setInterruptionFilter(NotificationManager.INTERRUPTION_FILTER_ALARMS)
                    if (BuildConfig.DEBUG) Log.i(tag, "Setting DND/Silent, voice call volume is active.")

                    audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, 0, 0)
                    audioManager.setStreamVolume(AudioManager.STREAM_NOTIFICATION, 0, 0)
                    audioManager.setStreamVolume(AudioManager.STREAM_RING, 0, 0)
                    audioManager.setStreamVolume(AudioManager.STREAM_SYSTEM, 0, 0)
                    if (BuildConfig.DEBUG) Log.i(tag, "Music, Notification, Ring & System volumes are muted")

                    sharedHelper.saveBoolean(SharedHelper.IS_APP_CONTROLLED_DND_ACTIVE, true)
                    if (BuildConfig.DEBUG) Log.i(tag, "Saved IS_APP_CONTROLLED_DND_ACTIVE as TRUE, App controls DND.")

                    if (sharedHelper.getAblutionSwitchState() && isAblutionBefore) {
                        if (BuildConfig.DEBUG) Log.i(tag, "Do Not Disturb activated for $localizedPrayerName 10 minutes before Adhan.")
                        NotificationHelper.sendNotification(context, R.string.schedule_title, R.string.ablution_silent_mode_activation, 220, localizedPrayerName)
                    } else {
                        if (BuildConfig.DEBUG) Log.i(tag, "Do Not Disturb activated for $localizedPrayerName.")
                        NotificationHelper.sendNotification(context, R.string.schedule_title, R.string.silent_mode_activated_for, 220, localizedPrayerName)
                    }
                    true
                }
            } else {
                val wasAppControlledDnd = sharedHelper.getBoolean(SharedHelper.IS_APP_CONTROLLED_DND_ACTIVE, false)
                if (BuildConfig.DEBUG) Log.d(tag, "Deactivation: hasDndAccess is true, wasAppControlledDnd is $wasAppControlledDnd")

                if (wasAppControlledDnd) {
                    audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, sharedHelper.getIntValue("music_volume", audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC) / 2), 0)
                    audioManager.setStreamVolume(AudioManager.STREAM_NOTIFICATION, sharedHelper.getIntValue("notification_volume", audioManager.getStreamMaxVolume(AudioManager.STREAM_NOTIFICATION) / 2), 0)
                    audioManager.setStreamVolume(AudioManager.STREAM_RING, sharedHelper.getIntValue("ring_volume", audioManager.getStreamMaxVolume(AudioManager.STREAM_RING) / 2), 0)
                    audioManager.setStreamVolume(AudioManager.STREAM_SYSTEM, sharedHelper.getIntValue("system_volume", audioManager.getStreamMaxVolume(AudioManager.STREAM_SYSTEM) / 2), 0)
                    if (BuildConfig.DEBUG) Log.i(tag, "All volumes restored to original values or defaults")

                    audioManager.ringerMode = AudioManager.RINGER_MODE_NORMAL
                    notificationManager.setInterruptionFilter(NotificationManager.INTERRUPTION_FILTER_ALL)

                    sharedHelper.saveBoolean(SharedHelper.IS_APP_CONTROLLED_DND_ACTIVE, false)
                    if (BuildConfig.DEBUG) Log.i(tag, "Saved IS_APP_CONTROLLED_DND_ACTIVE as FALSE, App no longer controls DND.")

                    if (!isExitSilent) {
                        NotificationHelper.sendNotification(context, R.string.schedule_title, R.string.back_to_normal_mode, 221, "")
                        if (BuildConfig.DEBUG) Log.d(tag, "Setting RINGER_MODE_NORMAL and INTERRUPTION_FILTER_ALL")
                    }
                    true
                } else {
                    if (BuildConfig.DEBUG) Log.i(tag, "App was not in control of DND state. Volumes and DND filters not touched. Respecting existing user/system DND.")
                    true
                }
            }
        } catch (e: SecurityException) {
            Log.e(tag, "A SecurityException occurred while trying to set silent mode: ${e.message}", e)
            if (BuildConfig.DEBUG) NotificationHelper.sendNotification(context, R.string.error, R.string.failed_schedule_silent_mode, 450, "${e.message}")
            return false
        } catch (e: Exception) {
            Log.e(tag, "An unexpected error occurred in setSilentMode: ${e.message}", e)
            if (BuildConfig.DEBUG) NotificationHelper.sendNotification(context, R.string.error, R.string.failed_schedule_silent_mode, 451, "${e.message}")
            return false
        }
    }
    private fun getPrayerCalendar(prayerTime: String): Calendar? {
        try {
            val cleanedTime = prayerTime.split(" ")[0]
            if (BuildConfig.DEBUG) Log.d(tag, "Original time: $prayerTime, Cleaned time: $cleanedTime")

            val timeParts = cleanedTime.split(":")
            if (timeParts.size != 2) {
                throw IllegalArgumentException("Invalid time format: $cleanedTime. Expected HH:MM.")
            }
            val hour = timeParts[0].toInt()
            val minute = timeParts[1].toInt()
            return Calendar.getInstance().apply {
                set(Calendar.HOUR_OF_DAY, hour)
                set(Calendar.MINUTE, minute)
                set(Calendar.SECOND, 0)
            }
        } catch (e: NumberFormatException) {
            if (BuildConfig.DEBUG) NotificationHelper.sendNotification(context, R.string.error, R.string.error_parsing_time, 431, "${e.message}")
            Log.e(tag, "Error parsing time: $prayerTime. Hour or minute is not a valid number.",e)
            return null
        } catch (e: IllegalArgumentException) {
            if (BuildConfig.DEBUG) NotificationHelper.sendNotification(context, R.string.error, R.string.invalid_time_format, 432, "${e.message}")
            Log.e(tag, "Error: Invalid time format for '$prayerTime'. ${e.message}", e)
            return null
        } catch (e: Exception) {
            if (BuildConfig.DEBUG) NotificationHelper.sendNotification(context, R.string.error, R.string.unexpected_error, 448, "${e.message}")
            Log.e(tag, "An unexpected error occurred while getting prayer calendar for: '$prayerTime'. ${e.message}", e)
            return null
        }
    }
    private fun getCurrentHijriDate(): JSONObject {
        try {
            val gregorianCalendar = Calendar.getInstance(TimeZone.getDefault())
            val calendar = IslamicCalendar(gregorianCalendar.time)
            val hijriYear = calendar.get(IslamicCalendar.YEAR).toString()
            val hijriMonth = (calendar.get(IslamicCalendar.MONTH) + 1).toString()
            val hijriDay = calendar.get(IslamicCalendar.DAY_OF_MONTH).toString()
            val daysInMonth = calendar.getActualMaximum(IslamicCalendar.DAY_OF_MONTH).toString()

            return JSONObject().apply {
                put("year", hijriYear)
                put("month", hijriMonth)
                put("day", hijriDay)
                put("daysInMonth", daysInMonth)
                put("timezone", TimeZone.getDefault().id)
            }
        } catch (e: Exception) {
            Log.e(tag, "Error getting Hijri date: ${e.message}",e)
            if (BuildConfig.DEBUG) NotificationHelper.sendNotification(context, R.string.error, R.string.error_getting_hijri_date, 445, "${e.message}")
            return JSONObject().apply {
                put("error", e.message)
            }
        }
    }
    fun exitSilentMode() {
        isExitSilent = true
        setSilentMode(false, "", false)
        isExitSilent = false
        if (BuildConfig.DEBUG) Log.d(tag, "Silent mode called. Exiting DND not controlled by App.")
    }
    fun processMethodChange(selectedMethodIndex: Int): Boolean {
        return try {
            val lastCheckedMethodIndex = sharedHelper.getLastCheckedMethodIndex()
            val methodIndex = sharedHelper.getIntValue(SharedHelper.SELECTED_METHOD_RES_ID, 0)
            if (BuildConfig.DEBUG) {
                Log.i(tag, "Selected method : $methodIndex, selected method Index: $selectedMethodIndex")
                Log.i(tag, "Last checked method Index: $lastCheckedMethodIndex")
            }

            if (lastCheckedMethodIndex == -1) {
                if (BuildConfig.DEBUG) Log.i(tag, "Calculation method has changed or not tracked (old: $lastCheckedMethodIndex, new: $selectedMethodIndex)")
                sharedHelper.saveLastCheckedMethodIndex(selectedMethodIndex)
                return true
            }

            if (lastCheckedMethodIndex != selectedMethodIndex) {
                if (BuildConfig.DEBUG) Log.i(tag, "Calculation method has changed (old: $lastCheckedMethodIndex, new: $selectedMethodIndex)")
                sharedHelper.saveLastCheckedMethodIndex(selectedMethodIndex)
                return true
            }

            if (BuildConfig.DEBUG) Log.i(tag, "Calculation method remains unchanged.")
            false
        } catch (e: Exception) {
            Log.e(tag, "Error processing method change: ${e.stackTraceToString()}")
            false
        }
    }
    suspend fun checkIfDataAvailable(): Boolean {
        return try {
            val file = File(context.filesDir, "prayer_times.txt")
            if (!file.exists() || file.length() == 0L) {
                if (BuildConfig.DEBUG) Log.d(tag, "File missing or empty")
                return false
            }

            val fileContent = file.bufferedReader().use { it.readText() }
            val fileJson = JSONObject(fileContent)
            val dataArray = fileJson.getJSONArray("data")

            if (dataArray.length() == 0) {
                if (BuildConfig.DEBUG) Log.i(tag, "Data array is empty")
                return false
            }

            val firstEntry = dataArray.getJSONObject(0)
            val dateStr = firstEntry.getJSONObject("date").getJSONObject("gregorian").getString("date")
            val monthYear = dateStr.substring(3)
            val currentMonthYear = SimpleDateFormat("MM-yyyy", Locale.US).format(Date())

            if (monthYear != currentMonthYear) {
                if (BuildConfig.DEBUG) Log.i(tag, "Data outdated: $monthYear != $currentMonthYear")
                return false
            }

            val lastLat = sharedHelper.getDouble(LocationService.PREF_LAST_FETCH_LATITUDE, Double.NaN)
            val lastLon = sharedHelper.getDouble(LocationService.PREF_LAST_FETCH_LONGITUDE, Double.NaN)

            if (lastLat.isNaN() || lastLon.isNaN()) {
                if (BuildConfig.DEBUG) Log.i(tag, "No last fetch location saved")
                return false
            }

            val currentLocation = getLastLocation() ?: run {
                if (BuildConfig.DEBUG) Log.w(tag, "Could not get current location, but date is valid")
                return true
            }

            val lastFetchLocation = Location("").apply {
                latitude = lastLat
                longitude = lastLon
            }

            val displacementKm = lastFetchLocation.distanceTo(currentLocation) / 1000

            if (displacementKm >= SIGNIFICANT_DISPLACEMENT_KM) {
                if (BuildConfig.DEBUG) Log.i(tag, "Location changed significantly (${"%.1f".format(displacementKm)} km). Data considered outdated.")
                return false
            }

            if (BuildConfig.DEBUG) Log.i(tag, "File data is valid for the current Gregorian month")
            true
        } catch (e: Exception) {
            Log.e(tag, "Unexpected error: ${e.stackTraceToString()}",e)
            false
        }
    }
    fun cancelScheduledSilentMode() {
        if (pendingIntentMap.isNotEmpty()) {
            if (BuildConfig.DEBUG) Log.d(tag, "Canceling ${pendingIntentMap.size} scheduled silent mode intents.")
            for ((prayerName, pendingIntent) in pendingIntentMap) {
                alarmManager.cancel(pendingIntent)
                if (BuildConfig.DEBUG) Log.d(tag, "Canceled intent for prayer: $prayerName")
            }
            pendingIntentMap.clear()
        } else {
            if (BuildConfig.DEBUG) Log.d(tag, "No pending intents to cancel.")
        }
    }
    fun cancelAllSilentModes() {
        val workManager = WorkManager.getInstance(context)
        try {
            fun cancelWorkersByTag(tag: String) {
                try {
                    val workers = workManager.getWorkInfosByTag(tag).get()
                    workers.forEach { workInfo ->
                        if (workInfo.state == WorkInfo.State.ENQUEUED || workInfo.state == WorkInfo.State.RUNNING) {
                            workManager.cancelWorkById(workInfo.id)
                            if (BuildConfig.DEBUG) Log.d("MainActivity", "Canceled worker with tag: $tag, ID: ${workInfo.id}")
                        }
                    }
                } catch (e: Exception) {
                    Log.e(tag, "Error while canceling workers with tag: $tag. Error: ${e.message}",e)
                }
            }
            cancelWorkersByTag("DailyWorker")
            for (prayer in prayerNames) {
                val startTag = "SilentModeWorker_${prayer}_Start"
                val endTag = "SilentModeWorker_${prayer}_End"
                cancelWorkersByTag(startTag)
                cancelWorkersByTag(endTag)
            }
            if (BuildConfig.DEBUG) Log.d(tag, "All silent mode workers have been canceled successfully.")
        } catch (e: Exception) {
            Log.e(tag, "Error while canceling silent mode workers: ${e.message}", e)
        }
    }
    private fun getMethodId(index: Int): Int {
        return when (index) {
            0 -> 4
            1 -> 3
            2 -> 2
            3 -> 5
            4 -> 1
            5 -> 8
            6 -> 9
            7 -> 10
            8 -> 11
            9 -> 12
            10 -> 13
            11 -> 14
            12 -> 15
            13 -> 16
            14 -> 17
            15 -> 18
            16 -> 19
            17 -> 20
            18 -> 21
            19 -> 22
            20 -> 23
            21 -> 7
            22 -> 0
            else -> 4
        }
    }
    private fun getDurationId(index: Int): Int {
        return when (index) {
            0 -> 15
            1 -> 20
            2 -> 25
            3 -> 30
            4 -> 35
            5 -> 40
            6 -> 45
            7 -> 50
            8 -> 55
            9 -> 60
            else -> 3
        }
    }
    private fun getBeforeJumuaDurationId(index: Int): Int {
        return when (index) {
            0 -> 0
            1 -> 10
            2 -> 15
            3 -> 20
            4 -> 25
            5 -> 30
            6 -> 35
            7 -> 40
            8 -> 45
            9 -> 50
            10 -> 55
            11 -> 60
            else -> 0
        }
    }
    private fun getAfterJumuaDurationId(index: Int): Int {
        return when (index) {
            0 -> 15
            1 -> 20
            2 -> 25
            3 -> 30
            4 -> 35
            5 -> 40
            6 -> 45
            7 -> 50
            8 -> 55
            9 -> 60
            else -> 3
        }
    }
    private fun getTaraweehDurationId(index: Int): Int {
        return when (index) {
            0 -> 20
            1 -> 30
            2 -> 40
            3 -> 50
            4 -> 60
            5 -> 90
            6 -> 120
            7 -> 150
            8 -> 180
            else -> 4
        }
    }
    private fun getTahajjudDurationId(index: Int): Int {
        return when (index) {
            0 -> 0
            1 -> 15
            2 -> 30
            3 -> 45
            4 -> 60
            5 -> 90
            6 -> 120
            7 -> 150
            8 -> 180
            else -> 0
        }
    }
    private fun getPrayerNameResId(prayerName: String): Int {
        return prayerNameMap[prayerName] ?: R.string.prayer_name_unknown
    }
    fun getLocalizedPrayerName(prayerName: String): String {
        val prayerNameResId = getPrayerNameResId(prayerName)
        return LocaleHelper.getLocalizedString(context, prayerNameResId)
    }
    fun isInternetAvailable(): Boolean {
        val connectivityManager = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val network = connectivityManager.activeNetwork ?: return false
        val capabilities = connectivityManager.getNetworkCapabilities(network) ?: return false

        return capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
                capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED) &&
                !capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_CAPTIVE_PORTAL) &&
                (capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) ||
                        capabilities.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) ||
                        capabilities.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET))
    }
    private fun getCurrentWeekday(): String {
        val calendar = Calendar.getInstance()
        val dateFormat = SimpleDateFormat("EEEE", Locale.US)
        return dateFormat.format(calendar.time)
    }
    fun isInCall(): Boolean {
        val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        return audioManager.mode == AudioManager.MODE_IN_CALL || audioManager.mode == AudioManager.MODE_IN_COMMUNICATION
    }
    fun createLoadingDialog(activity: Activity): Dialog {
        val builder = AlertDialog.Builder(activity)
        val view = LayoutInflater.from(activity).inflate(R.layout.dialog_loading_progress, null)
        builder.setView(view)
        val dialog = builder.create()
        dialog.window?.setBackgroundDrawableResource(android.R.color.transparent)
        dialog.setCanceledOnTouchOutside(false)
        dialog.setCancelable(false)
        return dialog
    }
    fun hideNavigationBarIfNeeded(activity: AppCompatActivity) {
        if (Build.VERSION.SDK_INT in Build.VERSION_CODES.Q..Build.VERSION_CODES.S_V2) {
            val windowInsetsController = WindowCompat.getInsetsController(activity.window, activity.window.decorView)
            windowInsetsController.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            windowInsetsController.hide(WindowInsetsCompat.Type.navigationBars())
        }
    }
}