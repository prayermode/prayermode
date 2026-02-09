package com.yahyaoui.prayermode

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import androidx.core.content.edit

class SharedHelper(private val context: Context) {
    private val sharedPreferences: SharedPreferences = context.getSharedPreferences("com.yahyaoui.prayermode.preferences", Context.MODE_PRIVATE)
    private val tag = "SharedHelper"

    companion object {
        const val SWITCH_STATE_KEY = "switch_state"
        const val SELECTED_METHOD_RES_ID = "selected_method_res_id"
        const val LAST_CHECKED_METHOD_INDEX = "last_checked_method_index"
        const val DURATION_FAJR = "duration_fajr"
        const val DURATION_DHUHR = "duration_dhuhr"
        const val DURATION_ASR = "duration_asr"
        const val DURATION_MAGHRIB = "duration_maghrib"
        const val DURATION_ISHA = "duration_isha"
        const val DURATION_BEFORE_JUMUA = "DurationBeforeJumua"
        const val DURATION_AFTER_JUMUA = "DurationAfterJumua"
        const val DURATION_TARAWEEH = "DurationTaraweeh"
        const val DURATION_TAHAJJUD = "DurationTahajjud"
        const val SELECTED_TIME_EID = "Selected_time_eid"
        const val DURATION_EID = "DurationEid"
        const val KEY_TERMS_ACCEPTED = "terms_accepted"
        const val IS_APP_CONTROLLED_DND_ACTIVE = "is_app_controlled_dnd_active"
        const val AUDIO_SWITCH_STATE_KEY = "audio_switch_state"
        const val KEY_SELECTED_LOCALE = "selected_locale"
        const val ABLUTION_SWITCH_STATE_KEY = "ablution_switch_state"
        const val LAST_LAUNCH_VERSION = "last_launch_version"
    }
    fun saveSwitchState(state: Boolean) {
        sharedPreferences.edit { putBoolean(SWITCH_STATE_KEY, state) }
    }
    fun getSwitchState(): Boolean {
        return sharedPreferences.getBoolean(SWITCH_STATE_KEY, false)
    }
    fun saveAudioSwitchState(state: Boolean) {
        sharedPreferences.edit { putBoolean(AUDIO_SWITCH_STATE_KEY, state) }
    }
    fun getAudioSwitchState(): Boolean {
        return sharedPreferences.getBoolean(AUDIO_SWITCH_STATE_KEY, false)
    }
    fun saveAblutionSwitchState(state: Boolean) {
        sharedPreferences.edit { putBoolean(ABLUTION_SWITCH_STATE_KEY, state) }
    }

    fun getAblutionSwitchState(): Boolean {
        return sharedPreferences.getBoolean(ABLUTION_SWITCH_STATE_KEY, false)
    }
    fun saveIntValue(key: String, value: Int) {
        sharedPreferences.edit {putInt(key, value)}
        if (BuildConfig.DEBUG) Log.d(tag, "Saving value: $value with key: $key")
    }
    fun getIntValue(key: String, defaultValue: Int): Int {
        val value = sharedPreferences.getInt(key, defaultValue)
        if (BuildConfig.DEBUG) Log.d(tag, "Retrieving value: $value with key: $key")
        return value
    }
    fun getStringFromArray(arrayResId: Int, indexKey: String, defaultIndex: Int): String {
        val array = context.resources.getStringArray(arrayResId)
        val index = getIntValue(indexKey, defaultIndex)
        return array.getOrNull(index) ?: array[defaultIndex]
    }
    fun saveLastCheckedMethodIndex(methodIndex: Int) {
        sharedPreferences.edit { putInt(LAST_CHECKED_METHOD_INDEX, methodIndex)}
    }
    fun getLastCheckedMethodIndex(): Int {
        return sharedPreferences.getInt(LAST_CHECKED_METHOD_INDEX, -1)
    }
    fun saveDouble(key: String, value: Double) {
        sharedPreferences.edit { putLong(key, java.lang.Double.doubleToRawLongBits(value))}
    }
    fun getDouble(key: String, defaultValue: Double = Double.NaN): Double {
        return java.lang.Double.longBitsToDouble(sharedPreferences.getLong(key, java.lang.Double.doubleToRawLongBits(defaultValue)))
    }
    fun saveLong(key: String, value: Long) {
        sharedPreferences.edit { putLong(key, value) }
    }
    fun getLong(key: String, defaultValue: Long): Long {
        return sharedPreferences.getLong(key, defaultValue)
    }
    fun saveTermsAccepted(accepted: Boolean) {
        sharedPreferences.edit { putBoolean(KEY_TERMS_ACCEPTED, accepted) }
        if (BuildConfig.DEBUG) Log.d(tag, "Saving terms_accepted: $accepted")
    }
    fun getTermsAccepted(): Boolean {
        val accepted = sharedPreferences.getBoolean(KEY_TERMS_ACCEPTED, false)
        if (BuildConfig.DEBUG) Log.d(tag, "Retrieving terms_accepted: $accepted")
        return accepted
    }
    fun setPermissionsSnackbarShown(shown: Boolean) {
        sharedPreferences.edit { putBoolean("PERMISSIONS_SNACKBAR_SHOWN", shown) }
    }
    fun isPermissionsSnackbarShown(): Boolean {
        return sharedPreferences.getBoolean("PERMISSIONS_SNACKBAR_SHOWN", false)
    }
    fun saveBoolean(key: String, value: Boolean) {
        sharedPreferences.edit { putBoolean(key, value) }
    }
    fun getBoolean(key: String, defaultValue: Boolean): Boolean {
        return sharedPreferences.getBoolean(key, defaultValue)
    }
    fun saveLocale(localeTag: String) {
        sharedPreferences.edit { putString(KEY_SELECTED_LOCALE, localeTag) }
    }
    fun getSavedLocale(): String? {
        val locale = sharedPreferences.getString(KEY_SELECTED_LOCALE, null)
        return locale
    }
    fun saveLastLaunchVersion(version: Int) {
        sharedPreferences.edit { putInt(LAST_LAUNCH_VERSION, version) }
    }

    fun getLastLaunchVersion(): Int {
        return sharedPreferences.getInt(LAST_LAUNCH_VERSION, 0)
    }
}