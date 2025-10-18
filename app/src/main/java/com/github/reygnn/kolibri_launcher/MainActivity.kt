/*
    * Copyright (C) 2025 reygnn (Ulrich Kaufmann)
    *
    * This program is free software: you can redistribute it and/or modify
    * it under the terms of the GNU General Public License as published by
    * the Free Software Foundation, either version 3 of the License, or
    * (at your option) any later version.
    */

package com.github.reygnn.kolibri_launcher

import android.app.Activity
import android.app.ActivityOptions
import android.app.AlertDialog
import android.app.WallpaperManager
import android.content.ActivityNotFoundException
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.ContentUris
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
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
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.navigation.NavController
import androidx.navigation.fragment.NavHostFragment
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import org.acra.ACRA
import timber.log.Timber
import javax.inject.Inject

/**
 * CRASH-SAFE VERSION
 *
 * Crash safety through:
 * - Nullable NavController with safe access
 * - Try-catch for all critical operations
 * - Safe BroadcastReceiver registration/unregistration
 * - Defensive null checks throughout
 * - Safe navigation with fallbacks
 * - Proper cleanup in lifecycle methods
 * - State restoration protection
 */
@AndroidEntryPoint
class MainActivity : BaseActivity<UiEvent, HomeViewModel>() {

    override val viewModel: HomeViewModel by viewModels()

    // CRASH-SAFE: Nullable NavController
    private var navController: NavController? = null
    private var isReceiverRegistered = false

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
                    Intent.ACTION_TIME_TICK,
                    Intent.ACTION_TIME_CHANGED,
                    Intent.ACTION_TIMEZONE_CHANGED -> viewModel.updateTimeAndDate()
                    Intent.ACTION_BATTERY_CHANGED -> viewModel.updateBatteryLevelFromIntent(intent)
                }
            } catch (e: Exception) {
                TimberWrapper.silentError(e, "Error in systemEventReceiver")
            }
        }
    }

    companion object {
        private const val STATE_CURRENT_DESTINATION = "current_destination"
    }


    // Der Launcher, der auf das Ergebnis der OnboardingActivity wartet.
    private val onboardingLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            // Onboarding war erfolgreich. Initialisiere jetzt die App.
            lifecycleScope.launch {
                lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
                    initializeMainApp()
                }
            }
        } else {
            // Onboarding wurde abgebrochen. App beenden.
            finish()
        }
    }

    /**
     * Die neue, stabile onCreate-Methode. Sie dient als Orchestrator.
     */
    override fun onCreate(savedInstanceState: Bundle?) {
        // Vorbereitende UI-Schritte, die vor super.onCreate() passieren müssen.
        try {
            installSplashScreen()
            setupWindow()
        } catch (e: Exception) {
            TimberWrapper.silentError(e, "Error in pre-onCreate setup")
        }

        super.onCreate(savedInstanceState)

        // PHASE 1: Synchroner UI-Aufbau
        // Wenn dieser Schritt fehlschlägt, wird die Methode sofort beendet.
        if (!setupMainContent()) {
            return
        }

        // PHASE 2: Asynchrone Logik sicher an den Lebenszyklus koppeln
        lifecycleScope.launch {
            lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
                // Führe die komplexe Logik nur aus, wenn die App sicher im Vordergrund ist.
                handlePostCreationLogic()
            }
        }
    }

    /**
     * PHASE 1 - HELFER: Kümmert sich um den synchronen Aufbau der Haupt-UI.
     * @return 'true' bei Erfolg, 'false' bei einem Fehler.
     */
    private fun setupMainContent(): Boolean {
        return try {
            // 1. Das Layout aus der XML-Datei laden und zur Ansicht machen.
            setContentView(R.layout.activity_main)

            // 2. Den Container für unsere Fragmente finden.
            val navHostFragment = supportFragmentManager
                .findFragmentById(R.id.nav_host_fragment) as? NavHostFragment

            if (navHostFragment != null) {
                // 3. Den NavController initialisieren, der für die Navigation zuständig ist.
                navController = navHostFragment.navController
                WindowCompat.setDecorFitsSystemWindows(window, false)
                true // Alles hat geklappt -> Erfolg melden.
            } else {
                // Kritischer Fehler: Der Navigations-Container wurde nicht gefunden.
                TimberWrapper.silentError("NavHostFragment not found")
                finish() // App sicher beenden.
                false // Fehler melden.
            }
        } catch (e: Exception) {
            // Allgemeiner, kritischer Fehler beim Aufbau der UI.
            TimberWrapper.silentError(e, "Fatal error setting up main content")
            finish() // App sicher beenden.
            false // Fehler melden.
        }
    }

    /**
     * PHASE 2 - HELFER: Entscheidet, ob Onboarding nötig ist oder die App direkt initialisiert wird.
     */
    private suspend fun handlePostCreationLogic() {
        try {
            // 1. Warte auf die Information, ob der Benutzer das Onboarding schon abgeschlossen hat.
            val onboardingCompleted = settingsRepository.onboardingCompletedFlow.first()
            val backupPresent = dataStoreBackup.isBackupPresent()

            // 2. Die entscheidende Weiche:
            if (!onboardingCompleted && !backupPresent) {
                // FALL A: Onboarding ist erforderlich.
                launchOnboardingActivity()
            } else {
                // FALL B: Onboarding ist bereits abgeschlossen.
                initializeMainApp()
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            TimberWrapper.silentError(e, "Error in handlePostCreationLogic")
        }
    }

    /**
     * PHASE 2 - HELFER (FALL A): Startet die OnboardingActivity und wartet auf das Ergebnis.
     */
    private fun launchOnboardingActivity() {
        try {
            val intent = Intent(this, OnboardingActivity::class.java)
            // Übergibt die Kontrolle an den onboardingLauncher. MainActivity wartet im Hintergrund.
            onboardingLauncher.launch(intent)
        } catch (e: Exception) {
            TimberWrapper.silentError(e, "Error launching OnboardingActivity")
        }
    }

    /**
     * PHASE 2 - HELFER (FALL B & nach FALL A): Führt die finale App-Initialisierung durch.
     */
    private suspend fun initializeMainApp() {
        try {
             checkAndShowCrashReportConsent()
            updateWallpaperColors()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            TimberWrapper.silentError(e, "Error during main app initialization")
        }
    }

    //----------------------------------------------------------------

    private fun setupWindow() {
        try {
            window.setWindowAnimations(0)
            window.setBackgroundDrawable(Color.TRANSPARENT.toDrawable())
            window.addFlags(WindowManager.LayoutParams.FLAG_SHOW_WALLPAPER)
        } catch (e: Exception) {
            TimberWrapper.silentError(e, "Error setting up window")
        }
    }

    private suspend fun checkAndShowCrashReportConsent() {
        CrashReportConsent.showConsentDialog(this) { userGaveConsent ->
            ACRA.errorReporter.setEnabled(userGaveConsent)
            Timber.i("User consent for crash reports is set to: $userGaveConsent")
        }
    }

    override fun onStart() {
        super.onStart()
        try {
            viewModel.refreshAllData()
        } catch (e: Exception) {
            TimberWrapper.silentError(e, "Error in onStart")
        }
    }

    override fun onResume() {
        super.onResume()
        try {
            val intentFilter = IntentFilter().apply {
                addAction(Intent.ACTION_TIME_TICK)
                addAction(Intent.ACTION_TIME_CHANGED)
                addAction(Intent.ACTION_TIMEZONE_CHANGED)
                addAction(Intent.ACTION_BATTERY_CHANGED)
            }
            registerReceiver(systemEventReceiver, intentFilter, RECEIVER_NOT_EXPORTED)
            isReceiverRegistered = true

            updateWallpaperColors()
        } catch (e: Exception) {
            TimberWrapper.silentError(e, "Error registering system event receiver")
            isReceiverRegistered = false
        }
    }

    override fun onPause() {
        super.onPause()
        try {
            if (isReceiverRegistered) {
                unregisterReceiver(systemEventReceiver)
                isReceiverRegistered = false
            }
        } catch (e: Exception) {
            TimberWrapper.silentError(e, "Error unregistering receiver")
        }
    }

    override fun onStop() {
        super.onStop()

        if (BuildConfig.DEBUG) {
            lifecycleScope.launch {
                try {
                    dataStoreBackup.createBackup()
                } catch (e: Exception) {
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
        } catch (e: Exception) {
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
                } catch (e: Exception) {
                    Timber.w(e, "Failed to restore navigation state")
                    // Fallback to home
                    try {
                        currentNav.popBackStack(R.id.homeFragment, false)
                    } catch (fallbackError: Exception) {
                        TimberWrapper.silentError(fallbackError, "Failed to navigate to home fragment")
                    }
                }
            }
        } catch (e: Exception) {
            TimberWrapper.silentError(e, "Error in onRestoreInstanceState")
        }
    }

    override fun onDestroy() {
        try {
            // CRASH-SAFE: Cleanup
            if (isReceiverRegistered) {
                try {
                    unregisterReceiver(systemEventReceiver)
                } catch (e: Exception) {
                    TimberWrapper.silentError(e, "Error unregistering receiver in onDestroy")
                }
                isReceiverRegistered = false
            }
            navController = null
        } catch (e: Exception) {
            TimberWrapper.silentError(e, "Error in onDestroy")
        } finally {
            super.onDestroy()
        }
    }

    override fun handleSpecificEvent(event: UiEvent) {
        if (BuildConfig.DEBUG) {
            Timber.d("[MAIN] handleSpecificEvent called with: ${event.javaClass.simpleName}")
        }

        try {
            when (event) {
                // --- Die spezifische Logik für MainActivity ---
                is UiEvent.ShowAppDrawer -> {
                    if (navController?.currentDestination?.id == R.id.homeFragment) {
                        try {
                            navController?.navigate(R.id.appDrawerFragment)
                            if (BuildConfig.DEBUG) {
                                Timber.d("[MAIN] Navigated to app drawer")
                            }
                        } catch (e: Exception) {
                            TimberWrapper.silentError(e, "[MAIN] Error navigating to app drawer")
                        }
                    } else if (BuildConfig.DEBUG) {
                        Timber.d("[MAIN] Not navigating - wrong destination: ${navController?.currentDestination?.id}")
                    }
                }

                is UiEvent.ShowSettings -> {
                    try {
                        val intent = Intent(this, SettingsActivity::class.java)
                        startActivity(intent)
                    } catch (e: Exception) {
                        TimberWrapper.silentError(e, "[MAIN] Error starting settings")
                    }
                }

                is UiEvent.ShowCustomizationOptions -> {
                    showCustomizationOptionsDialog()
                }

                is UiEvent.ShowColorPickerDialog -> {
                    ColorCustomizationDialogFragment().show(supportFragmentManager, "ColorCustomizationDialog")
                }

                is UiEvent.OpenClock -> {
                    startActivitySafely(Intent(AlarmClock.ACTION_SHOW_ALARMS))
                }

                is UiEvent.OpenCalendar -> {
                    try {
                        val builder = CalendarContract.CONTENT_URI.buildUpon().appendPath("time")
                        ContentUris.appendId(builder, System.currentTimeMillis())
                        startActivitySafely(Intent(Intent.ACTION_VIEW).setData(builder.build()))
                    } catch (e: Exception) {
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
                        Timber.d("[MAIN] Processing LaunchApp for: ${event.app.displayName}, inDrawer: $isInDrawer")
                    }

                    try {
                        val isInDrawer =
                            navController?.currentDestination?.id == R.id.appDrawerFragment

                        if (isInDrawer) {
                            try {
                                navController?.popBackStack()
                                if (BuildConfig.DEBUG) {
                                    Timber.d("[MAIN] Drawer closed")
                                }
                            } catch (e: Exception) {
                                TimberWrapper.silentError(e, "[MAIN] Error popping back stack")
                            }
                        }

                        launchApp(event.app)
                    } catch (e: Exception) {
                        TimberWrapper.silentError(e, "[MAIN] Error handling launch app event")
                    }
                }

                is UiEvent.ShowAccessibilityDialog -> {
                    showAccessibilityDialog()
                }

                is UiEvent.ShowToast,
                is UiEvent.ShowToastFromString,
                is UiEvent.NavigateUp,
                is UiEvent.RefreshAppDrawer -> {
                    // Hier ist absichtlich nichts zu tun.
                }
            }
        } catch (e: Exception) {
            TimberWrapper.silentError(e, "[MAIN] Error in handleSpecificEvent")
        }
    }

    private fun launchApp(appInfo: AppInfo) {
        if (BuildConfig.DEBUG) {
            Timber.d("[LAUNCH] Starting launch for: ${appInfo.displayName}")
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
                Timber.d("[LAUNCH] Success: ${appInfo.displayName}")
            }

        } catch (e: ActivityNotFoundException) {
            TimberWrapper.silentError(e, "[LAUNCH] ActivityNotFoundException: ${appInfo.displayName}")
            Toast.makeText(this, getString(R.string.error_app_not_available), Toast.LENGTH_SHORT).show()
        } catch (e: SecurityException) {
            TimberWrapper.silentError(e, "[LAUNCH] SecurityException: ${appInfo.displayName}")
            Toast.makeText(this, getString(R.string.error_generic), Toast.LENGTH_SHORT).show()
        } catch (e: Exception) {
            TimberWrapper.silentError(e, "[LAUNCH] Exception: ${appInfo.displayName}")
            Toast.makeText(this, getString(R.string.error_generic), Toast.LENGTH_SHORT).show()
        }
    }

    private fun showAccessibilityDialog() {
        try {
            MaterialAlertDialogBuilder(this, R.style.CustomAlertDialog)
                .setTitle(getString(R.string.accessibility_service_title))
                .setMessage(getString(R.string.accessibility_service_explanation))
                .setPositiveButton(getString(R.string.go_to_settings)) { _, _ ->
                    startActivitySafely(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
                }
                .setNegativeButton(getString(R.string.cancel), null)
                .show()
        } catch (e: Exception) {
            TimberWrapper.silentError(e, "Error showing accessibility dialog")
        }
    }

    private fun showCustomizationOptionsDialog() {
        try {
            val options = arrayOf(
                getString(R.string.customize_colors_and_shadow),
                getString(R.string.more_settings)
            )

            MaterialAlertDialogBuilder(this, R.style.CustomAlertDialog)
                .setTitle(getString(R.string.customize_title))
                .setItems(options) { _, which ->
                    when (which) {
                        0 -> ColorCustomizationDialogFragment().show(supportFragmentManager, "ColorCustomizationDialog")
                        1 -> {
                            try {
                                val intent = Intent(this, SettingsActivity::class.java)
                                startActivity(intent)
                            } catch (e: Exception) {
                                TimberWrapper.silentError(e, "[MAIN] Error starting settings")
                            }
                        }
                    }
                }
                .setNegativeButton(R.string.cancel, null)
                .show()
        } catch (e: Exception) {
            TimberWrapper.silentError(e, "Error showing customization options dialog")
        }
    }

    private fun startActivitySafely(intent: Intent, fallbackIntent: Intent? = null) {
        try {
            intent.flags = Intent.FLAG_ACTIVITY_NEW_TASK
            startActivity(intent)
        } catch (e: Exception) {
            TimberWrapper.silentError(e, getString(R.string.error_starting_intent, intent.toString()))

            if (fallbackIntent != null) {
                try {
                    fallbackIntent.flags = Intent.FLAG_ACTIVITY_NEW_TASK
                    startActivity(fallbackIntent)
                    return
                } catch (fallbackError: Exception) {
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
            // Ruft die neue, universelle Update-Funktion im ViewModel auf
            viewModel.updateUiColors(colors)
        } catch (e: Exception) {
            TimberWrapper.silentError(e, "Error updating wallpaper colors")
            // Fallback, falls etwas schiefgeht
            viewModel.updateUiColors()
        }
    }
}