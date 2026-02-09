package com.yahyaoui.prayermode

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.provider.Settings
import android.util.Log
import android.view.View
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.net.toUri
import com.google.android.material.card.MaterialCardView
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.yahyaoui.prayermode.TermsAndConditions.Companion.TERMS_URL
class SettingsActivity : AppCompatActivity() {
    private val sharedHelper: SharedHelper by lazy { SharedHelper(this) }
    private lateinit var tvCurrentLanguage: TextView
    private lateinit var tvBatteryOptimizationStatus: TextView
    private val tag = "SettingsActivity"
    private val languageSelectionLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == RESULT_OK) {
            val selectedIndex = result.data?.getIntExtra("SELECTED_INDEX", -1) ?: -1
            if (selectedIndex != -1) {
                val languageCodes = resources.getStringArray(R.array.language_codes)
                val selectedLocale = languageCodes[selectedIndex]

                LocaleHelper.setLocale(this, selectedLocale)
                recreate()

                Handler(Looper.getMainLooper()).postDelayed({
                    val intent = Intent(this, MainActivity::class.java).apply {
                        flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_NEW_TASK
                    }
                    startActivity(intent)
                    finish()
                }, 100)
            }
        }
    }
    private val batterySettingsLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        Handler(Looper.getMainLooper()).postDelayed({
            updateBatteryOptimizationStatus()
        }, 1000)
    }

    override fun attachBaseContext(newBase: Context) {
        val savedLocale = SharedHelper(newBase).getSavedLocale()
        val locale = savedLocale ?: LocaleHelper.getPersistedLocale()
        super.attachBaseContext(LocaleHelper.setLocale(newBase, locale))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings)
        LocaleHelper.setupLayoutDirection(this, window)

        tvCurrentLanguage = findViewById(R.id.tvCurrentLanguage)
        tvBatteryOptimizationStatus = findViewById(R.id.tvBatteryOptimizationStatus)

        findViewById<View>(R.id.settingsBackButton).setOnClickListener {
            finish()
        }

        setupContainer(R.id.privacyContainer, R.string.privacy_notice, R.string.privacy_content, "PRIVACY")
        setupContainer(R.id.permissionsContainer, R.string.permission, R.string.permissions_content, "PERMISSIONS")
        setupContainer(R.id.helpContainer, R.string.help, R.string.bulb_content, "HELP")
        setupContainer(R.id.donationContainer, R.string.donation, R.string.donation_content, "DONATION")

        findViewById<MaterialCardView>(R.id.termsContainer).setOnClickListener {
            val intent = Intent(Intent.ACTION_VIEW, TERMS_URL.toUri())
            startActivity(intent)
        }

        findViewById<MaterialCardView>(R.id.languageContainer).setOnClickListener {
            val languageNames = resources.getStringArray(R.array.language_names)
            val languageCodes = resources.getStringArray(R.array.language_codes)
            val currentLocale = sharedHelper.getSavedLocale() ?: LocaleHelper.getPersistedLocale()
            val currentIndex = languageCodes.indexOf(currentLocale).takeIf { it != -1 } ?: 0

            val intent = Intent(this, SelectionActivity::class.java).apply {
                putExtra("TITLE", getString(R.string.select_language))
                putExtra("OPTIONS", languageNames)
                putExtra("SELECTED_INDEX_KEY", "language_selection")
                putExtra("DEFAULT_INDEX", currentIndex)
            }
            languageSelectionLauncher.launch(intent)
        }

        findViewById<MaterialCardView>(R.id.batteryOptimizationContainer).setOnClickListener {
            showBatteryOptimizationDialog()
        }

        updateLanguageDisplay()
        updateBatteryOptimizationStatus()
    }

    override fun onResume() {
        super.onResume()
        updateLanguageDisplay()
        updateBatteryOptimizationStatus()
    }
    private fun updateLanguageDisplay() {
        val currentLocale = sharedHelper.getSavedLocale() ?: LocaleHelper.getPersistedLocale()
        tvCurrentLanguage.text = LocaleHelper.getLanguageDisplayName(this, currentLocale)
    }
    private fun updateBatteryOptimizationStatus() {
        val powerManager = getSystemService(POWER_SERVICE) as PowerManager
        val isIgnoring = powerManager.isIgnoringBatteryOptimizations(packageName)

        if (isIgnoring) {
            tvBatteryOptimizationStatus.text = getString(R.string.battery_optimization_enabled)
            tvBatteryOptimizationStatus.setTextColor(ContextCompat.getColor(this, android.R.color.holo_green_dark))
        } else {
            tvBatteryOptimizationStatus.text = getString(R.string.battery_optimization_active)
            tvBatteryOptimizationStatus.setTextColor(ContextCompat.getColor(this, android.R.color.holo_orange_dark))
        }
    }
    private fun showBatteryOptimizationDialog() {
        val powerManager = getSystemService(POWER_SERVICE) as PowerManager
        if (powerManager.isIgnoringBatteryOptimizations(packageName)) {
            MaterialAlertDialogBuilder(this)
                .setTitle(getString(R.string.battery_optimization_title))
                .setMessage(getString(R.string.battery_optimization_already_enabled))
                .setPositiveButton(getString(R.string.ok), null)
                .show()
            return
        }

        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.battery_optimization_dialog_title)
            .setMessage(R.string.battery_optimization_dialog_message)
            .setPositiveButton(R.string.open_settings) { _, _ ->
                openBatteryOptimizationSettings()
            }
            .setNegativeButton(R.string.battery_optimization_dialog_negative, null)
            .show()
    }
    private fun openBatteryOptimizationSettings() {
        try {
            val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                data = android.net.Uri.fromParts("package", packageName, null)
                flags = Intent.FLAG_ACTIVITY_NEW_TASK
            }
            batterySettingsLauncher.launch(intent)
            if (BuildConfig.DEBUG) Log.d(tag, "Opened app details for battery settings")
        } catch (e: Exception) {
            if (BuildConfig.DEBUG) Log.e(tag, "Failed to open app settings: ${e.message}", e)
            Toast.makeText(this, R.string.battery_optimization_error, Toast.LENGTH_SHORT).show()
        }
    }
    private fun setupContainer(containerId: Int, titleResId: Int, contentResId: Int, screenType: String) {
        findViewById<MaterialCardView>(containerId).setOnClickListener {
            val intent = Intent(this, InformationActivity::class.java).apply {
                putExtra("TITLE", getString(titleResId))
                putExtra("CONTENT", getString(contentResId))
                putExtra("SCREEN_TYPE", screenType)
            }
            startActivity(intent)
        }
    }
}