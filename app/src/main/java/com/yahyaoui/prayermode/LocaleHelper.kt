package com.yahyaoui.prayermode

import android.content.Context
import android.content.res.Configuration
import android.content.res.Resources
import android.util.Log
import android.view.View
import android.view.Window
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale
import android.text.format.DateFormat

object LocaleHelper {
    private const val TAG = "LocaleHelper"
    private const val EASTERN_ARABIC_OFFSET = 1584
    fun setLocale(context: Context, localeTag: String): Context {
//        if (BuildConfig.DEBUG) Log.d(TAG, "Attempting to set app locale to: $localeTag")

        SharedHelper(context).saveLocale(localeTag)
        val locale = Locale.forLanguageTag(localeTag)
        Locale.setDefault(locale)

        val config = Configuration(context.resources.configuration)
        config.setLocale(locale)
        config.setLayoutDirection(locale)
        val newContext = context.createConfigurationContext(config)
//        if (BuildConfig.DEBUG) Log.d(TAG, "Actual locale applied to new context: ${newContext.resources.configuration.locales[0]}")
        return newContext
    }
    fun getPersistedLocale(): String {
        val systemLocale: Locale = Resources.getSystem().configuration.locales[0]
        val localeTag = systemLocale.toLanguageTag()
        if (BuildConfig.DEBUG) Log.d(TAG, "System locale BCP 47 tag: $localeTag")
        val finalLocaleTag = when {
            localeTag == "in" || localeTag.startsWith("in-") -> "id"
            localeTag.matches(Regex("ar-(MA|TN|DZ|LY|MR|LB).*")) -> "ar-MA"
            localeTag.startsWith("ur-PK") -> "ur-PK"
            else -> localeTag.substringBefore("-")
        }
//        if (BuildConfig.DEBUG) Log.d(TAG, "Mapped locale tag for resources: $finalLocaleTag")
        return finalLocaleTag
    }
    fun getLanguageDisplayName(context: Context, localeTag: String): String {
        val languageCodes = context.resources.getStringArray(R.array.language_codes)
        val languageNames = context.resources.getStringArray(R.array.language_names)
        val index = languageCodes.indexOf(localeTag)
        return if (index != -1) languageNames[index] else languageNames[0]
    }
    fun setupLayoutDirection(context: Context, window: Window) {
        val sharedHelper = SharedHelper(context)
        val currentLocale = sharedHelper.getSavedLocale() ?: getPersistedLocale()
        if (BuildConfig.DEBUG) Log.d(TAG, "Setting layout direction for locale: $currentLocale")

        if (currentLocale == "ar" || currentLocale == "ur") {
            window.decorView.layoutDirection = View.LAYOUT_DIRECTION_RTL
//            if (BuildConfig.DEBUG) Log.d(TAG, "Setting RTL layout direction for locale: $currentLocale")
        } else {
            window.decorView.layoutDirection = View.LAYOUT_DIRECTION_LTR
//            if (BuildConfig.DEBUG) Log.d(TAG, "Setting LTR layout direction for locale: $currentLocale")
        }
    }
    fun getLocalizedString(context: Context, stringResId: Int, vararg formatArgs: Any): String {
        val sharedHelper = SharedHelper(context)
        val savedLocale = sharedHelper.getSavedLocale() ?: getPersistedLocale()
        val localizedContext = setLocale(context, savedLocale)
        return localizedContext.getString(stringResId, *formatArgs)
    }
    fun formatTimeForNotification(context: Context, calendar: Calendar): String {
        val timeFormatPattern = if (DateFormat.is24HourFormat(context)) "HH:mm" else "hh:mm a"
        val timeFormatter = SimpleDateFormat(timeFormatPattern, Locale.ENGLISH)
        val formattedTime = timeFormatter.format(calendar.time)
        return formatWithLocaleNumerals(context, formattedTime)
    }
    private fun shouldUseEasternArabicNumerals(savedLocale: String): Boolean {
        return when {
            savedLocale == "ar" || savedLocale == "ur" -> true
            savedLocale.startsWith("ar-") && !savedLocale.contains("MA|TN|DZ|LY|MR|LB".toRegex()) -> true
            else -> false
        }
    }
    private fun getSavedLocale(context: Context): String {
        return SharedHelper(context).getSavedLocale() ?: Locale.getDefault().toLanguageTag()
    }
    private fun formatWithLocaleNumerals(context: Context, input: String): String {
        val savedLocale = getSavedLocale(context)
        return if (shouldUseEasternArabicNumerals(savedLocale)) {
            convertToEasternArabicNumerals(input)
        } else {
            input
        }
    }
    private fun convertToEasternArabicNumerals(text: String): String {
        return buildString {
            text.forEach { char ->
                append(
                    when (char) {
                        in '0'..'9' -> (char.code + EASTERN_ARABIC_OFFSET).toChar()
                        else -> char
                    }
                )
            }
        }
    }
    fun formatStringWithLocaleNumerals(context: Context, input: String): String {
        return formatWithLocaleNumerals(context, input)
    }
}