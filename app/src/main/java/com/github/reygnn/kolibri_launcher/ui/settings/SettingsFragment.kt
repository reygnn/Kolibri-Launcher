package com.github.reygnn.kolibri_launcher.ui.settings

import android.Manifest
import android.app.role.RoleManager
import android.content.Context
import android.content.Intent
import android.text.format.DateUtils
import android.content.pm.PackageManager
import android.os.Bundle
import android.provider.Settings
import android.view.LayoutInflater
import android.view.View
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.browser.customtabs.CustomTabColorSchemeParams
import androidx.browser.customtabs.CustomTabsIntent
import androidx.core.content.ContextCompat
import androidx.core.net.toUri
import androidx.fragment.app.viewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.preference.ListPreference
import androidx.preference.Preference
import androidx.preference.PreferenceFragmentCompat
import androidx.preference.SwitchPreferenceCompat
import com.github.reygnn.kolibri_launcher.BuildConfig
import com.github.reygnn.kolibri_launcher.EspressoIdlingResource
import com.github.reygnn.kolibri_launcher.R
import com.github.reygnn.kolibri_launcher.core.AppConstants
import com.github.reygnn.kolibri_launcher.core.TimberWrapper
import com.github.reygnn.kolibri_launcher.domain.model.WallpaperSurfaceMode
import com.github.reygnn.kolibri_launcher.domain.repository.FavoritesOrderRepository
import com.github.reygnn.kolibri_launcher.domain.repository.FavoritesRepository
import com.github.reygnn.kolibri_launcher.domain.repository.SettingsRepository
import com.github.reygnn.kolibri_launcher.ui.backup.BackupFragment
import com.github.reygnn.kolibri_launcher.ui.customnames.CustomNamesActivity
import com.github.reygnn.kolibri_launcher.ui.favorites.FavoritesSortFragment
import com.github.reygnn.kolibri_launcher.ui.hiddenapps.HiddenAppsActivity
import com.github.reygnn.kolibri_launcher.ui.onboarding.LaunchMode
import com.github.reygnn.kolibri_launcher.ui.onboarding.OnboardingActivity
import com.github.reygnn.kolibri_launcher.ui.swipeactions.SwipeActionsActivity
import com.github.reygnn.kolibri_launcher.ui.usageexport.UsageExportFragment
import com.github.reygnn.kolibri_launcher.crashreporting.consent.ConsentController
import com.github.reygnn.kolibri_launcher.crashreporting.consent.ConsentDecision
import com.github.reygnn.kolibri_launcher.crashreporting.consent.ConsentDialog
import com.github.reygnn.kolibri_launcher.crashreporting.consent.ConsentReadResult
import com.github.reygnn.kolibri_launcher.crashreporting.resilience.PipelineBacklogProbe
import com.github.reygnn.kolibri_launcher.ui.util.resolveThemeColor
import com.github.reygnn.kolibri_launcher.ui.util.showToastSafe
import com.github.reygnn.kolibri_launcher.ui.util.withRelaxedStrictMode
import com.google.android.material.checkbox.MaterialCheckBox
import androidx.appcompat.app.AlertDialog
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import timber.log.Timber
import javax.inject.Inject

/**
 * CRASH-SAFE VERSION
 * (includes calendar-permission handling)
 *
 * Throwable-audit note: the outer catches that used to wrap every
 * `findPreference + setOn*Listener` wiring are gone — neither
 * `findPreference` (returns null on miss) nor `setOn*Listener` on a
 * safe-call receiver can throw. Inner catches inside listener bodies
 * stay everywhere because real work happens there (startActivity /
 * fragment transaction / suspend repo call). Same pattern for the
 * Flow observers in `observeSettings`: the `?.isChecked = X` setter
 * inside the block can't throw, but the outer catch around
 * `.collect { }` can — that's why it stays.
 *
 * 2026-05-02 follow-up sweep: listener bodies that only call a sub-method
 * with its own try/catch (`openSystemWallpaperPicker`,
 * `showFactoryResetDialog`, `openDefaultLauncherSettings`) need no
 * additional outer catch.
 * Listeners with `viewLifecycleOwner` access or direct system-API calls
 * (`startActivity`, `parentFragmentManager`) keep their inner catch
 * (lifecycle-race protection).
 */
@AndroidEntryPoint
class SettingsFragment : PreferenceFragmentCompat() {

    private val viewModel: SettingsViewModel by viewModels({ requireActivity() })

    @Inject
    lateinit var favoritesRepository: FavoritesRepository

    @Inject
    lateinit var favoritesOrderRepository: FavoritesOrderRepository

    @Inject
    lateinit var settingsRepository: SettingsRepository

    @Inject
    lateinit var crashReportConsentController: ConsentController

    @Inject
    lateinit var pipelineBacklogProbe: PipelineBacklogProbe

    // 1. Deklaration für die Preference
    // Tracked so onDestroyView can dismiss the currently-open dialog
    // (calendar-permission rationale / factory-reset / forced crash-report
    // consent) — otherwise a rotation with it open leaks its window.
    private var currentDialog: AlertDialog? = null

    private var calendarSwitchPreference: SwitchPreferenceCompat? = null
    private var alarmSwitchPreference: SwitchPreferenceCompat? = null
    private var doubleTapClipboardSwitchPreference: SwitchPreferenceCompat? = null
    private var autoKeyboardSwitchPreference: SwitchPreferenceCompat? = null
    private var autoLaunchAppSwitchPreference: SwitchPreferenceCompat? = null
    private var rotationLockedSwitchPreference: SwitchPreferenceCompat? = null

    // 2. Companion Object für den Berechtigungs-String
    companion object {
        private const val CALENDAR_PERMISSION = Manifest.permission.READ_CALENDAR
    }

    // 3. ActivityResultLauncher für die Berechtigungsanfrage
    private val requestPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { isGranted: Boolean ->
            if (isGranted) {
                // Nutzer hat zugestimmt! Jetzt die Einstellung speichern.
                viewLifecycleOwner.lifecycleScope.launch {
                    settingsRepository.setShowCalendarEvent(true)
                }
            } else {
                // Nutzer hat abgelehnt. Zeige Feedback.
                showToastSafe(R.string.calendar_permission_denied_toast)
            }
        }

    override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
        try {
            // "Ich weiss, dass das schlecht ist, aber die Library lässt mir keine Wahl!"

            // Workaround for an internal issue in the AndroidX Preference library:
            // setPreferencesFromResource triggers a synchronous disk read on the main
            // thread. We cannot offload it to a background dispatcher because it also
            // initializes View objects, which must happen on the main thread. Relax
            // StrictMode for the duration (DEBUG-only; see withRelaxedStrictMode).
            withRelaxedStrictMode {
                setPreferencesFromResource(R.xml.preferences, rootKey)
            }

            setupPreferenceListeners()

        } catch (e: Throwable) {
            // no suspension point — non-suspend onCreatePreferences inflation, cannot see CancellationException
            // setPreferencesFromResource liest XML — echter I/O-Pfad.
            TimberWrapper.silentError(e, "Error in onCreatePreferences")
        }
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        // Listener-Wiring selbst wirft nicht — alle Inner-Catches in den
        // Listener-Bodies machen die echte Defensive.
        calendarSwitchPreference = findPreference(AppConstants.PrefKeys.SHOW_CALENDAR_EVENT)
        calendarSwitchPreference?.setOnPreferenceChangeListener { _, newValue ->
            try {
                val shouldEnable = newValue as? Boolean ?: false

                if (shouldEnable) {
                    handleCalendarPermissionRequest()
                    // Rückgabe 'false' verhindert das automatische Toggle.
                    // Der Switch wird erst auf 'true' gesetzt, wenn die
                    // Berechtigung erteilt wurde (siehe observeSettings).
                    false
                } else {
                    // User möchte Feature deaktivieren -> direkt speichern
                    viewLifecycleOwner.lifecycleScope.launch {
                        settingsRepository.setShowCalendarEvent(false)
                    }
                    true // Erlaube das Toggle auf 'false'
                }
            } catch (e: Throwable) {
                // no suspension point — non-suspend preference listener, cannot see CancellationException
                TimberWrapper.silentError(e, "Error in calendar change listener")
                false
            }
        }

        alarmSwitchPreference = findPreference(AppConstants.PrefKeys.SHOW_ALARM)
        alarmSwitchPreference?.setOnPreferenceChangeListener { _, newValue ->
            try {
                val shouldEnable = newValue as? Boolean ?: true
                viewLifecycleOwner.lifecycleScope.launch {
                    settingsRepository.setShowAlarm(shouldEnable)
                }
                true
            } catch (e: Throwable) {
                // no suspension point — non-suspend preference listener, cannot see CancellationException
                TimberWrapper.silentError(e, "Error in alarm change listener")
                false
            }
        }

        doubleTapClipboardSwitchPreference = findPreference(AppConstants.PrefKeys.DOUBLE_TAP_CLIPBOARD)
        doubleTapClipboardSwitchPreference?.setOnPreferenceChangeListener { _, newValue ->
            try {
                val shouldEnable = newValue as? Boolean ?: false
                viewLifecycleOwner.lifecycleScope.launch {
                    settingsRepository.setDoubleTapClipboard(shouldEnable)
                }
                true
            } catch (e: Throwable) {
                // no suspension point — non-suspend preference listener, cannot see CancellationException
                TimberWrapper.silentError(e, "Error in doubleTapClipboard change listener")
                false
            }
        }

        autoKeyboardSwitchPreference = findPreference(AppConstants.PrefKeys.AUTO_SHOW_KEYBOARD)
        autoKeyboardSwitchPreference?.setOnPreferenceChangeListener { _, newValue ->
            try {
                val shouldEnable = newValue as? Boolean ?: false
                viewLifecycleOwner.lifecycleScope.launch {
                    settingsRepository.setAutoShowKeyboard(shouldEnable)
                }
                true
            } catch (e: Throwable) {
                // no suspension point — non-suspend preference listener, cannot see CancellationException
                TimberWrapper.silentError(e, "Error in autoKeyboard change listener")
                false
            }
        }

        autoLaunchAppSwitchPreference = findPreference(AppConstants.PrefKeys.AUTO_LAUNCH_APP)
        autoLaunchAppSwitchPreference?.setOnPreferenceChangeListener { _, newValue ->
            try {
                val shouldEnable = newValue as? Boolean ?: false
                viewLifecycleOwner.lifecycleScope.launch {
                    settingsRepository.setAutoLaunchApp(shouldEnable)
                }
                true
            } catch (e: Throwable) {
                // no suspension point — non-suspend preference listener, cannot see CancellationException
                TimberWrapper.silentError(e, "Error in autoLaunchApp change listener")
                false
            }
        }

        // Rotation Lock
        rotationLockedSwitchPreference = findPreference(AppConstants.PrefKeys.ROTATION_LOCKED)
        rotationLockedSwitchPreference?.setOnPreferenceChangeListener { _, newValue ->
            try {
                val shouldEnable = newValue as? Boolean ?: false
                viewLifecycleOwner.lifecycleScope.launch {
                    settingsRepository.setRotationLocked(shouldEnable)
                }
                true
            } catch (e: Throwable) {
                // no suspension point — non-suspend preference listener, cannot see CancellationException
                TimberWrapper.silentError(e, "Error in rotation lock change listener")
                false
            }
        }

        observeSettings()
        viewLifecycleOwner.lifecycleScope.launch {
            updateCrashReportSummary()
        }
    }

    override fun onResume() {
        super.onResume()
        // updateDefaultLauncherStatus hat eigene Catches für die echten
        // System-API-Calls (RoleManager). Ein Outer-Catch hier wäre tot.
        updateDefaultLauncherStatus()
    }

    private fun setupPreferenceListeners() {
        // Wallpaper — openSystemWallpaperPicker has its own try/catch + fallback path.
        findPreference<Preference>(AppConstants.PrefKeys.SYSTEM_WALLPAPER)?.setOnPreferenceClickListener {
            openSystemWallpaperPicker()
            true
        }

        // Edit Favorites
        findPreference<Preference>(AppConstants.PrefKeys.EDIT_FAVORITES)?.setOnPreferenceClickListener {
            try {
                val intent = Intent(requireActivity(), OnboardingActivity::class.java).apply {
                    putExtra(
                        OnboardingActivity.Companion.EXTRA_LAUNCH_MODE,
                        LaunchMode.EDIT_FAVORITES.name
                    )
                }
                startActivity(intent)
                true
            } catch (e: Throwable) {
                // no suspension point — non-suspend click listener, cannot see CancellationException
                TimberWrapper.silentError(e, "Error starting edit favorites")
                false
            }
        }

        // Sort Favorites
        findPreference<Preference>(AppConstants.PrefKeys.SORT_FAVORITES)?.setOnPreferenceClickListener {
            try {
                if (BuildConfig.DEBUG) EspressoIdlingResource.increment()

                viewLifecycleOwner.lifecycleScope.launch(Dispatchers.Main) {
                    try {
                        showSortFavoritesFragment()
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Throwable) {
                        TimberWrapper.silentError(e, "Error showing sort favorites")
                        viewModel.onAppListNotLoaded()
                    } finally {
                        if (BuildConfig.DEBUG) EspressoIdlingResource.decrement()
                    }
                }
                true
            } catch (e: Throwable) {
                // no suspension point — non-suspend click listener, cannot see CancellationException
                TimberWrapper.silentError(e, "Error in sort favorites click")
                if (BuildConfig.DEBUG) EspressoIdlingResource.decrement()
                false
            }
        }

        // Hidden Apps
        findPreference<Preference>(AppConstants.PrefKeys.HIDDEN_APPS)?.setOnPreferenceClickListener {
            try {
                if (BuildConfig.DEBUG) EspressoIdlingResource.increment()

                val intent = Intent(requireContext(), HiddenAppsActivity::class.java)
                startActivity(intent)

                if (BuildConfig.DEBUG) EspressoIdlingResource.decrement()
                true
            } catch (e: Throwable) {
                // no suspension point — non-suspend click listener, cannot see CancellationException
                TimberWrapper.silentError(e, "Error starting hidden apps")
                if (BuildConfig.DEBUG) EspressoIdlingResource.decrement()
                false
            }
        }

        // Custom App Names
        findPreference<Preference>(AppConstants.PrefKeys.CUSTOM_APP_NAMES)?.setOnPreferenceClickListener {
            try {
                val intent = Intent(requireActivity(), CustomNamesActivity::class.java)
                startActivity(intent)
                true
            } catch (e: Throwable) {
                // no suspension point — non-suspend click listener, cannot see CancellationException
                TimberWrapper.silentError(e, "Error starting app names activity")
                false
            }
        }

        // Backup & Restore
        findPreference<Preference>(AppConstants.PrefKeys.BACKUP_RESTORE)?.setOnPreferenceClickListener {
            try {
                if (!isAdded || isStateSaved || isDetached) {
                    Timber.w("Cannot show backup - invalid fragment state")
                    return@setOnPreferenceClickListener false
                }

                val fragment = BackupFragment()

                parentFragmentManager.beginTransaction()
                    .replace(android.R.id.content, fragment)
                    .addToBackStack(null)
                    .commitAllowingStateLoss()

                true
            } catch (e: Throwable) {
                // no suspension point — non-suspend fragment transaction, cannot see CancellationException
                TimberWrapper.silentError(e, "Error showing backup fragment")
                false
            }
        }

        // Factory Reset — showFactoryResetDialog has its own try/catch.
        findPreference<Preference>(AppConstants.PrefKeys.FACTORY_RESET)?.setOnPreferenceClickListener {
            showFactoryResetDialog()
            true
        }

        // Usage Export
        findPreference<Preference>(AppConstants.PrefKeys.USAGE_EXPORT)?.setOnPreferenceClickListener {
            try {
                if (!isAdded || isStateSaved || isDetached) {
                    Timber.w("Cannot show usage export - invalid fragment state")
                    return@setOnPreferenceClickListener false
                }

                val fragment = UsageExportFragment()

                parentFragmentManager.beginTransaction()
                    .replace(android.R.id.content, fragment)
                    .addToBackStack(null)
                    .commitAllowingStateLoss()

                true
            } catch (e: Throwable) {
                // no suspension point — non-suspend fragment transaction, cannot see CancellationException
                TimberWrapper.silentError(e, "Error showing usage export fragment")
                false
            }
        }

        // App Info
        findPreference<Preference>(AppConstants.PrefKeys.APP_INFO)?.setOnPreferenceClickListener {
            try {
                openUrlInCustomTab(
                    requireContext(),
                    AppConstants.URL_ABOUT_PAGE
                )
                true
            } catch (e: Throwable) {
                // no suspension point — non-suspend click listener, cannot see CancellationException
                TimberWrapper.silentError(e, "Error opening app info")
                false
            }
        }

        // Default Launcher — openDefaultLauncherSettings has its own try/catch.
        findPreference<Preference>(AppConstants.PrefKeys.SET_DEFAULT_LAUNCHER)?.setOnPreferenceClickListener {
            openDefaultLauncherSettings()
            true
        }

        // App Drawer Mode (Auto / Light / Dark)
        val wallpaperSurfaceModePreference =
            findPreference<ListPreference>(AppConstants.PrefKeys.APP_DRAWER_MODE)
        wallpaperSurfaceModePreference?.setOnPreferenceChangeListener { _, newValue ->
            if (newValue is String) {
                val mode = try {
                    WallpaperSurfaceMode.valueOf(newValue)
                } catch (e: IllegalArgumentException) {
                    TimberWrapper.silentError(e, "Unknown WallpaperSurfaceMode value: $newValue")
                    return@setOnPreferenceChangeListener false
                }
                viewLifecycleOwner.lifecycleScope.launch {
                    try {
                        settingsRepository.setWallpaperSurfaceMode(mode)
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Throwable) {
                        TimberWrapper.silentError(e, "Error setting WallpaperSurfaceMode")
                    }
                }
            }
            true
        }

        // Swipe Actions
        findPreference<Preference>(AppConstants.PrefKeys.SWIPE_ACTIONS)?.setOnPreferenceClickListener {
            try {
                val intent = Intent(requireContext(), SwipeActionsActivity::class.java)
                startActivity(intent)
                true
            } catch (e: Throwable) {
                // no suspension point — non-suspend click listener, cannot see CancellationException
                TimberWrapper.silentError(e, "Error starting swipe actions activity")
                false
            }
        }

        // Crash Reports
        findPreference<Preference>(AppConstants.PrefKeys.CRASH_REPORTS)?.setOnPreferenceClickListener {
            try {
                viewLifecycleOwner.lifecycleScope.launch(Dispatchers.Main) {
                    val activityContext = activity ?: return@launch

                    try {
                        // Track the forced-consent dialog like the other
                        // dialogs here so onDestroyView dismisses it — otherwise
                        // a rotation with it open leaks its window (it is
                        // setCancelable(false), so it stays up). AUDIT-3 #12.
                        currentDialog?.dismiss()
                        currentDialog = ConsentDialog.show(activityContext) { userGaveConsent ->
                            // Persist (on the app-lifetime scope) + apply to
                            // ACRA through the controller — one source for the
                            // sequence both callers used to duplicate
                            // (AUDIT-10 #12).
                            crashReportConsentController.applyConsent(userGaveConsent)

                            // Optional: Dem Nutzer Feedback geben
                            val feedbackMessage = if (userGaveConsent) {
                                getString(R.string.toast_crash_reports_enabled)
                            } else {
                                getString(R.string.toast_crash_reports_disabled)
                            }
                            activityContext.showToastSafe(feedbackMessage)

                            // onResult runs on the main thread, so reflect the
                            // just-made choice directly instead of re-reading
                            // the store on a separate scope — which could race
                            // the still-running persist write (AUDIT-10 #1).
                            applyCrashReportSummary(userGaveConsent)
                        }
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Throwable) {
                        TimberWrapper.silentError(e, "Error showing forced consent dialog")
                    }
                }
                true
            } catch (e: Throwable) {
                // no suspension point — non-suspend click listener, cannot see CancellationException
                TimberWrapper.silentError(e, "Error in crash reports preference click")
                false
            }
        }

        // Developer command: the ACRA pipeline-status probe (§8c, G3-C). Reads
        // the unsent-report backlog OFF the main thread and toasts it — a
        // growing backlog means the sender/server is dead; an empty backlog
        // means healthy OR never crashed (deliberately ambiguous; the accepted
        // limit of the ReportLocator baseline over a positive HTTP send-marker).
        findPreference<Preference>(AppConstants.PrefKeys.PIPELINE_STATUS)?.setOnPreferenceClickListener {
            viewLifecycleOwner.lifecycleScope.launch(Dispatchers.Main) {
                val backlog = withContext(Dispatchers.IO) { pipelineBacklogProbe.read() }
                val activityContext = activity ?: return@launch
                val message = if (backlog.approved == 0 && backlog.unapproved == 0) {
                    getString(R.string.toast_pipeline_status_empty)
                } else {
                    val oldest = backlog.oldestMillis
                        ?.let { DateUtils.getRelativeTimeSpanString(it).toString() }
                        ?: getString(R.string.pipeline_status_oldest_unknown)
                    getString(
                        R.string.toast_pipeline_status_backlog,
                        backlog.approved,
                        backlog.unapproved,
                        oldest,
                    )
                }
                activityContext.showToastSafe(message)
            }
            true
        }

        // Developer command: the ACRA throw-test shortcut. A throw in a
        // Preference click handler propagates straight to the global
        // UncaughtExceptionHandler — which for the throw-test button is exactly
        // what we WANT (it exercises the real uncaught path).
        findPreference<Preference>(AppConstants.PrefKeys.THROW_TEST_EXCEPTION)?.setOnPreferenceClickListener {
            // Toast first so the user sees the warning before the crash.
            // The Thread + Thread.sleep(800) gives Android time to render
            // the Toast (~300ms surface) before the throw lands. The throw
            // runs on a non-Main thread with no CoroutineExceptionHandler
            // attached, so it travels straight to ACRA's global
            // UncaughtExceptionHandler — the exact path a real user crash
            // takes.
            showToastSafe(R.string.toast_throwing_test_exception, Toast.LENGTH_LONG)
            Thread {
                try {
                    Thread.sleep(800)
                } catch (_: InterruptedException) {
                    // ignore — the throw below is the point
                }
                throw RuntimeException(
                    "ACRA developer-test crash from Settings (version ${BuildConfig.VERSION_NAME})",
                )
            }.start()
            true
        }
    }

    fun openUrlInCustomTab(context: Context, url: String) {
        try {
            val builder = CustomTabsIntent.Builder()
            val colorSchemeParams = CustomTabColorSchemeParams.Builder()
                .setToolbarColor(
                    context.resolveThemeColor(
                        com.google.android.material.R.attr.colorSurface,
                        ContextCompat.getColor(context, android.R.color.black),
                    )
                )
                .build()
            builder.setDefaultColorSchemeParams(colorSchemeParams)

            val customTabsIntent = builder.build()
            customTabsIntent.launchUrl(context, url.toUri())
        } catch (e: Throwable) {
            // no suspension point — non-suspend custom-tab launch, cannot see CancellationException
            TimberWrapper.silentError(e, "Could not open Custom Tab, falling back to standard browser.")
            try {
                val intent = Intent(Intent.ACTION_VIEW, url.toUri())
                context.startActivity(intent)
            } catch (fallbackError: Throwable) {
                // no suspension point — non-suspend fallback browser launch, cannot see CancellationException
                TimberWrapper.silentError(fallbackError, "Even the fallback browser intent failed.")
            }
        }
    }

    private suspend fun updateCrashReportSummary() {
        withContext(Dispatchers.Main) {
            // currentDecision() reports its outcome as a value (no throw for I/O),
            // so the old try/catch is gone: an unreadable store is the Unavailable
            // branch, which leaves the summary as-is — display only, no write
            // follows, so a stale summary is the safe outcome (A2). The failure is
            // already reported by the repository.
            when (val result = crashReportConsentController.currentDecision()) {
                is ConsentReadResult.Loaded ->
                    applyCrashReportSummary(result.decision == ConsentDecision.Granted)

                is ConsentReadResult.Unavailable -> Unit
            }
        }
    }

    /**
     * Sets the crash-report preference summary from a known consent value —
     * no store read. Must be called on the main thread.
     */
    private fun applyCrashReportSummary(isEnabled: Boolean) {
        val preference = findPreference<Preference>(AppConstants.PrefKeys.CRASH_REPORTS) ?: return
        preference.summary = getString(
            if (isEnabled) {
                R.string.crash_report_summary_enabled
            } else {
                R.string.crash_report_summary_disabled
            }
        )
    }

    private fun observeSettings() {
        viewLifecycleOwner.lifecycleScope.launch(Dispatchers.Main) {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                // Inner-Catches um die einzelnen `?.isChecked = X`-Setter
                // sind entfernt — Boolean-Property-Writes auf nullable
                // SwitchPreferenceCompat werfen nicht. Outer-Catches um
                // die `.collect { }`-Aufrufe bleiben (legitime Flow-
                // Failure-Pfade).

                // Observer für App-Liste
                launch {
                    try {
                        viewModel.installedApps.collect { apps ->
                            if (!isAdded || isDetached) return@collect

                            Timber.d("[Fragment] Collected ${apps.size} apps")

                            val sortFavoritesPref =
                                findPreference<Preference>(AppConstants.PrefKeys.SORT_FAVORITES)
                            val hiddenAppsPref =
                                findPreference<Preference>(AppConstants.PrefKeys.HIDDEN_APPS)

                            val isAppListReady = apps.isNotEmpty()

                            sortFavoritesPref?.isEnabled = isAppListReady
                            hiddenAppsPref?.isEnabled = isAppListReady
                        }
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Throwable) {
                        TimberWrapper.silentError(e, "Error in installed apps collection")
                    }
                }

                // Observer für Kalender-Einstellung
                launch {
                    try {
                        settingsRepository.showCalendarEventFlow.collect { isEnabled ->
                            if (!isAdded || isDetached) return@collect
                            calendarSwitchPreference?.isChecked = isEnabled
                        }
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Throwable) {
                        TimberWrapper.silentError(e, "Error in calendar flow collection")
                    }
                }

                // Observer für Alarm-Einstellung
                launch {
                    try {
                        settingsRepository.showAlarmFlow.collect { isEnabled ->
                            if (!isAdded || isDetached) return@collect
                            alarmSwitchPreference?.isChecked = isEnabled
                        }
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Throwable) {
                        TimberWrapper.silentError(e, "Error in alarm flow collection")
                    }
                }

                // Observer for AppDrawer Mode Setting
                launch {
                    try {
                        settingsRepository.wallpaperSurfaceModeFlow.collect { mode ->
                            if (!isAdded || isDetached) return@collect
                            findPreference<ListPreference>(AppConstants.PrefKeys.APP_DRAWER_MODE)?.value =
                                mode.name
                        }
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Throwable) {
                        TimberWrapper.silentError(e, "Error in wallpaperSurfaceMode flow collection")
                    }
                }

                // Observer for the double-tap clipboard-action setting
                launch {
                    try {
                        settingsRepository.doubleTapClipboardEnabledFlow.collect { isEnabled ->
                            if (!isAdded || isDetached) return@collect
                            doubleTapClipboardSwitchPreference?.isChecked = isEnabled
                        }
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Throwable) {
                        TimberWrapper.silentError(e, "Error in doubleTapClipboard flow collection")
                    }
                }

                // Observer für show Keyboard Setting
                launch {
                    try {
                        settingsRepository.autoShowKeyboardFlow.collect { isEnabled ->
                            if (!isAdded || isDetached) return@collect
                            autoKeyboardSwitchPreference?.isChecked = isEnabled
                        }
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Throwable) {
                        TimberWrapper.silentError(e, "Error in autoKeyboard flow collection")
                    }
                }

                launch {
                    try {
                        settingsRepository.autoLaunchAppFlow.collect { isEnabled ->
                            if (!isAdded || isDetached) return@collect
                            autoLaunchAppSwitchPreference?.isChecked = isEnabled
                        }
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Throwable) {
                        TimberWrapper.silentError(e, "Error in autoLaunchApp flow collection")
                    }
                }



                // Observer für Rotation Lock Setting
                launch {
                    try {
                        settingsRepository.rotationLockedFlow.collect { isEnabled ->
                            if (!isAdded || isDetached) return@collect
                            rotationLockedSwitchPreference?.isChecked = isEnabled
                        }
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Throwable) {
                        TimberWrapper.silentError(e, "Error in rotation lock flow collection")
                    }
                }


            }
        }
    }

    private suspend fun showSortFavoritesFragment() {
        // CRASH-SAFE: Check Fragment state
        if (!isAdded || isStateSaved || isDetached) {
            Timber.w("Cannot show sort favorites - invalid fragment state")
            return
        }

        // viewModel.installedApps.value ist ein StateFlow-Read — wirft nicht.
        val allApps = viewModel.installedApps.value

        if (allApps.isEmpty()) {
            viewModel.onAppListNotLoaded()
            return
        }

        val favoriteComponents = try {
            favoritesRepository.favoriteComponentsFlow.first()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            TimberWrapper.silentError(e, "Error getting favorite components")
            emptySet()
        }

        // filter mit Set.contains auf Strings — wirft nicht.
        val favoriteApps = allApps.filter { favoriteComponents.contains(it.componentName) }

        if (favoriteApps.isEmpty()) {
            viewModel.onNoFavoritesToSort()
            return
        }

        val savedOrder = try {
            favoritesOrderRepository.favoriteComponentsOrderFlow.first()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            TimberWrapper.silentError(e, "Error getting saved order")
            emptyList()
        }

        val orderedFavoriteApps = try {
            favoritesOrderRepository.sortFavoriteComponents(favoriteApps, savedOrder)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            TimberWrapper.silentError(e, "Error sorting favorites")
            favoriteApps
        }

        // CRASH-SAFE: Check state again before transaction
        if (!isAdded || isStateSaved || isDetached) {
            Timber.w("Fragment state changed during async operations")
            return
        }

        try {
            val fragment =
                FavoritesSortFragment.Companion.newInstance(ArrayList(orderedFavoriteApps))

            parentFragmentManager.beginTransaction()
                .replace(android.R.id.content, fragment)
                .addToBackStack(null)
                .commitAllowingStateLoss() // CRITICAL: Use commitAllowingStateLoss
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            // Fragment-Transaktionen werfen IllegalStateException unter
            // Race-Bedingungen mit dem Lifecycle.
            TimberWrapper.silentError(e, "Error committing fragment transaction")
            viewModel.onAppListNotLoaded()
        }
    }

    private fun openSystemWallpaperPicker() {
        try {
            val intent = Intent(Intent.ACTION_SET_WALLPAPER)
            startActivity(Intent.createChooser(intent, getString(R.string.wallpaper_picker_title)))
        } catch (e: Throwable) {
            // no suspension point — non-suspend wallpaper-picker launch, cannot see CancellationException
            TimberWrapper.silentError(e, "Error opening system wallpaper picker")
            openWallpaperSettings()
        }
    }

    private fun openWallpaperSettings() {
        try {
            startActivity(Intent(Settings.ACTION_DISPLAY_SETTINGS))
            viewModel.onWallpaperSettingsFallback()
        } catch (e: Throwable) {
            // no suspension point — non-suspend wallpaper-settings launch, cannot see CancellationException
            viewModel.onErrorOpeningWallpaperSettings(e)
        }
    }

    private fun updateDefaultLauncherStatus() {
        // Inner-Catches schützen die echten System-API-Calls (RoleManager).
        // Property-Writes danach werfen nicht; ein Outer-Catch wäre tot.
        val setDefaultLauncherPref =
            findPreference<Preference>(AppConstants.PrefKeys.SET_DEFAULT_LAUNCHER)
        if (setDefaultLauncherPref == null) {
            Timber.w("Default launcher preference not found")
            return
        }

        val roleManager = try {
            requireContext().getSystemService(RoleManager::class.java)
        } catch (e: Throwable) {
            // no suspension point — non-suspend RoleManager fetch, cannot see CancellationException
            TimberWrapper.silentError(e, "Error getting RoleManager")
            return
        }

        if (roleManager == null) {
            Timber.w("RoleManager is null")
            return
        }

        val isDefault = try {
            roleManager.isRoleHeld(RoleManager.ROLE_HOME)
        } catch (e: Throwable) {
            // no suspension point — non-suspend role-held check, cannot see CancellationException
            TimberWrapper.silentError(e, "Error checking role")
            false
        }

        if (isDefault) {
            setDefaultLauncherPref.summary = getString(R.string.default_launcher_is_set)
            setDefaultLauncherPref.isEnabled = false
        } else {
            setDefaultLauncherPref.summary = getString(R.string.set_default_launcher_summary)
            setDefaultLauncherPref.isEnabled = true
        }
    }

    // Die Berechtigungs-Logik
    // (Diese Funktion war bereits vorhanden und funktioniert jetzt)
    private fun handleCalendarPermissionRequest() {
        if (!isAdded) return

        try {
            when {
                ContextCompat.checkSelfPermission(
                    requireContext(),
                    CALENDAR_PERMISSION
                ) == PackageManager.PERMISSION_GRANTED -> {
                    // Berechtigung bereits vorhanden.
                    viewLifecycleOwner.lifecycleScope.launch {
                        settingsRepository.setShowCalendarEvent(true)
                    }
                }

                shouldShowRequestPermissionRationale(CALENDAR_PERMISSION) -> {
                    currentDialog?.dismiss()
                    currentDialog = MaterialAlertDialogBuilder(requireContext())
                        .setTitle(R.string.calendar_permission_title)
                        .setMessage(R.string.calendar_permission_rationale)
                        .setPositiveButton(android.R.string.ok) { _, _ ->
                            requestPermissionLauncher.launch(CALENDAR_PERMISSION)
                        }
                        .setNegativeButton(android.R.string.cancel, null)
                        .show()
                }

                else -> {
                    // Berechtigung direkt anfordern
                    requestPermissionLauncher.launch(CALENDAR_PERMISSION)
                }
            }
        } catch (e: Throwable) {
            // no suspension point — non-suspend permission request, cannot see CancellationException
            TimberWrapper.silentError(e, "Error handling calendar permission request")
        }
    }

    private fun openDefaultLauncherSettings() {
        try {
            startActivity(Intent(Settings.ACTION_HOME_SETTINGS))
        } catch (e: Throwable) {
            // no suspension point — non-suspend launcher-settings launch, cannot see CancellationException
            viewModel.onErrorOpeningDefaultLauncherSettings(e)
        }
    }

    /**
     * Zeigt den Bestätigungsdialog für das Zurücksetzen auf Werkseinstellungen an.
     * Bei Bestätigung wird die Logik im ViewModel aufgerufen.
     */
    private fun showFactoryResetDialog() {
        try {
            if (!isAdded) return

            // 1. Inflate das neue Layout
            val dialogView = LayoutInflater.from(requireContext())
                .inflate(R.layout.dialog_factory_reset, null)

            // 2. Finde die Checkbox
            val checkBox = dialogView.findViewById<MaterialCheckBox>(
                R.id.checkbox_include_usage_data
            )

            currentDialog?.dismiss()
            currentDialog = MaterialAlertDialogBuilder(requireContext())
                .setTitle(R.string.factory_reset_dialog_title)
                // 3. Setze das View statt einer Message
                .setView(dialogView)
                .setNegativeButton(R.string.cancel, null)
                .setPositiveButton(R.string.reset) { _, _ ->
                    // 4. Lese den Wert der Checkbox aus
                    val includeUsageData = checkBox.isChecked

                    // 5. Übergebe den Wert an das ViewModel
                    viewModel.onFactoryResetConfirmed(includeUsageData)
                }
                .show()
        } catch (e: Throwable) {
            // no suspension point — non-suspend factory-reset dialog, cannot see CancellationException
            TimberWrapper.silentError(e, "Cannot show factory reset dialog")
        }
    }

    override fun onDestroyView() {
        // Property-Writes (Listener auf null setzen, Field auf null
        // setzen) — werfen nicht. Frühere try/catch um den Body war
        // CANT_THROW. super.onDestroyView() bleibt am Ende.
        calendarSwitchPreference?.onPreferenceChangeListener = null
        alarmSwitchPreference?.onPreferenceChangeListener = null
        doubleTapClipboardSwitchPreference?.onPreferenceChangeListener = null
        autoKeyboardSwitchPreference?.onPreferenceChangeListener = null
        autoLaunchAppSwitchPreference?.onPreferenceChangeListener = null
        rotationLockedSwitchPreference?.onPreferenceChangeListener = null

        calendarSwitchPreference = null
        alarmSwitchPreference = null
        doubleTapClipboardSwitchPreference = null
        autoKeyboardSwitchPreference = null
        autoLaunchAppSwitchPreference = null
        rotationLockedSwitchPreference = null

        currentDialog?.dismiss()
        currentDialog = null

        super.onDestroyView()
    }
}
