package com.github.reygnn.kolibri_launcher.ui.main

import android.app.ActivityOptions
import android.app.WallpaperManager
import android.content.ActivityNotFoundException
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.ContentUris
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ActivityInfo
import android.content.pm.LauncherApps
import android.content.pm.PackageManager
import android.graphics.Color
import android.os.Bundle
import android.os.Process
import android.provider.AlarmClock
import android.provider.CalendarContract
import android.provider.Settings
import android.view.WindowManager
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.core.graphics.drawable.toDrawable
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.core.view.WindowCompat
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.withStarted
import androidx.navigation.NavController
import androidx.navigation.fragment.NavHostFragment
import com.github.reygnn.kolibri_launcher.BuildConfig
import com.github.reygnn.kolibri_launcher.DataStoreBackup
import com.github.reygnn.kolibri_launcher.R
import com.github.reygnn.kolibri_launcher.core.TimberWrapper
import com.github.reygnn.kolibri_launcher.domain.model.AppInfo
import com.github.reygnn.kolibri_launcher.domain.repository.SettingsRepository
import com.github.reygnn.kolibri_launcher.ui.base.BaseActivity
import com.github.reygnn.kolibri_launcher.ui.base.UiEvent
import com.github.reygnn.kolibri_launcher.ui.colorcustomization.ColorCustomizationDialogFragment
import com.github.reygnn.kolibri_launcher.ui.layoutcustomization.LayoutCustomizationDialogFragment
import com.github.reygnn.kolibri_launcher.ui.onboarding.OnboardingActivity
import com.github.reygnn.kolibri_launcher.ui.settings.SettingsActivity
import com.github.reygnn.kolibri_launcher.ui.util.CrashReportConsent
import com.github.reygnn.kolibri_launcher.ui.util.WallpaperImagePicker
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import org.acra.ACRA
import timber.log.Timber
import javax.inject.Inject

/**
 * ULTRA CRASH-SAFE & STABLE VERSION
 *
 * Multi-layer exception handling:
 * - Throwable catch (handles Exception + Error types)
 * - CoroutineExceptionHandler for uncaught coroutine exceptions
 * - CancellationException properly re-thrown
 * - Idempotent initialization (runs only once)
 * - Safe state restoration after configuration changes
 * - Race condition prevention for wallpaper colors
 * - Proper cleanup in lifecycle methods
 */
@AndroidEntryPoint
class MainActivity : BaseActivity<UiEvent, LauncherViewModel>() {

    override val viewModel: LauncherViewModel by viewModels()

    private var navController: NavController? = null
    private var isReceiverRegistered = false
    private var currentDialog: androidx.appcompat.app.AlertDialog? = null

    // Idempotency flags
    private var isInitialized = false
    private var onboardingCheckCompleted = false

    // Ultra Paranoia: Exception handler for MainActivity coroutines
    private val mainActivityExceptionHandler = CoroutineExceptionHandler { _, throwable ->
        try {
            TimberWrapper.silentError(throwable, "Uncaught exception in MainActivity")

            // In Debug: make crashes loud!
            if (BuildConfig.DEBUG) {
                throw throwable
            }
        } catch (e: Throwable) {
            // Even logging can fail
        }
    }

    @Inject
    lateinit var appPackageManager: PackageManager
    @Inject
    lateinit var settingsRepository: SettingsRepository
    @Inject
    lateinit var dataStoreBackup: DataStoreBackup

    private val systemEventReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            try {
                when (intent?.action) {
                    Intent.ACTION_BATTERY_CHANGED -> viewModel.updateBatteryLevelFromIntent(intent)
                }
            } catch (e: Throwable) {
                TimberWrapper.silentError(e, "Error in systemEventReceiver")
            }
        }
    }

    companion object {
        private const val STATE_CURRENT_DESTINATION = "current_destination"
        // private var leaker: Context? = null
    }

    private val onboardingLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        lifecycleScope.launch(mainActivityExceptionHandler) {
            try {
                when (result.resultCode) {
                    RESULT_OK -> {
                        // Onboarding successful - initialize app directly
                        initializeMainApp()
                    }
                    else -> {
                        // Onboarding aborted - close app
                        Timber.Forest.w("Onboarding aborted, closing app")
                        finish()
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                TimberWrapper.silentError(e, "Error handling onboarding result")
                finish()
            }
        }
    }

    private val wallpaperPickerLauncher = registerForActivityResult(
        WallpaperImagePicker.contract()
    ) { uri: android.net.Uri? ->
        try {
            if (uri != null) {
                viewModel.onSetWallpaperImage(uri)
            }
        } catch (e: Throwable) {
            TimberWrapper.silentError(e, "Error handling wallpaper picker result")
            Toast.makeText(this, getString(R.string.error_generic), Toast.LENGTH_SHORT).show()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        try {
            installSplashScreen()
            setupWindow()
        } catch (e: Throwable) {
            TimberWrapper.silentError(e, "Error in pre-onCreate setup")
        }

        super.onCreate(savedInstanceState)

//        // BÖSE: Wir weisen die aktuelle Activity-Instanz der statischen Variable zu.
//        // Wenn man jetzt das Handy dreht oder "Zurück" drückt, bleibt 'this'
//        // hier gespeichert und kann nicht gelöscht werden.
//        if (leaker == null) {
//            leaker = this
//        }

        if (!setupMainContent()) {
            return
        }

        // Only run initial setup on first creation (not on config changes)
        if (savedInstanceState == null) {
            lifecycleScope.launch(mainActivityExceptionHandler) {
                // Launch setup immediately to fetch DataStore in parallel with UI startup.
                // The lifecycle check happens internally only if we need to launch an Activity.
                handleInitialSetup()
            }
        } else {
            // After config change: restore flags
            isInitialized = true
            onboardingCheckCompleted = true
            Timber.Forest.d("Activity recreated, skipping initialization")
        }
    }

    private fun setupMainContent(): Boolean {
        return try {
            setContentView(R.layout.activity_main)

            val navHostFragment = supportFragmentManager
                .findFragmentById(R.id.nav_host_fragment) as? NavHostFragment

            if (navHostFragment != null) {
                navController = navHostFragment.navController
                WindowCompat.setDecorFitsSystemWindows(window, false)
                true
            } else {
                TimberWrapper.silentError("NavHostFragment not found")
                finish()
                false
            }
        } catch (e: Throwable) {
            TimberWrapper.silentError(e, "Fatal error setting up main content")
            finish()
            false
        }
    }

    /**
     * Runs ONLY ONCE on first app start.
     * Determines if onboarding is needed or proceeds directly to initialization.
     */
    private suspend fun handleInitialSetup() {
        if (onboardingCheckCompleted) {
            Timber.Forest.d("Initial setup already completed, skipping")
            return
        }

        try {
            // 1. Fetch data immediately (Async IO) - Don't wait for UI
            val onboardingCompleted = settingsRepository.onboardingCompletedFlow.first()
            val backupPresent = dataStoreBackup.isBackupPresent()

            onboardingCheckCompleted = true

            if (!onboardingCompleted && !backupPresent) {
                // 2. Wait for STARTED state, then launch ONCE
                // This prevents BackgroundActivityLaunchViolation on Pixel/Android 14+
                // Using withStarted is cleaner than repeatOnLifecycle + cancel
                lifecycle.withStarted {
                    launchOnboardingActivity()
                }
            } else {
                // 3. Normal Start - Initialize immediately (no wait needed)
                initializeMainApp()
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            TimberWrapper.silentError(e, "Error in handleInitialSetup")
            // Fallback: Initialize anyway to prevent broken state
            try {
                initializeMainApp()
            } catch (fallbackError: Throwable) {
                TimberWrapper.silentError(fallbackError, "Fallback initialization failed")
            }
        }
    }

    private fun launchOnboardingActivity() {
        try {
            val intent = Intent(this, OnboardingActivity::class.java)
            onboardingLauncher.launch(intent)
        } catch (e: Throwable) {
            TimberWrapper.silentError(e, "Error launching OnboardingActivity")
            // Fallback: Initialize without onboarding
            lifecycleScope.launch(mainActivityExceptionHandler) {
                initializeMainApp()
            }
        }
    }

    /**
     * Final app initialization. Runs ONLY ONCE thanks to isInitialized flag.
     * Note: updateWallpaperColors() is intentionally NOT called here to avoid
     * race conditions with onResume(). Colors are updated in onResume() instead.
     */
    private suspend fun initializeMainApp() {
        if (isInitialized) {
            Timber.Forest.d("App already initialized, skipping")
            return
        }

        try {
            checkAndShowCrashReportConsent()
            isInitialized = true
            Timber.Forest.d("Main app initialization completed")
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            TimberWrapper.silentError(e, "Error during main app initialization")
        }

        // ACRA.errorReporter.handleSilentException(Exception("ACRA Manual Test Report"))
    }

    private fun setupWindow() {
        try {
            window.setWindowAnimations(0)
            window.setBackgroundDrawable(Color.TRANSPARENT.toDrawable())
            window.addFlags(WindowManager.LayoutParams.FLAG_SHOW_WALLPAPER)
        } catch (e: Throwable) {
            TimberWrapper.silentError(e, "Error setting up window")
        }
    }

    private suspend fun checkAndShowCrashReportConsent() {
        CrashReportConsent.showConsentDialog(this) { userGaveConsent ->
            lifecycleScope.launch(mainActivityExceptionHandler) {
                try {
                    ACRA.errorReporter.setEnabled(userGaveConsent)
                    Timber.Forest.i("User consent for crash reports is set to: $userGaveConsent")
                } catch (e: Throwable) {
                    TimberWrapper.silentError(e, "Error setting ACRA consent")
                }
            }
        }
    }

    override fun onStart() {
        super.onStart()
        try {
            viewModel.refreshAllData()
        } catch (e: Throwable) {
            TimberWrapper.silentError(e, "Error in onStart")
        }
    }

    override fun onResume() {
        super.onResume()
        try {
            val intentFilter = IntentFilter().apply {
                addAction(Intent.ACTION_BATTERY_CHANGED)
            }
            registerReceiver(systemEventReceiver, intentFilter, RECEIVER_NOT_EXPORTED)
            isReceiverRegistered = true

            updateWallpaperColors()
            updateSecureFlag()
            updateRotationLock()
        } catch (e: Throwable) {
            TimberWrapper.silentError(e, "Error registering system event receiver")
            isReceiverRegistered = false
        }
    }

    private fun updateSecureFlag() {
        lifecycleScope.launch(mainActivityExceptionHandler) {
            try {
                val isSecure = settingsRepository.secureWindowFlow.first()
                if (isSecure) {
                    window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
                } else {
                    window.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                TimberWrapper.silentError(e, "Error updating FLAG_SECURE")
            }
        }
    }

    private fun updateRotationLock() {
        lifecycleScope.launch(mainActivityExceptionHandler) {
            try {
                val locked = settingsRepository.rotationLockedFlow.first()
                requestedOrientation = if (locked) {
                    ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
                } else {
                    ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                TimberWrapper.silentError(e, "Error updating rotation lock")
            }
        }
    }

    override fun onPause() {
        super.onPause()
        try {
            if (isReceiverRegistered) {
                unregisterReceiver(systemEventReceiver)
                isReceiverRegistered = false
            }
        } catch (e: Throwable) {
            TimberWrapper.silentError(e, "Error unregistering receiver")
        }
    }

    override fun onStop() {
        super.onStop()

        if (BuildConfig.DEBUG) {
            lifecycleScope.launch(mainActivityExceptionHandler) {
                try {
                    dataStoreBackup.createBackup()
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Throwable) {
                    TimberWrapper.silentError(e, "Error creating backup")
                }
            }
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        try {
            navController?.currentDestination?.id?.let { destinationId ->
                outState.putInt(STATE_CURRENT_DESTINATION, destinationId)
            }
        } catch (e: Throwable) {
            TimberWrapper.silentError(e, "Error saving navigation state")
        }
    }

    override fun onRestoreInstanceState(savedInstanceState: Bundle) {
        super.onRestoreInstanceState(savedInstanceState)
        try {
            val destinationId = savedInstanceState.getInt(STATE_CURRENT_DESTINATION, R.id.homeFragment)
            val currentNav = navController

            if (currentNav != null &&
                destinationId != R.id.homeFragment &&
                currentNav.currentDestination?.id != destinationId) {
                try {
                    currentNav.navigate(destinationId)
                } catch (e: Throwable) {
                    Timber.Forest.w(e, "Failed to restore navigation state")
                    // Fallback to home
                    try {
                        currentNav.popBackStack(R.id.homeFragment, false)
                    } catch (fallbackError: Throwable) {
                        TimberWrapper.silentError(fallbackError, "Failed to navigate to home fragment")
                    }
                }
            }
        } catch (e: Throwable) {
            TimberWrapper.silentError(e, "Error in onRestoreInstanceState")
        }
    }

    override fun onDestroy() {
        try {
            currentDialog?.dismiss()
            currentDialog = null

            if (isReceiverRegistered) {
                try {
                    unregisterReceiver(systemEventReceiver)
                } catch (e: Throwable) {
                    TimberWrapper.silentError(e, "Error unregistering receiver in onDestroy")
                }
                isReceiverRegistered = false
            }
            navController = null
        } catch (e: Throwable) {
            TimberWrapper.silentError(e, "Error in onDestroy")
        } finally {
            super.onDestroy()
        }
    }

    override fun handleSpecificEvent(event: UiEvent) {
        if (BuildConfig.DEBUG) {
            Timber.Forest.d("[MAIN] handleSpecificEvent called with: ${event.javaClass.simpleName}")
        }

        try {
            when (event) {
                is UiEvent.ShowAppDrawer -> {
                    if (navController?.currentDestination?.id == R.id.homeFragment) {
                        try {
                            navController?.navigate(R.id.appDrawerFragment)
                            if (BuildConfig.DEBUG) {
                                Timber.Forest.d("[MAIN] Navigated to app drawer")
                            }
                        } catch (e: Throwable) {
                            TimberWrapper.silentError(e, "[MAIN] Error navigating to app drawer")
                        }
                    } else if (BuildConfig.DEBUG) {
                        Timber.Forest.d("[MAIN] Not navigating - wrong destination: ${navController?.currentDestination?.id}")
                    }
                }

                is UiEvent.ShowSettings -> {
                    try {
                        val intent = Intent(this, SettingsActivity::class.java)
                        startActivity(intent)
                    } catch (e: Throwable) {
                        TimberWrapper.silentError(e, "[MAIN] Error starting settings")
                    }
                }

                is UiEvent.ShowCustomizationOptions -> {
                    showCustomizationOptionsDialog()
                }

                is UiEvent.ShowColorPickerDialog -> {
                    try {
                        ColorCustomizationDialogFragment().show(supportFragmentManager, "ColorCustomizationDialog")
                    } catch (e: Throwable) {
                        TimberWrapper.silentError(e, "[MAIN] Error showing color picker")
                    }
                }

                is UiEvent.OpenClock -> {
                    startActivitySafely(Intent(AlarmClock.ACTION_SHOW_ALARMS))
                }

                is UiEvent.OpenCalendar -> {
                    try {
                        val builder = CalendarContract.CONTENT_URI.buildUpon().appendPath("time")
                        ContentUris.appendId(builder, System.currentTimeMillis())
                        startActivitySafely(Intent(Intent.ACTION_VIEW).setData(builder.build()))
                    } catch (e: Throwable) {
                        TimberWrapper.silentError(e, "[MAIN] Error opening calendar")
                        Toast.makeText(this, getString(R.string.error_no_calendar_app), Toast.LENGTH_SHORT).show()
                    }
                }

                is UiEvent.OpenBatterySettings -> {
                    startActivitySafely(Intent(Intent.ACTION_POWER_USAGE_SUMMARY))
                }

                is UiEvent.LaunchApp -> {
                    if (BuildConfig.DEBUG) {
                        val isInDrawer =
                            navController?.currentDestination?.id == R.id.appDrawerFragment
                        Timber.Forest.d("[MAIN] Processing LaunchApp for: ${event.app.displayName}, inDrawer: $isInDrawer")
                    }

                    try {
                        val isInDrawer =
                            navController?.currentDestination?.id == R.id.appDrawerFragment

                        if (isInDrawer) {
                            try {
                                navController?.popBackStack()
                                if (BuildConfig.DEBUG) {
                                    Timber.Forest.d("[MAIN] Drawer closed")
                                }
                            } catch (e: Throwable) {
                                TimberWrapper.silentError(e, "[MAIN] Error popping back stack")
                            }
                        }

                        launchApp(event.app)
                    } catch (e: Throwable) {
                        TimberWrapper.silentError(e, "[MAIN] Error handling launch app event")
                    }
                }

                is UiEvent.ShowAccessibilityDialog -> {
                    showAccessibilityDialog()
                }

                is UiEvent.OpenWallpaperPicker -> {
                    try {
                        WallpaperImagePicker.launch(wallpaperPickerLauncher)
                    } catch (e: Throwable) {
                        TimberWrapper.silentError(e, "Error launching wallpaper picker")
                        Toast.makeText(this, getString(R.string.error_generic), Toast.LENGTH_SHORT).show()
                    }
                }

                is UiEvent.EnterWallpaperEditMode -> {
                    viewModel.onSetWallpaperEditMode(true)
                }

                is UiEvent.ExitWallpaperEditMode -> {
                    viewModel.onSetWallpaperEditMode(false)
                }

                is UiEvent.ShowToast,
                is UiEvent.ShowToastFromString,
                is UiEvent.NavigateUp,
                is UiEvent.RefreshAppDrawer -> {
                    // Intentionally empty - handled in BaseActivity
                }
            }
        } catch (e: Throwable) {
            TimberWrapper.silentError(e, "[MAIN] Error in handleSpecificEvent")
        }
    }

    private fun launchApp(appInfo: AppInfo) {
        if (BuildConfig.DEBUG) {
            Timber.Forest.d("[LAUNCH] Starting launch for: ${appInfo.displayName}")
        }

        try {
            val launcherApps = getSystemService(LAUNCHER_APPS_SERVICE) as? LauncherApps
            if (launcherApps == null) {
                TimberWrapper.silentError("[LAUNCH] LauncherApps service is null!")
                Toast.makeText(this, getString(R.string.error_generic), Toast.LENGTH_SHORT).show()
                return
            }

            val componentName = ComponentName(appInfo.packageName, appInfo.className)
            val userHandle = Process.myUserHandle()
            val options = ActivityOptions.makeCustomAnimation(
                this,
                R.anim.app_open_enter,
                R.anim.app_open_exit
            )

            launcherApps.startMainActivity(componentName, userHandle, null, options.toBundle())

            if (BuildConfig.DEBUG) {
                Timber.Forest.d("[LAUNCH] Success: ${appInfo.displayName}")
            }

        } catch (e: ActivityNotFoundException) {
            TimberWrapper.silentError(e, "[LAUNCH] ActivityNotFoundException: ${appInfo.displayName}")
            Toast.makeText(this, getString(R.string.error_app_not_available), Toast.LENGTH_SHORT).show()
        } catch (e: SecurityException) {
            TimberWrapper.silentError(e, "[LAUNCH] SecurityException: ${appInfo.displayName}")
            Toast.makeText(this, getString(R.string.error_generic), Toast.LENGTH_SHORT).show()
        } catch (e: Throwable) {
            TimberWrapper.silentError(e, "[LAUNCH] Exception: ${appInfo.displayName}")
            Toast.makeText(this, getString(R.string.error_generic), Toast.LENGTH_SHORT).show()
        }
    }

    private fun showDialog(builder: MaterialAlertDialogBuilder) {
        try {
            currentDialog?.dismiss()
            currentDialog = builder.show()
        } catch (e: Throwable) {
            TimberWrapper.silentError(e, "Error showing dialog")
        }
    }

    private fun showAccessibilityDialog() {
        try {
            val builder = MaterialAlertDialogBuilder(this, R.style.CustomAlertDialog)
                .setTitle(getString(R.string.accessibility_service_title))
                .setMessage(getString(R.string.accessibility_service_explanation))
                .setPositiveButton(getString(R.string.go_to_settings)) { _, _ ->
                    startActivitySafely(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
                }
                .setNegativeButton(getString(R.string.cancel), null)

            showDialog(builder)

        } catch (e: Throwable) {
            TimberWrapper.silentError(e, "Error showing accessibility dialog")
        }
    }

    private fun showCustomizationOptionsDialog() {
        try {
            // 1. Vorherigen Dialog schließen
            currentDialog?.dismiss()
            currentDialog = null

            val hasWallpaper = viewModel.wallpaperState.value.hasWallpaper
            val isEditMode = viewModel.isWallpaperEditMode.value

            // Dynamische Optionen basierend auf Wallpaper-Status
            val options = mutableListOf<String>()
            val actions = mutableListOf<() -> Unit>()

            // Im Edit-Mode: Dialog nicht öffnen (Buttons sind sichtbar)
            if (isEditMode) {
                return
            }

            // Wallpaper Optionen
            run {
                // Normal: Wallpaper wählen
                options.add(getString(R.string.wallpaper_choose))
                actions.add {
                    WallpaperImagePicker.launch(wallpaperPickerLauncher)
                }

                // Falls Wallpaper vorhanden: Edit & Remove Optionen
                if (hasWallpaper) {
                    options.add(getString(R.string.wallpaper_edit_mode))
                    actions.add {
                        viewModel.onSetWallpaperEditMode(true)
                    }

                    options.add(getString(R.string.wallpaper_remove))
                    actions.add {
                        MaterialAlertDialogBuilder(this, R.style.CustomAlertDialog)
                            .setTitle(getString(R.string.wallpaper_remove))
                            .setMessage(getString(R.string.wallpaper_remove_confirm))
                            .setPositiveButton(getString(R.string.wallpaper_remove_yes)) { _, _ ->
                                viewModel.onClearWallpaper()
                            }
                            .setNegativeButton(getString(R.string.cancel), null)
                            .show()
                    }
                }
            }

            // Bestehende Optionen
            options.add(getString(R.string.customize_colors_and_shadow))
            actions.add {
                try {
                    ColorCustomizationDialogFragment().show(supportFragmentManager, "ColorCustomizationDialog")
                } catch (e: Throwable) {
                    TimberWrapper.silentError(e, "Error showing color customization")
                }
            }

            options.add(getString(R.string.customize_layout_title))
            actions.add {
                try {
                    LayoutCustomizationDialogFragment().show(supportFragmentManager, "LayoutCustomizationDialog")
                } catch (e: Throwable) {
                    TimberWrapper.silentError(e, "Error showing layout customization")
                }
            }

            options.add(getString(R.string.more_settings))
            actions.add {
                try {
                    val intent = Intent(this, SettingsActivity::class.java)
                    startActivity(intent)
                } catch (e: Throwable) {
                    TimberWrapper.silentError(e, "[MAIN] Error starting settings")
                }
            }

            // 2. Dialog bauen
            currentDialog = MaterialAlertDialogBuilder(this, R.style.CustomAlertDialog)
                .setTitle(getString(R.string.customize_title))
                .setItems(options.toTypedArray()) { _, which ->
                    try {
                        actions.getOrNull(which)?.invoke()
                    } catch (e: Throwable) {
                        TimberWrapper.silentError(e, "Error handling dialog selection")
                    }
                }
                .setNegativeButton(R.string.cancel, null)
                .setOnDismissListener {
                    if (currentDialog == it) {
                        currentDialog = null
                    }
                }
                .show()

        } catch (e: Throwable) {
            TimberWrapper.silentError(e, "Error showing customization options dialog")
        }
    }

    private fun startActivitySafely(intent: Intent, fallbackIntent: Intent? = null) {
        try {
            intent.flags = Intent.FLAG_ACTIVITY_NEW_TASK
            startActivity(intent)
        } catch (e: Throwable) {
            TimberWrapper.silentError(e, getString(R.string.error_starting_intent, intent.toString()))

            if (fallbackIntent != null) {
                try {
                    fallbackIntent.flags = Intent.FLAG_ACTIVITY_NEW_TASK
                    startActivity(fallbackIntent)
                    return
                } catch (fallbackError: Throwable) {
                    TimberWrapper.silentError(fallbackError, getString(R.string.error_fallback_intent_failed))
                }
            }

            Toast.makeText(this, getString(R.string.error_activity_not_found), Toast.LENGTH_SHORT).show()
        }
    }

    private fun updateWallpaperColors() {
        try {
            val wallpaperManager = WallpaperManager.getInstance(this)
            val colors = wallpaperManager.getWallpaperColors(WallpaperManager.FLAG_SYSTEM)
            viewModel.updateUiColors(colors)
        } catch (e: Throwable) {
            TimberWrapper.silentError(e, "Error updating wallpaper colors")
            viewModel.updateUiColors()
        }
    }
}