package com.yahyaoui.prayermode

import android.content.Context
import android.location.Address
import android.location.Geocoder
import android.location.Location
import android.os.Build
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.io.IOException
import java.util.Locale
import kotlin.coroutines.resume
object GeocodingHelper {
    private fun Address.getLocationName(): String? {
        return locality ?: adminArea ?: countryName
    }
    private const val TAG = "GeocodingHelper"

    suspend fun getLocationName(context: Context, location: Location?): String? {
        if (location == null) return null
        return withContext(Dispatchers.IO) {
            try {
                val sharedHelper = SharedHelper(context)
                val savedLocale = sharedHelper.getSavedLocale()
                val appLocale = if (!savedLocale.isNullOrEmpty()) {
                    Locale.forLanguageTag(savedLocale)
                } else Locale.getDefault()

                if (BuildConfig.DEBUG) Log.d(TAG, "Requested locale for Geocoder: $appLocale (language: ${appLocale.language}, country: ${appLocale.country})")
                val geocoder = Geocoder(context, appLocale)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    suspendCancellableCoroutine { continuation ->
                        geocoder.getFromLocation(location.latitude, location.longitude, 1) { addresses ->
                            continuation.resume(addresses.firstOrNull()?.getLocationName())
                        }
                    }
                } else {
                    @Suppress("DEPRECATION")
                    geocoder.getFromLocation(location.latitude, location.longitude, 1)?.firstOrNull()?.getLocationName()
                }
            } catch (e: IOException) {
                Log.e(TAG, "Network error: ${e.message}")
                null
            } catch (e: IllegalArgumentException) {
                Log.e(TAG, "Invalid coordinates: ${e.message}")
                null
            } catch (e: Exception) {
                Log.e(TAG, "Unexpected error: ${e.message}")
                null
            }
        }
    }
}