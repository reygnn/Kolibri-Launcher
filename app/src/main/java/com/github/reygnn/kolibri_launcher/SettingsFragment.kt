package com.github.reygnn.kolibri_launcher

import android.app.role.RoleManager
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import android.view.View
import android.widget.Toast
import androidx.browser.customtabs.CustomTabsIntent
import androidx.core.content.ContextCompat
import androidx.core.os.bundleOf
import androidx.fragment.app.setFragmentResult
import androidx.fragment.app.viewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.preference.ListPreference
import androidx.preference.Preference
import androidx.preference.PreferenceFragmentCompat
import androidx.preference.SwitchPreferenceCompat
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.acra.ACRA
import timber.log.Timber
import javax.inject.Inject
import android.util.TypedValue
import androidx.annotation.AttrRes
import androidx.browser.customtabs.CustomTabColorSchemeParams
import androidx.core.net.toUri
import com.google.android.material.R as MaterialR

/**
 * CRASH-SAFE VERSION
 *
 * Crash safety through:
 * - Try-catch around all lifecycleScope.launch blocks
 * - Safe preference access with null checks
 * - commitAllowingStateLoss for fragment transactions
 * - Safe suspend function calls with error handling
 * - Lifecycle-aware coroutines with proper error handling
 * - Safe Intent handling
 * - Defensive checks for Fragment state
 */
@AndroidEntryPoint
class SettingsFragment : PreferenceFragmentCompat() {

    companion object {
        const val READABILITY_CHANGED_KEY = "readability_changed_key"
    }

    private val viewModel: SettingsViewModel by viewModels({ requireActivity() })

    @Inject
    lateinit var appVisibilityManager: AppVisibilityRepository
    @Inject
    lateinit var favoritesManager: FavoritesRepository
    @Inject
    lateinit var favoritesOrderManager: FavoritesOrderRepository
    @Inject
    lateinit var settingsManager: SettingsRepository
    @Inject
    lateinit var screenLockManager: ScreenLockRepository

    override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
        try {
            setPreferencesFromResource(R.xml.preferences, rootKey)

            findPreference<ListPreference>("text_readability_mode")?.summaryProvider =
                ListPreference.SimpleSummaryProvider.getInstance()

            setupPreferenceListeners()
        } catch (e: Exception) {
            TimberWrapper.silentError(e, "Error in onCreatePreferences")
        }
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        try {
            observeSettings()
            viewLifecycleOwner.lifecycleScope.launch {
                updateCrashReportSummary()
            }
        } catch (e: Exception) {
            TimberWrapper.silentError(e, "Error in onViewCreated")
        }
    }

    override fun onResume() {
        super.onResume()

        try {
            updateDefaultLauncherStatus()
        } catch (e: Exception) {
            TimberWrapper.silentError(e, "Error in onResume")
        }
    }

    private fun setupPreferenceListeners() {
        // Wallpaper
        try {
            findPreference<Preference>("system_wallpaper")?.setOnPreferenceClickListener {
                try {
                    openSystemWallpaperPicker()
                    true
                } catch (e: Exception) {
                    TimberWrapper.silentError(e, "Error in wallpaper preference click")
                    false
                }
            }
        } catch (e: Exception) {
            TimberWrapper.silentError(e, "Error setting wallpaper preference listener")
        }

        // Edit Favorites
        try {
            findPreference<Preference>("edit_favorites")?.setOnPreferenceClickListener {
                try {
                    val intent = Intent(requireActivity(), OnboardingActivity::class.java).apply {
                        putExtra(OnboardingActivity.EXTRA_LAUNCH_MODE, LaunchMode.EDIT_FAVORITES.name)
                    }
                    startActivity(intent)
                    true
                } catch (e: Exception) {
                    TimberWrapper.silentError(e, "Error starting edit favorites")
                    false
                }
            }
        } catch (e: Exception) {
            TimberWrapper.silentError(e, "Error setting edit favorites listener")
        }

        // Sort Favorites
        try {
            findPreference<Preference>("sort_favorites")?.setOnPreferenceClickListener {
                try {
                    if (BuildConfig.DEBUG) EspressoIdlingResource.increment()

                    viewLifecycleOwner.lifecycleScope.launch {
                        try {
                            showSortFavoritesFragment()
                        } catch (e: CancellationException) {
                            throw e
                        } catch (e: Exception) {
                            TimberWrapper.silentError(e, "Error showing sort favorites")
                            viewModel.onAppListNotLoaded()
                        } finally {
                            if (BuildConfig.DEBUG) EspressoIdlingResource.decrement()
                        }
                    }
                    true
                } catch (e: Exception) {
                    TimberWrapper.silentError(e, "Error in sort favorites click")
                    if (BuildConfig.DEBUG) EspressoIdlingResource.decrement()
                    false
                }
            }
        } catch (e: Exception) {
            TimberWrapper.silentError(e, "Error setting sort favorites listener")
        }

        // Hidden Apps
        try {
            findPreference<Preference>("hidden_apps")?.setOnPreferenceClickListener {
                try {
                    if (BuildConfig.DEBUG) EspressoIdlingResource.increment()

                    val intent = Intent(requireContext(), HiddenAppsActivity::class.java)
                    startActivity(intent)

                    if (BuildConfig.DEBUG) EspressoIdlingResource.decrement()
                    true
                } catch (e: Exception) {
                    TimberWrapper.silentError(e, "Error starting hidden apps")
                    if (BuildConfig.DEBUG) EspressoIdlingResource.decrement()
                    false
                }
            }
        } catch (e: Exception) {
            TimberWrapper.silentError(e, "Error setting hidden apps listener")
        }

        // Custom App Names
        try {
            findPreference<Preference>("custom_app_names")?.setOnPreferenceClickListener {
                try {
                    val intent = Intent(requireActivity(), AppNamesActivity::class.java)
                    startActivity(intent)
                    true
                } catch (e: Exception) {
                    TimberWrapper.silentError(e, "Error starting app names activity")
                    false
                }
            }
        } catch (e: Exception) {
            TimberWrapper.silentError(e, "Error setting custom app names listener")
        }

        // App Info
        try {
            findPreference<Preference>("app_info")?.setOnPreferenceClickListener {
                try {
                    openUrlInCustomTab(requireContext(), "https://docs.kolibri-launcher.ch/about.html")
                    true
                } catch (e: Exception) {
                    TimberWrapper.silentError(e, "Error opening app info")
                    false
                }
            }
        } catch (e: Exception) {
            TimberWrapper.silentError(e, "Error setting app info listener")
        }

        // Accessibility
        try {
            findPreference<Preference>("accessibility")?.setOnPreferenceClickListener {
                try {
                    openAccessibilitySettings()
                    true
                } catch (e: Exception) {
                    TimberWrapper.silentError(e, "Error opening accessibility")
                    false
                }
            }
        } catch (e: Exception) {
            TimberWrapper.silentError(e, "Error setting accessibility listener")
        }

        // Default Launcher
        try {
            findPreference<Preference>("set_default_launcher")?.setOnPreferenceClickListener {
                try {
                    openDefaultLauncherSettings()
                    true
                } catch (e: Exception) {
                    TimberWrapper.silentError(e, "Error opening default launcher settings")
                    false
                }
            }
        } catch (e: Exception) {
            TimberWrapper.silentError(e, "Error setting default launcher listener")
        }

        // Double Tap to Lock
        try {
            val doubleTapPreference = findPreference<SwitchPreferenceCompat>("double_tap_to_lock_enabled")
            doubleTapPreference?.setOnPreferenceChangeListener { _, newValue ->
                try {
                    if (newValue is Boolean) {
                        lifecycleScope.launch {
                            try {
                                settingsManager.setDoubleTapToLock(newValue)
                            } catch (e: CancellationException) {
                                throw e
                            } catch (e: Exception) {
                                TimberWrapper.silentError(e, "Error setting double tap to lock")
                            }
                        }
                    }
                    true
                } catch (e: Exception) {
                    TimberWrapper.silentError(e, "Error in double tap preference change")
                    false
                }
            }
        } catch (e: Exception) {
            TimberWrapper.silentError(e, "Error setting double tap listener")
        }

        // Readability Mode
        try {
            val readabilityPreference = findPreference<ListPreference>("text_readability_mode")
            readabilityPreference?.setOnPreferenceChangeListener { _, newValue ->
                try {
                    if (newValue is String) {
                        lifecycleScope.launch {
                            try {
                                settingsManager.setReadabilityMode(newValue)
                                setFragmentResult(READABILITY_CHANGED_KEY, bundleOf("changed" to true))
                            } catch (e: CancellationException) {
                                throw e
                            } catch (e: Exception) {
                                TimberWrapper.silentError(e, "Error setting readability mode")
                            }
                        }
                    }
                    true
                } catch (e: Exception) {
                    TimberWrapper.silentError(e, "Error in readability preference change")
                    false
                }
            }
        } catch (e: Exception) {
            TimberWrapper.silentError(e, "Error setting readability listener")
        }

        // Crash Reports
        try {
            findPreference<Preference>("crash_reports")?.setOnPreferenceClickListener {
                try {
                    // Wir starten eine Coroutine, um die suspend-Funktion aufzurufen
                    viewLifecycleOwner.lifecycleScope.launch {
                        // Wir benötigen den Activity-Context, um den Dialog zu zeigen
                        val activityContext = activity ?: return@launch

                        try {
                            CrashReportConsent.forceShowConsentDialog(activityContext) { userGaveConsent ->
                                // Hier aktualisieren wir ACRA sofort nach der Entscheidung des Benutzers
                                ACRA.errorReporter.setEnabled(userGaveConsent)
                                Timber.i("User consent for crash reports manually changed to: $userGaveConsent")

                                // Optional: Dem Nutzer Feedback geben
                                val feedbackMessage = if (userGaveConsent) {
                                    getString(R.string.toast_crash_reports_enabled)
                                } else {
                                    getString(R.string.toast_crash_reports_disabled)
                                }
                                Toast.makeText(activityContext, feedbackMessage, Toast.LENGTH_SHORT).show()

                                viewLifecycleOwner.lifecycleScope.launch {
                                    updateCrashReportSummary()
                                }
                            }
                        } catch (e: CancellationException) {
                            throw e
                        } catch (e: Exception) {
                            TimberWrapper.silentError(e, "Error showing forced consent dialog")
                        }
                    }
                    true
                } catch (e: Exception) {
                    TimberWrapper.silentError(e, "Error in crash reports preference click")
                    false
                }
            }
        } catch (e: Exception) {
            TimberWrapper.silentError(e, "Error setting crash reports listener")
        }
    }

    fun getThemeColor(context: Context, @AttrRes attrRes: Int): Int {
        try {
            val typedValue = TypedValue()
            if (context.theme.resolveAttribute(attrRes, typedValue, true)) {
                return typedValue.data
            }
        } catch (e: Exception) {
            Timber.e(e, "Failed to resolve theme color attribute: $attrRes")
        }
        // Fallback, wenn die Farbe nicht gefunden werden konnte
        return ContextCompat.getColor(context, android.R.color.black)
    }

    fun openUrlInCustomTab(context: Context, url: String) {
        try {
            val builder = CustomTabsIntent.Builder()
            val colorSchemeParams = CustomTabColorSchemeParams.Builder()
                .setToolbarColor(getThemeColor(context, MaterialR.attr.colorSurface))
                .build()
            builder.setDefaultColorSchemeParams(colorSchemeParams)

            val customTabsIntent = builder.build()
            customTabsIntent.launchUrl(context, url.toUri())
        } catch (e: Exception) {
            Timber.e(e, "Could not open Custom Tab, falling back to standard browser.")
            try {
                val intent = Intent(Intent.ACTION_VIEW, url.toUri())
                context.startActivity(intent)
            } catch (fallbackError: Exception) {
                Timber.e(fallbackError, "Even the fallback browser intent failed.")
            }
        }
    }

    private suspend fun updateCrashReportSummary() {
        withContext(Dispatchers.Main) {
            try {
                val preference = findPreference<Preference>("crash_reports") ?: return@withContext
                val isEnabled = CrashReportConsent.hasConsent(requireContext())

                if (isEnabled) {
                    preference.summary = getString(R.string.crash_report_summary_enabled)
                } else {
                    preference.summary = getString(R.string.crash_report_summary_disabled)
                }
            } catch (e: Exception) {
                TimberWrapper.silentError(e, "Could not update crash report summary")
            }
        }
    }

    private fun observeSettings() {
        viewLifecycleOwner.lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                // Observer für App-Liste
                launch {
                    try {
                        viewModel.installedApps.collect { apps ->
                            if (!isAdded || isDetached) return@collect

                            try {
                                Timber.d("[Fragment] Collected ${apps.size} apps")

                                val sortFavoritesPref = findPreference<Preference>("sort_favorites")
                                val hiddenAppsPref = findPreference<Preference>("hidden_apps")

                                val isAppListReady = apps.isNotEmpty()

                                sortFavoritesPref?.isEnabled = isAppListReady
                                hiddenAppsPref?.isEnabled = isAppListReady
                            } catch (e: Exception) {
                                TimberWrapper.silentError(e, "Error updating preference states")
                            }
                        }
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        TimberWrapper.silentError(e, "Error in installed apps collection")
                    }
                }

                // Observer für Double Tap Setting
                launch {
                    try {
                        settingsManager.doubleTapToLockEnabledFlow.collect { isChecked ->
                            if (!isAdded || isDetached) return@collect

                            try {
                                findPreference<SwitchPreferenceCompat>("double_tap_to_lock_enabled")?.isChecked = isChecked
                            } catch (e: Exception) {
                                TimberWrapper.silentError(e, "Error updating double tap preference")
                            }
                        }
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        TimberWrapper.silentError(e, "Error in double tap flow collection")
                    }
                }

                // Observer für Readability Setting
                launch {
                    try {
                        settingsManager.readabilityModeFlow.collect { currentMode ->
                            if (!isAdded || isDetached) return@collect

                            try {
                                findPreference<ListPreference>("text_readability_mode")?.value = currentMode
                            } catch (e: Exception) {
                                TimberWrapper.silentError(e, "Error updating readability preference")
                            }
                        }
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        TimberWrapper.silentError(e, "Error in readability flow collection")
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

        try {
            val allApps = try {
                viewModel.installedApps.value
            } catch (e: Exception) {
                TimberWrapper.silentError(e, "Error getting installed apps")
                emptyList()
            }

            if (allApps.isEmpty()) {
                viewModel.onAppListNotLoaded()
                return
            }

            val favoriteComponents = try {
                favoritesManager.favoriteComponentsFlow.first()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                TimberWrapper.silentError(e, "Error getting favorite components")
                emptySet()
            }

            val favoriteApps = try {
                allApps.filter { favoriteComponents.contains(it.componentName) }
            } catch (e: Exception) {
                TimberWrapper.silentError(e, "Error filtering favorite apps")
                emptyList()
            }

            if (favoriteApps.isEmpty()) {
                viewModel.onNoFavoritesToSort()
                return
            }

            val savedOrder = try {
                favoritesOrderManager.favoriteComponentsOrderFlow.first()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                TimberWrapper.silentError(e, "Error getting saved order")
                emptyList()
            }

            val orderedFavoriteApps = try {
                favoritesOrderManager.sortFavoriteComponents(favoriteApps, savedOrder)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                TimberWrapper.silentError(e, "Error sorting favorites")
                favoriteApps
            }

            // CRASH-SAFE: Check state again before transaction
            if (!isAdded || isStateSaved || isDetached) {
                Timber.w("Fragment state changed during async operations")
                return
            }

            try {
                val fragment = FavoritesSortFragment.newInstance(ArrayList(orderedFavoriteApps))

                parentFragmentManager.beginTransaction()
                    .replace(android.R.id.content, fragment)
                    .addToBackStack(null)
                    .commitAllowingStateLoss() // CRITICAL: Use commitAllowingStateLoss
            } catch (e: Exception) {
                TimberWrapper.silentError(e, "Error committing fragment transaction")
                viewModel.onAppListNotLoaded()
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            TimberWrapper.silentError(e, "Error in showSortFavoritesFragment")
            viewModel.onAppListNotLoaded()
        }
    }

    private fun openSystemWallpaperPicker() {
        try {
            val intent = Intent(Intent.ACTION_SET_WALLPAPER)
            startActivity(Intent.createChooser(intent, getString(R.string.wallpaper_picker_title)))
        } catch (e: Exception) {
            TimberWrapper.silentError(e, "Error opening system wallpaper picker")
            openWallpaperSettings()
        }
    }

    private fun openWallpaperSettings() {
        try {
            startActivity(Intent(Settings.ACTION_DISPLAY_SETTINGS))
            viewModel.onWallpaperSettingsFallback()
        } catch (e: Exception) {
            viewModel.onErrorOpeningWallpaperSettings(e)
        }
    }

    private fun openAccessibilitySettings() {
        try {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        } catch (e: Exception) {
            viewModel.onErrorOpeningAccessibilitySettings(e)
        }
    }

    private fun updateDefaultLauncherStatus() {
        try {
            val setDefaultLauncherPref = findPreference<Preference>("set_default_launcher")
            if (setDefaultLauncherPref == null) {
                Timber.w("Default launcher preference not found")
                return
            }

            val roleManager = try {
                requireContext().getSystemService(RoleManager::class.java)
            } catch (e: Exception) {
                TimberWrapper.silentError(e, "Error getting RoleManager")
                return
            }

            if (roleManager == null) {
                Timber.w("RoleManager is null")
                return
            }

            val isDefault = try {
                roleManager.isRoleHeld(RoleManager.ROLE_HOME)
            } catch (e: Exception) {
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
        } catch (e: Exception) {
            TimberWrapper.silentError(e, "Error updating default launcher status")
        }
    }

    private fun openDefaultLauncherSettings() {
        try {
            startActivity(Intent(Settings.ACTION_HOME_SETTINGS))
        } catch (e: Exception) {
            viewModel.onErrorOpeningDefaultLauncherSettings(e)
        }
    }
}