package com.yahyaoui.prayermode

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.coroutines.CoroutineContext
class LocationService : Service(), CoroutineScope {
    override val coroutineContext: CoroutineContext = Dispatchers.IO + Job()
    private val channelId = "location_service_channel"
    private val notificationId = 1202
    private val tag = "LocationService"
    private val tools: Tools by lazy { Tools(applicationContext) }
    private val sharedHelper: SharedHelper by lazy { SharedHelper(applicationContext) }
    private val locationMutex = Mutex()
    private lateinit var locationManager: LocationManager
    private lateinit var activeLocationListener: LocationListener
    companion object {
        const val SIGNIFICANT_DISPLACEMENT_KM = 25f
        const val MIN_LOCATION_ACCURACY_METERS = 1500f
        const val MAX_LOCATION_AGE_MS = 30 * 60000L
        const val SELF_CHECK_INTERVAL_MS = 15 * 60000L
        const val ACTIVE_REQUEST_TIMEOUT_MS = 60000L
        const val PREF_LAST_FETCH_LATITUDE = "last_fetch_latitude"
        const val PREF_LAST_FETCH_LONGITUDE = "last_fetch_longitude"
        const val PREF_LAST_FETCH_TIME_MS = "last_fetch_time_ms"
        const val PREF_LAST_LOCATION_UPDATE_TIME = "last_location_update_time"
        private const val LOCATION_UPDATE_INTERVAL_MS = 20 * 60000L
        private const val LOCATION_UPDATE_DISTANCE_METERS = 1000f
        fun start(context: Context) {
            if (BuildConfig.DEBUG) Log.d("LocationService", "LocationService started")
            val serviceIntent = Intent(context, LocationService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) ContextCompat.startForegroundService(context, serviceIntent)
            else context.startService(serviceIntent)
        }
        fun stop(context: Context) {
            if (BuildConfig.DEBUG) Log.d("LocationService", "LocationService stopped")
            val serviceIntent = Intent(context, LocationService::class.java)
            context.stopService(serviceIntent)
        }
    }
    private val providerReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action == LocationManager.PROVIDERS_CHANGED_ACTION && isLocationEnabled()) refreshLocationRequest()
        }
    }
    private val selfCheckHandler = Handler(Looper.getMainLooper())
    private val selfCheckRunnable = object : Runnable {
        override fun run() {
            val lastUpdate = sharedHelper.getLong(PREF_LAST_LOCATION_UPDATE_TIME, 0L)
            val timeSinceLastUpdate = System.currentTimeMillis() - lastUpdate
            val timeSinceLastTravel = System.currentTimeMillis() - sharedHelper.getLong("last_travel_time", 0)
            val checkInterval = if (timeSinceLastTravel > 24 * 60 * 60000L) { 30 * 60000L }
            else { SELF_CHECK_INTERVAL_MS }

            if (timeSinceLastUpdate > checkInterval) {
                if (BuildConfig.DEBUG) Log.d(tag, "Self-check triggered: No valid location for ${timeSinceLastUpdate/60000} min (threshold: ${checkInterval/60000} min). Requesting active update.")
                refreshLocationRequest()
            }
            selfCheckHandler.postDelayed(this, checkInterval)
        }
    }

    override fun onCreate() {
        super.onCreate()
        locationManager = getSystemService(LOCATION_SERVICE) as LocationManager
        if (sharedHelper.getLong("last_travel_time", 0L) == 0L) sharedHelper.saveLong("last_travel_time", System.currentTimeMillis())

        initLocationListeners()
        createNotificationChannel()
        startForeground(notificationId, createForegroundNotification())
        val filter = IntentFilter(LocationManager.PROVIDERS_CHANGED_ACTION)
        registerReceiver(providerReceiver, filter)
        refreshLocationRequest()
        selfCheckHandler.postDelayed(selfCheckRunnable, SELF_CHECK_INTERVAL_MS)
        if (BuildConfig.DEBUG) Log.d(tag, "Location Service created")
    }
    private fun initLocationListeners() {
        activeLocationListener = object : LocationListener {
            override fun onLocationChanged(location: Location) {
                processIncomingLocation(location)
            }
            override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) {}
            override fun onProviderEnabled(provider: String) {
                if (BuildConfig.DEBUG) Log.d(tag, "Provider enabled: $provider")
            }
            override fun onProviderDisabled(provider: String) {
                if (BuildConfig.DEBUG) Log.d(tag, "Provider disabled: $provider")
            }
        }
    }
    private fun processIncomingLocation(location: Location) {
        if (!isLocationValid(location)) {
            if (BuildConfig.DEBUG) Log.d(tag, "Rejecting location: Age=${(System.currentTimeMillis() - location.time)/1000}s, " + "Acc=${location.accuracy}m")
            return
        }

        sharedHelper.saveLong(PREF_LAST_LOCATION_UPDATE_TIME, System.currentTimeMillis())

        if (location.accuracy <= 100f) {
            stopActiveProviders()
            if (BuildConfig.DEBUG) Log.d(tag, "Location accuracy is under 100m, stopping active provider...")
        }

        launch {
            locationMutex.withLock {
                processLocationUpdate(location)
            }
        }
    }
    private fun refreshLocationRequest() {
        if (!isLocationEnabled()) {
            if (BuildConfig.DEBUG) Log.w(tag, "Location disabled, skipping active request")
            return
        }
        val hasFineLocation = ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
        val hasCoarseLocation = ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED
        if (!hasFineLocation && !hasCoarseLocation) {
            if (BuildConfig.DEBUG) Log.w(tag, "No location permissions, stopping service")
            stopSelf()
            return
        }

        try {
            locationManager.removeUpdates(activeLocationListener)

            val providers = mutableListOf<String>()
            if (hasCoarseLocation && locationManager.isProviderEnabled(LocationManager.NETWORK_PROVIDER)) {
                providers.add(LocationManager.NETWORK_PROVIDER)
            }
            if (hasFineLocation && locationManager.isProviderEnabled(LocationManager.GPS_PROVIDER)) {
                providers.add(LocationManager.GPS_PROVIDER)
            }

            for (provider in providers) {
                locationManager.requestLocationUpdates(provider, LOCATION_UPDATE_INTERVAL_MS, LOCATION_UPDATE_DISTANCE_METERS, activeLocationListener, Looper.getMainLooper())
                if (BuildConfig.DEBUG) Log.d(tag, "Active updates requested for: $provider (interval: $LOCATION_UPDATE_INTERVAL_MS ms, distance: $LOCATION_UPDATE_DISTANCE_METERS m)")
            }

           Handler(Looper.getMainLooper()).postDelayed({
                stopActiveProviders()
            }, ACTIVE_REQUEST_TIMEOUT_MS)

        } catch (e: SecurityException) {
            Log.e(tag, "Security exception requesting location updates", e)
            stopSelf()
        } catch (e: IllegalArgumentException) {
            Log.e(tag, "Illegal argument requesting location updates", e)
        } catch (e: Exception) {
            Log.e(tag, "Error requesting location updates", e)
        }
    }
    private fun stopActiveProviders() {
        try {
            locationManager.removeUpdates(activeLocationListener)
            if (BuildConfig.DEBUG) Log.d(tag, "Active providers stopped")
        } catch (e: Exception) {
            Log.e(tag, "Error stopping active providers", e)
        }
    }
    private suspend fun processLocationUpdate(location: Location) {
        val lastLat = sharedHelper.getDouble(PREF_LAST_FETCH_LATITUDE, Double.NaN)
        val lastLon = sharedHelper.getDouble(PREF_LAST_FETCH_LONGITUDE, Double.NaN)

        if (lastLat.isNaN() || lastLon.isNaN()) {
            saveInitialLocation(location)
            return
        }

        val lastFetchLocation = Location("").apply {
            latitude = lastLat
            longitude = lastLon
        }

        val displacementKm = lastFetchLocation.distanceTo(location) / 1000

        if (BuildConfig.DEBUG) {
            Log.d(tag, "--- Location Update Analysis ---")
            Log.d(tag, "New Location: Lat=${"%.4f".format(location.latitude)}, " + "Lon=${"%.4f".format(location.longitude)}, " + "Acc=${"%.0f".format(location.accuracy)}m")
            Log.d(tag, "Last Fetch Location: Lat=${"%.4f".format(lastLat)}, " + "Lon=${"%.4f".format(lastLon)}")
            Log.d(tag, "Displacement: ${"%.1f".format(displacementKm)} km, Threshold: $SIGNIFICANT_DISPLACEMENT_KM km")
            Log.d(tag, "COMPARISON: New(${location.latitude}, ${location.longitude}) " + "vs Saved($lastLat, $lastLon)")
            Log.d(tag, "--- End Analysis ---")
        }

        if (displacementKm >= SIGNIFICANT_DISPLACEMENT_KM) {
            if (BuildConfig.DEBUG) Log.i(tag, "Significant displacement detected (${"%.1f".format(displacementKm)} km). Triggering fetch.")
            triggerPrayerTimesFetch(location, displacementKm)
        } else if (BuildConfig.DEBUG) {
            Log.d(tag, "Displacement (${"%.1f".format(displacementKm)} km) below threshold")
        }
    }
    private fun saveInitialLocation(location: Location) {
        launch {
            sharedHelper.saveDouble(PREF_LAST_FETCH_LATITUDE, location.latitude)
            sharedHelper.saveDouble(PREF_LAST_FETCH_LONGITUDE, location.longitude)
            sharedHelper.saveLong(PREF_LAST_FETCH_TIME_MS, System.currentTimeMillis())
            if (BuildConfig.DEBUG) Log.d(tag, "Initial prayer location saved")
        }
    }
    private suspend fun triggerPrayerTimesFetch(currentLocation: Location, distance: Float) {
        if (!sharedHelper.getSwitchState()) {
            if (BuildConfig.DEBUG) Log.i(tag, "Skipping fetch: main switch is off.")
            return
        }

        if (BuildConfig.DEBUG) {
            Log.d(tag, "triggerPrayerTimesFetch called with location: " +
                    "${currentLocation.latitude}, ${currentLocation.longitude}")
            Log.d(tag, "Saved location before fetch: " +
                    "${sharedHelper.getDouble(PREF_LAST_FETCH_LATITUDE, 0.0)}, " +
                    "${sharedHelper.getDouble(PREF_LAST_FETCH_LONGITUDE, 0.0)}")
        }

        try {
            val selectedMethodIndex = sharedHelper.getIntValue(SharedHelper.SELECTED_METHOD_RES_ID, 0)
            val finalMethodIndex = if (selectedMethodIndex != -1) selectedMethodIndex else 4
            if (BuildConfig.DEBUG) Log.i(tag, "Fetching prayer times for displacement of ${"%.1f".format(distance)} km, " + "method index: $finalMethodIndex")
            val success = tools.findLocation(finalMethodIndex)

            if (success) {
                if (distance>0) {
//                    val formattedDistance = LocaleHelper.formatNumberForNotification(this@LocationService, distance, "%.1f")
                    NotificationHelper.sendNotification(this@LocationService, R.string.location_title, R.string.travelled_distance, 333, "")
                    sharedHelper.saveLong("last_travel_time", System.currentTimeMillis())
                }
                tools.exitSilentMode()
                tools.cancelAllSilentModes()
                tools.cancelScheduledSilentMode()
                AlarmScheduler(applicationContext).scheduleDailyAlarm()

                if (BuildConfig.DEBUG) Log.d(tag, "Fetch successful. Saving new location: " + "${currentLocation.latitude}, ${currentLocation.longitude}")
                sharedHelper.saveDouble(PREF_LAST_FETCH_LATITUDE, currentLocation.latitude)
                sharedHelper.saveDouble(PREF_LAST_FETCH_LONGITUDE, currentLocation.longitude)
                sharedHelper.saveLong(PREF_LAST_FETCH_TIME_MS, System.currentTimeMillis())
                stopActiveProviders()
                if (BuildConfig.DEBUG) Log.i(tag, "Prayer times successfully updated for new location")
            } else Log.e(tag, "Failed to fetch prayer times for new location")
        } catch (e: Exception) {
            Log.e(tag, "Error in triggerPrayerTimesFetch: ${e.message}", e)
        }
    }
    private fun isLocationValid(location: Location): Boolean {
        val locationAge = System.currentTimeMillis() - location.time
        val isFresh = locationAge <= MAX_LOCATION_AGE_MS
        val isAccurate = location.accuracy <= MIN_LOCATION_ACCURACY_METERS

        return isFresh && isAccurate
    }
    private fun isLocationEnabled(): Boolean {
        return locationManager.isProviderEnabled(LocationManager.GPS_PROVIDER) ||
                locationManager.isProviderEnabled(LocationManager.NETWORK_PROVIDER)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (BuildConfig.DEBUG) Log.d(tag, "Service started with flags: $flags")
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        try {
            unregisterReceiver(providerReceiver)
            selfCheckHandler.removeCallbacks(selfCheckRunnable)
            locationManager.removeUpdates(activeLocationListener)
            coroutineContext.cancel()

            if (BuildConfig.DEBUG) Log.d(tag, "LocationService destroyed")
        } catch (e: Exception) {
            Log.e(tag, "Error during service destruction: ${e.message}")
        }
        super.onDestroy()
    }
    private fun createForegroundNotification(): android.app.Notification {
        val notificationIntent = Intent(this, MainActivity::class.java)
        val pendingIntent = PendingIntent.getActivity(this, 0, notificationIntent, PendingIntent.FLAG_IMMUTABLE)
        val title = LocaleHelper.getLocalizedString(this, R.string.location_title)
        val contentText = LocaleHelper.getLocalizedString(this, R.string.getting_location_update)

        return NotificationCompat.Builder(this, channelId)
            .setContentTitle(title)
            .setContentText(contentText)
            .setSmallIcon(R.drawable.ic_prayer_mat_vector)
            .setContentIntent(pendingIntent)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_MIN)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setVisibility(NotificationCompat.VISIBILITY_SECRET)
            .setShowWhen(false)
            .build()
    }
    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channelName = LocaleHelper.getLocalizedString(this, R.string.location_title)
            val channel = NotificationChannel(channelId, channelName, NotificationManager.IMPORTANCE_MIN).apply {
                description = "Background location service for prayer time updates"
                setShowBadge(false)
                enableVibration(false)
                setSound(null, null)
                lockscreenVisibility = NotificationCompat.VISIBILITY_SECRET
            }
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(channel)
            if (BuildConfig.DEBUG) Log.d(tag, "Notification channel created: $channelId")
        }
    }
}