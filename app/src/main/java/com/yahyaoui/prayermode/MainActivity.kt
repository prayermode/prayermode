package com.yahyaoui.prayermode

import android.app.AlertDialog
import android.app.NotificationManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.util.Log
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.SwitchCompat
import android.os.Handler
import android.os.Looper
import android.view.View
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.google.android.material.snackbar.Snackbar
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import com.yahyaoui.prayermode.TermsAndConditions.TermsAndConditionsListener
import android.Manifest
import android.content.pm.ActivityInfo
import android.widget.ImageView
import android.widget.LinearLayout
import androidx.activity.enableEdgeToEdge
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding
import kotlinx.coroutines.withContext
import android.app.Dialog
private const val IS_APP_RESTARTED_KEY = "isAppRestarted"
data class ContainerConfig(
    val container: View,
    val titleResId: Int,
    val optionsResId: Int,
    val selectedIndexKey: String,
    val defaultIndex: Int,
    val launcherKey: String
)
data class DailyPrayerConfig(
    val prayerName: String,
    val container: View,
    val textView: TextView,
    val durationKey: String,
    val durationTitleResId: Int,
    val ablutionTitleResId: Int,
    val iqamaTitleResId: Int,
    val durationArrayResId: Int = R.array.silent_durations,
    val durationDefaultIndex: Int = 3
)
private enum class ChainStep { DURATION, ABLUTION, IQAMA }

class MainActivity : AppCompatActivity(), TermsAndConditionsListener, WelcomeDialog.WelcomeDialogListener {

    override fun attachBaseContext(newBase: Context) {
        val savedLocale = SharedHelper(newBase).getSavedLocale()
        val locale = savedLocale ?: LocaleHelper.getPersistedLocale()
        if (BuildConfig.DEBUG) Log.d(tag, "Attaching base context for locale: $locale")
        super.attachBaseContext(LocaleHelper.setLocale(newBase, locale))
    }
    private lateinit var activateSwitch: SwitchCompat
    private lateinit var tvSwitchState: TextView
    private lateinit var menuButton: ImageView
    private lateinit var audioSwitch: SwitchCompat
    private val textViewsMap = mutableMapOf<String, TextView>()
    private val tools: Tools by lazy { Tools(this) }
    private val sharedHelper: SharedHelper by lazy { SharedHelper(this) }
    private val permissionsHelper: PermissionsHelper by lazy { PermissionsHelper(this) }
    private val tag = "MainActivity"
    private var isAppRestarted = true
    private var isRestoringSwitchState = false
    private val locationLauncher = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) handleLocationGranted() else showLocationDenied()
    }
    private val notificationLauncher = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) handleNotificationGranted() else showNotificationDenied()
    }
    private val selectionLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == RESULT_OK) {
            result.data?.getIntExtra("SELECTED_INDEX", -1)?.let { selectedIndex ->
                result.data?.getStringExtra("SELECTED_INDEX_KEY")?.let { key ->
                    sharedHelper.saveIntValue(key, selectedIndex)
                    updateTextViewForKey(key)
                }
            }
        }
    }
    private val dndStateReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action == NotificationManager.ACTION_NOTIFICATION_POLICY_ACCESS_GRANTED_CHANGED) {
                checkDndAndUpdateSwitch()
            }
        }
    }
    private val containerConfigs by lazy { createContainerConfigs() }
    private val dailyPrayerConfigs by lazy { createDailyPrayerConfigs() }
    private var chainConfig: DailyPrayerConfig? = null
    private var chainStep: ChainStep? = null
    private val chainedSelectionLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        val config = chainConfig ?: return@registerForActivityResult
        if (result.resultCode == RESULT_OK) {
            when (chainStep) {
                ChainStep.DURATION -> launchAblutionSelection(config)
                ChainStep.ABLUTION -> {
                    val ablutionKey = sharedHelper.getAblutionKeyForPrayer(config.prayerName)
                    val ablutionOn = ablutionKey != null && sharedHelper.isAblutionEnabled(ablutionKey)
                    if (ablutionOn) {
                        val iqamaKey = sharedHelper.getIqamaKeyForPrayer(config.prayerName)
                        if (iqamaKey != null) sharedHelper.saveIntValue(iqamaKey, 0)
                    }
                    if (ablutionOn) finishChain(config) else launchIqamaSelection(config)
                }
                ChainStep.IQAMA -> {
                    val ablutionKey = sharedHelper.getAblutionKeyForPrayer(config.prayerName)
                    if (ablutionKey != null) sharedHelper.saveIntValue(ablutionKey, 0)
                    finishChain(config)
                }
                null -> finishChain(config)
            }
        } else {
            when (chainStep) {
                ChainStep.ABLUTION -> launchDurationSelection(config)
                ChainStep.IQAMA -> launchAblutionSelection(config)
                else -> finishChain(config)
            }
        }
    }
    private var loadingDialog: Dialog? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContentView(R.layout.activity_main)
        initializeViews()
        setupEdgeToEdge()
        LocaleHelper.setupLayoutDirection(this,window)

        if (!sharedHelper.getTermsAccepted()) showWelcomeDialog() else requestLocationPermission()
        tools.hideNavigationBarIfNeeded(this)
        updateAllTextViews()
        audioSwitch.isChecked = sharedHelper.getAudioSwitchState()
        setupListeners()
        isRestoringSwitchState = true
        activateSwitch.isChecked = sharedHelper.getSwitchState()
        isRestoringSwitchState = false
        NotificationHelper.cancelNotification(this,1001)

        if (savedInstanceState != null) {
            isAppRestarted = savedInstanceState.getBoolean(IS_APP_RESTARTED_KEY, true)
        }
        if (savedInstanceState == null) handleToggleSwitchIntent()
        checkForUpdateRefresh()
    }
    private fun initializeViews() {
        activateSwitch = findViewById(R.id.activateSwitch)
        tvSwitchState = findViewById(R.id.tvSwitchState)
        menuButton = findViewById(R.id.menuButton)
        audioSwitch = findViewById(R.id.audioSwitch)

        textViewsMap.apply {
            put(SharedHelper.SELECTED_METHOD_RES_ID, findViewById(R.id.tvCalculationMethod))
            put(SharedHelper.DURATION_BEFORE_JUMUA, findViewById(R.id.tvBeforeJumua))
            put(SharedHelper.DURATION_AFTER_JUMUA, findViewById(R.id.tvAfterJumua))
            put(SharedHelper.DURATION_TAHAJJUD, findViewById(R.id.tvTahajjud))
            put(SharedHelper.SELECTED_TIME_EID, findViewById(R.id.tvEidTime))
            put(SharedHelper.DURATION_EID, findViewById(R.id.tvEidDuration))
        }
    }
    private fun createContainerConfigs(): List<ContainerConfig> {
        return listOf(
            ContainerConfig(findViewById(R.id.calculationMethodsContainer), R.string.select_calculation_method, R.array.calculation_methods, SharedHelper.SELECTED_METHOD_RES_ID, 0, "calculation_method"),
            ContainerConfig(findViewById(R.id.beforeJumuaContainer), R.string.select_before_jumua_duration, R.array.before_jumua_duration, SharedHelper.DURATION_BEFORE_JUMUA, 0, "before_jumua"),
            ContainerConfig(findViewById(R.id.afterJumuaContainer), R.string.select_after_jumua_duration, R.array.after_jumua_duration, SharedHelper.DURATION_AFTER_JUMUA, 3, "after_jumua"),
            ContainerConfig(findViewById(R.id.tahajjudContainer), R.string.select_tahajjud_duration, R.array.tahajjud_duration, SharedHelper.DURATION_TAHAJJUD, 0, "tahajjud"),
            ContainerConfig(findViewById(R.id.eidTimeContainer), R.string.select_eid_time, R.array.eid_time, SharedHelper.SELECTED_TIME_EID, 0, "eid_time"),
            ContainerConfig(findViewById(R.id.eidDurationContainer), R.string.select_eid_duration, R.array.eid_duration, SharedHelper.DURATION_EID, 2, "eid_duration")
        )
    }
    private fun createDailyPrayerConfigs(): List<DailyPrayerConfig> {
        return listOf(
            DailyPrayerConfig("Fajr", findViewById(R.id.fajrContainer), findViewById(R.id.tvFajr), SharedHelper.DURATION_FAJR, R.string.select_fajr_duration, R.string.select_fajr_ablution, R.string.select_fajr_iqama),
            DailyPrayerConfig("Dhuhr", findViewById(R.id.dhuhrContainer), findViewById(R.id.tvDhuhr), SharedHelper.DURATION_DHUHR, R.string.select_dhuhr_duration, R.string.select_dhuhr_ablution, R.string.select_dhuhr_iqama),
            DailyPrayerConfig("Asr", findViewById(R.id.asrContainer), findViewById(R.id.tvAsr), SharedHelper.DURATION_ASR, R.string.select_asr_duration, R.string.select_asr_ablution, R.string.select_asr_iqama),
            DailyPrayerConfig("Maghrib", findViewById(R.id.maghribContainer), findViewById(R.id.tvMaghrib), SharedHelper.DURATION_MAGHRIB, R.string.select_maghrib_duration, R.string.select_maghrib_ablution, R.string.select_maghrib_iqama),
            DailyPrayerConfig("Isha", findViewById(R.id.ishaContainer), findViewById(R.id.tvIsha), SharedHelper.DURATION_ISHA, R.string.select_isha_duration, R.string.select_isha_ablution, R.string.select_isha_iqama),
            DailyPrayerConfig("Taraweeh", findViewById(R.id.taraweehContainer), findViewById(R.id.tvTaraweeh), SharedHelper.DURATION_TARAWEEH, R.string.select_taraweeh_duration, R.string.select_taraweeh_ablution, R.string.select_taraweeh_iqama, R.array.taraweeh_duration, 4)
        )
    }
    private fun launchDurationSelection(config: DailyPrayerConfig) {
        chainConfig = config
        chainStep = ChainStep.DURATION
        val intent = Intent(this, SelectionActivity::class.java).apply {
            putExtra("TITLE", getString(config.durationTitleResId))
            putExtra("OPTIONS", resources.getStringArray(config.durationArrayResId))
            putExtra("SELECTED_INDEX_KEY", config.durationKey)
            putExtra("DEFAULT_INDEX", config.durationDefaultIndex)
        }
        chainedSelectionLauncher.launch(intent)
    }
    private fun launchAblutionSelection(config: DailyPrayerConfig) {
        val ablutionKey = sharedHelper.getAblutionKeyForPrayer(config.prayerName) ?: return
        chainStep = ChainStep.ABLUTION
        val intent = Intent(this, SelectionActivity::class.java).apply {
            putExtra("TITLE", getString(config.ablutionTitleResId))
            putExtra("OPTIONS", resources.getStringArray(R.array.ablution_duration))
            putExtra("SELECTED_INDEX_KEY", ablutionKey)
            putExtra("DEFAULT_INDEX", 0)
        }
        chainedSelectionLauncher.launch(intent)
    }
    private fun launchIqamaSelection(config: DailyPrayerConfig) {
        val iqamaKey = sharedHelper.getIqamaKeyForPrayer(config.prayerName) ?: return
        chainStep = ChainStep.IQAMA
        val intent = Intent(this, SelectionActivity::class.java).apply {
            putExtra("TITLE", getString(config.iqamaTitleResId))
            putExtra("OPTIONS", resources.getStringArray(R.array.iqama_delay_options))
            putExtra("SELECTED_INDEX_KEY", iqamaKey)
            putExtra("DEFAULT_INDEX", 0)
        }
        chainedSelectionLauncher.launch(intent)
    }
    private fun finishChain(config: DailyPrayerConfig) {
        updateCombinedPrayerText(config)
        chainConfig = null
        chainStep = null
    }
    private fun updateCombinedPrayerText(config: DailyPrayerConfig) {
        val durationText = sharedHelper.getStringFromArray(config.durationArrayResId, config.durationKey, config.durationDefaultIndex)
        val ablutionKey = sharedHelper.getAblutionKeyForPrayer(config.prayerName) ?: return
        val iqamaKey = sharedHelper.getIqamaKeyForPrayer(config.prayerName) ?: return
        val ablutionOn = sharedHelper.isAblutionEnabled(ablutionKey)
        val iqamaIndex = sharedHelper.getIqamaIndex(iqamaKey)

        config.textView.text = when {
            ablutionOn -> getString(R.string.duration_ablution_suffix, durationText)
            iqamaIndex > 0 -> {
                val iqamaText = resources.getStringArray(R.array.iqama_delay_options).getOrNull(iqamaIndex) ?: ""
                getString(R.string.duration_iqama_suffix, durationText, iqamaText)
            }
            else -> durationText
        }
    }
    private fun setupEdgeToEdge() {
        val mainLinearLayout = findViewById<LinearLayout>(R.id.main_linear_layout)
        ViewCompat.setOnApplyWindowInsetsListener(mainLinearLayout) { view, insets ->
            val systemBarsInsets = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            val displayCutout = insets.displayCutout
            var topOffset = systemBarsInsets.top
            if (displayCutout != null) {
                val safeInsetTop = displayCutout.safeInsetTop
                if (safeInsetTop > topOffset) topOffset = safeInsetTop
            }
            val customOffsetPx = (30 * resources.displayMetrics.density).toInt()
            topOffset += customOffsetPx
            view.updatePadding(left = systemBarsInsets.left, top = topOffset, right = systemBarsInsets.right, bottom = systemBarsInsets.bottom)
            WindowInsetsCompat.CONSUMED
        }
    }
    private fun handleToggleSwitchIntent() {
        if (intent.getBooleanExtra("TOGGLE_SWITCH_TWICE", false)) {
            sharedHelper.saveSwitchState(false)
            activateSwitch.isChecked = false
            Handler(Looper.getMainLooper()).postDelayed({
                sharedHelper.saveSwitchState(true)
                activateSwitch.isChecked = true
            }, 500)
        }
    }
    private fun setupListeners() {
        findViewById<View>(R.id.switchButtonContainer).setOnClickListener { activateSwitch.toggle() }
        activateSwitch.setOnCheckedChangeListener { _, isChecked ->
            if (isRestoringSwitchState) return@setOnCheckedChangeListener
            sharedHelper.saveSwitchState(isChecked)
            if (isChecked) switchOn() else switchOff()
        }
        findViewById<View>(R.id.audioSwitchContainer).setOnClickListener { audioSwitch.toggle() }
        audioSwitch.setOnCheckedChangeListener { _, isChecked ->
            sharedHelper.saveAudioSwitchState(isChecked)
            if (BuildConfig.DEBUG) Log.d(tag, "Audio takbir changed to: $isChecked")
        }
        menuButton.setOnClickListener {
            startActivity(Intent(this, SettingsActivity::class.java))
        }
        containerConfigs.forEach { config ->
            config.container.setOnClickListener {
                if (activateSwitch.isChecked) {
                    showSnackbar(R.string.cannot_change_settings)
                    return@setOnClickListener
                }
                val intent = Intent(this, SelectionActivity::class.java).apply {
                    putExtra("TITLE", getString(config.titleResId))
                    putExtra("OPTIONS", resources.getStringArray(config.optionsResId))
                    putExtra("SELECTED_INDEX_KEY", config.selectedIndexKey)
                    putExtra("DEFAULT_INDEX", config.defaultIndex)
                }
                selectionLauncher.launch(intent)
            }
        }
        dailyPrayerConfigs.forEach { config ->
            config.container.setOnClickListener {
                if (activateSwitch.isChecked) {
                    showSnackbar(R.string.cannot_change_settings)
                    return@setOnClickListener
                }
                launchDurationSelection(config)
            }
        }
    }
    private fun checkAndRequestPermissions(): Boolean {
        val permissionsToCheck = listOf(
            { permissionsHelper.checkLocationPermission() } to { requestLocationPermission() },
            { permissionsHelper.checkDNDPermission(this) } to { permissionsHelper.requestDNDPermission(this) },
            { permissionsHelper.checkAlarmPermission() } to { permissionsHelper.requestAlarmPermission(this) },
            { permissionsHelper.checkBackgroundLocationPermission() } to { permissionsHelper.requestBackgroundLocationPermission(this) }
        )
        permissionsToCheck.forEach { (check, request) ->
            if (!check()) {
                request()
                return false
            }
        }
        return true
    }
    private fun switchOn() {
        tvSwitchState.text = getString(R.string.On)
        if (BuildConfig.DEBUG) Log.d(tag, "Main Switch is turned on")
        if (!checkAndRequestPermissions()) {
            switchOffSilently()
            return
        }
        LocationService.start(this)
        requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_LOCKED
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                handlePrayerTimesSetup()
            } finally {
                withContext(Dispatchers.Main) {
                    requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
                }
            }
        }
    }
    private fun switchOff() {
        tools.exitSilentMode()
        tools.cancelAllSilentModes()
        tools.cancelScheduledSilentMode()
        AlarmScheduler(this).cancelDailyAlarm()
        LocationService.stop(this)
        tvSwitchState.text = getString(R.string.Off)
        sharedHelper.saveSwitchState(false)
        showSnackbar(R.string.app_disabled)
        if (BuildConfig.DEBUG) Log.d(tag, "Main Switch is turned off.")
    }
    private suspend fun handlePrayerTimesSetup() {
        val selectedMethodIndex = sharedHelper.getIntValue(SharedHelper.SELECTED_METHOD_RES_ID, 0)
        if (tools.checkIfDataAvailable() && !tools.processMethodChange(selectedMethodIndex)) {
            if (BuildConfig.DEBUG) Log.i(tag, "Prayer times data already available, processing...")
            tools.processPrayerTimes()
            AlarmScheduler(this@MainActivity).scheduleDailyAlarm()
            withContext(Dispatchers.Main) { showSnackbar(R.string.app_enabled) }
        } else {
            if (BuildConfig.DEBUG) Log.i(tag, "Prayer times data not available/obsolete or method changed, retrieving...")
            withContext(Dispatchers.Main) {
                loadingDialog = tools.createLoadingDialog(this@MainActivity)
                loadingDialog?.show()
            }
            val success = tools.findLocation(selectedMethodIndex) && tools.isInternetAvailable()
            withContext(Dispatchers.Main) {
                if (loadingDialog?.isShowing == true && !isFinishing) loadingDialog?.dismiss()
                loadingDialog = null
                if (success) {
                    sharedHelper.saveIntValue(SharedHelper.SELECTED_METHOD_RES_ID, selectedMethodIndex)
                    sharedHelper.saveLastCheckedMethodIndex(selectedMethodIndex)
                    AlarmScheduler(this@MainActivity).scheduleDailyAlarm()
                    showSnackbar(R.string.app_enabled)
                } else {
                    Log.e(tag, "Location disabled or No Internet connexion")
                    switchStateOff(R.string.no_location_internet)
                }
            }
        }
    }
    private fun updateAllTextViews() {
        textViewsMap.forEach { (key, textView) -> updateTextViewForKey(key, textView) }
        dailyPrayerConfigs.forEach { updateCombinedPrayerText(it) }
    }
    private fun updateTextViewForKey(key: String, textView: TextView? = null) {
        val targetTextView = textView ?: textViewsMap[key] ?: return
        val (arrayResId, defaultIndex) = when (key) {
            SharedHelper.SELECTED_METHOD_RES_ID -> R.array.calculation_methods to 0
            SharedHelper.DURATION_BEFORE_JUMUA -> R.array.before_jumua_duration to 0
            SharedHelper.DURATION_AFTER_JUMUA -> R.array.after_jumua_duration to 3
            SharedHelper.DURATION_TARAWEEH -> R.array.taraweeh_duration to 4
            SharedHelper.DURATION_TAHAJJUD -> R.array.tahajjud_duration to 0
            SharedHelper.SELECTED_TIME_EID -> R.array.eid_time to 0
            SharedHelper.DURATION_EID -> R.array.eid_duration to 2
            else -> R.array.silent_durations to 3
        }
        targetTextView.text = sharedHelper.getStringFromArray(arrayResId, key, defaultIndex)
    }

    override fun onResume() {
        super.onResume()
        checkForUpdateRefresh()
        tools.hideNavigationBarIfNeeded(this)
        if (BuildConfig.DEBUG) Log.d(tag, "Switch state restored in onResume: ${activateSwitch.isChecked}")
        tvSwitchState.text = if (activateSwitch.isChecked) getString(R.string.On) else getString(R.string.Off)

        registerReceiver(dndStateReceiver, IntentFilter(NotificationManager.ACTION_NOTIFICATION_POLICY_ACCESS_GRANTED_CHANGED))
        checkDndAndUpdateSwitch()

        if (sharedHelper.getTermsAccepted() && !sharedHelper.isMigration185DialogShown()) {
            showMigration185Dialog()
            sharedHelper.setMigration185DialogShown(true)
        }

        if (sharedHelper.getTermsAccepted() && permissionsHelper.areLocNotifDNDGranted() && !permissionsHelper.checkAlarmPermission()) {
            permissionsHelper.requestAlarmPermission(this)
        } else if (sharedHelper.getTermsAccepted() && permissionsHelper.areLocNotifDNDAlarmGranted() && !permissionsHelper.checkBackgroundLocationPermission()) {
            permissionsHelper.requestBackgroundLocationPermission(this)
        } else if (permissionsHelper.areAllPermissionsGranted() && !sharedHelper.isPermissionsSnackbarShown()) {
            showEducationDialog()
            sharedHelper.setPermissionsSnackbarShown(true)
        }
        updateAllTextViews()
        audioSwitch.isChecked = sharedHelper.getAudioSwitchState()
    }

    override fun onPause() {
        super.onPause()
        loadingDialog?.dismiss()
        loadingDialog = null
        sharedHelper.saveSwitchState(activateSwitch.isChecked)
        sharedHelper.saveAudioSwitchState(audioSwitch.isChecked)
        try {
            unregisterReceiver(dndStateReceiver)
        } catch (e: IllegalArgumentException) {
            Log.e(tag, "Receiver was not registered, ignore. ${e.message}", e)
        }
        if (BuildConfig.DEBUG) Log.d(tag, "Switch state saved in onPause: ${activateSwitch.isChecked}")
    }

    override fun onDestroy() {
        super.onDestroy()
        if (BuildConfig.DEBUG) Log.d(tag, "onDestroy accessed")
    }
    private fun checkForUpdateRefresh() {
        val lastVersion = sharedHelper.getLastLaunchVersion()
        val currentVersion = BuildConfig.VERSION_CODE
        if (currentVersion > lastVersion) {
            if (sharedHelper.getSwitchState()) LocationService.start(this)
            sharedHelper.saveLastLaunchVersion(currentVersion)
        }
    }
    private fun showSnackbar(@androidx.annotation.StringRes messageRes: Int) {
        Snackbar.make(findViewById(android.R.id.content), getString(messageRes), Snackbar.LENGTH_SHORT).show()
    }
    fun switchStateOff(@androidx.annotation.StringRes message: Int) {
        sharedHelper.saveSwitchState(false)
        activateSwitch.isChecked = false
        tvSwitchState.text = getString(R.string.Off)
        showSnackbar(message)
    }
    private fun switchOffSilently() {
        isRestoringSwitchState = true
        activateSwitch.isChecked = false
        isRestoringSwitchState = false
        sharedHelper.saveSwitchState(false)
        tvSwitchState.text = getString(R.string.Off)
    }
    private fun showWelcomeDialog() {
        val dialog = WelcomeDialog()
        dialog.show(supportFragmentManager, "WelcomeDialog")
    }
    private fun showEducationDialog() {
        val dialog = EducationDialog()
        dialog.show(supportFragmentManager, "EducationDialog")
    }
    private fun showMigration185Dialog() {
        val dialog = MigrationDialog()
        dialog.show(supportFragmentManager, "MigrationDialog")
    }

    override fun onNextClicked() {
        if (BuildConfig.DEBUG) Log.d(tag, "Welcome dialog next clicked. Showing terms.")
        showTermsAndConditionsDialog()
    }
    private fun showTermsAndConditionsDialog() {
        val dialog = TermsAndConditions()
        dialog.show(supportFragmentManager, "TermsAndConditionsDialog")
    }

    override fun onTermsAccepted() {
        if (BuildConfig.DEBUG) Log.d(tag, "Terms accepted callback received. Proceeding with permissions.")
        sharedHelper.setMigration185DialogShown(true)
        requestLocationPermission()
    }

    override fun onTermsDeclined() {
        if (BuildConfig.DEBUG) Log.d(tag, "Terms declined callback received. Exiting app.")
        finish()
    }
    private fun checkDndAndUpdateSwitch() {
        if (activateSwitch.isChecked && !permissionsHelper.checkDNDPermission(this))
            runOnUiThread { switchStateOff(R.string.grant_dnd_permission) }
    }
    private fun requestLocationPermission() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED)
            locationLauncher.launch(Manifest.permission.ACCESS_FINE_LOCATION)
        else handleLocationGranted()
    }
    private fun handleLocationGranted() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED)
            notificationLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        else handleNotificationGranted()
    }
    private fun handleNotificationGranted() {
        when {
            !permissionsHelper.checkDNDPermission(this) -> permissionsHelper.requestDNDPermission(this)
            !permissionsHelper.checkAlarmPermission() -> permissionsHelper.requestAlarmPermission(this)
            !permissionsHelper.checkBackgroundLocationPermission() -> permissionsHelper.requestBackgroundLocationPermission(this)
            else -> {}
        }
    }
    private fun showLocationDenied() {
        if (shouldShowRequestPermissionRationale(Manifest.permission.ACCESS_FINE_LOCATION)) {
            AlertDialog.Builder(this)
                .setTitle(R.string.location_permission_denied_title)
                .setMessage(R.string.location_permission_denied_message)
                .setPositiveButton(R.string.try_again) { _, _ -> requestLocationPermission() }
                .setNegativeButton(R.string.cancel) { _, _ -> Snackbar.make(findViewById(android.R.id.content), getString(R.string.grant_location_permission), Snackbar.LENGTH_SHORT).show() }
                .show()
        } else {
            AlertDialog.Builder(this)
                .setTitle(R.string.location_permanently_denied_title)
                .setMessage(R.string.location_permanently_denied_message)
                .setPositiveButton(R.string.open_settings) { _, _ ->
                    startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                        data = Uri.fromParts("package", packageName, null)
                    })
                }
                .setNegativeButton(R.string.exit) { _, _ -> finish() }
                .show()
        }
    }
    private fun showNotificationDenied() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && shouldShowRequestPermissionRationale(Manifest.permission.POST_NOTIFICATIONS)) {
            AlertDialog.Builder(this)
                .setTitle(R.string.notification_recommended_title)
                .setMessage(R.string.notification_recommended_message)
                .setPositiveButton(R.string.enable) { _, _ -> notificationLauncher.launch(Manifest.permission.POST_NOTIFICATIONS) }
                .setNegativeButton(R.string.skip) { _, _ -> handleNotificationGranted() }
                .show()
        } else {
            handleNotificationGranted()
            Snackbar.make(findViewById(android.R.id.content), getString(R.string.notification_disabled), Snackbar.LENGTH_SHORT).show()
        }
    }
}