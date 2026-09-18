package com.github.reygnn.nyx_launcher.settings

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.Settings
import androidx.core.app.NotificationManagerCompat
import android.os.Bundle
import android.view.View
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.preference.Preference
import androidx.preference.PreferenceCategory
import androidx.preference.PreferenceFragmentCompat
import androidx.preference.SwitchPreferenceCompat
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import androidx.appcompat.app.AlertDialog
import com.github.reygnn.launcher.common.ui.showToastSafe
import com.github.reygnn.launcher.core.TimberWrapper
import com.github.reygnn.launcher.feature.crashreporting.consent.ConsentController
import com.github.reygnn.launcher.feature.crashreporting.consent.ConsentDialog
import com.github.reygnn.nyx_launcher.BuildConfig
import com.github.reygnn.nyx_launcher.R
import com.github.reygnn.nyx_launcher.data.home.NyxWallpaperImageSetter
import com.github.reygnn.nyx_launcher.home.model.ImportResult
import com.github.reygnn.nyx_launcher.home.repository.PreferencesRepository
import com.github.reygnn.nyx_launcher.data.home.NyxBackupManager
import com.github.reygnn.nyx_launcher.data.home.NyxBackupOptions
import com.github.reygnn.nyx_launcher.data.home.NyxResetManager
import com.github.reygnn.nyx_launcher.home.FirstRunSeeder
import com.github.reygnn.nyx_launcher.home.model.HiddenAppsSelection
import com.github.reygnn.nyx_launcher.home.model.displayName
import com.github.reygnn.nyx_launcher.home.repository.HiddenAppsRepository
import com.github.reygnn.nyx_launcher.home.usecase.GetDrawerAppsUseCase
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import timber.log.Timber
import javax.inject.Inject

/**
 * Settings as a Material 3 [PreferenceFragmentCompat] with categorised rows
 * (mirrors Kolibri's SettingsFragment). Preferences are NOT auto-persisted to
 * SharedPreferences (`isPersistent = false`) — the DataStore-backed
 * [PreferencesRepository] is the single source of truth: the switch drives it on
 * change and its flow drives the switch's checked state.
 */
@AndroidEntryPoint
class SettingsFragment : PreferenceFragmentCompat() {

    @Inject lateinit var backupManager: NyxBackupManager
    @Inject lateinit var resetManager: NyxResetManager
    @Inject lateinit var firstRunSeeder: FirstRunSeeder
    @Inject lateinit var preferences: PreferencesRepository
    @Inject lateinit var wallpaperImageSetter: NyxWallpaperImageSetter
    @Inject lateinit var getDrawerApps: GetDrawerAppsUseCase
    @Inject lateinit var hiddenAppsRepository: HiddenAppsRepository
    @Inject lateinit var consentController: ConsentController

    private var crashReportPref: Preference? = null
    // ConsentDialog is setCancelable(false); tracked so onDestroyView can dismiss it.
    private var consentDialog: AlertDialog? = null

    private var monochromeSwitch: SwitchPreferenceCompat? = null
    private var notificationDotsSwitch: SwitchPreferenceCompat? = null
    private var searchAutoLaunchSwitch: SwitchPreferenceCompat? = null
    private var calendarSwitch: SwitchPreferenceCompat? = null
    private var alarmSwitch: SwitchPreferenceCompat? = null

    // Calendar events are permission-gated (HIE-INV-6): the toggle only flips on
    // once READ_CALENDAR is granted (the flow observer then checks it).
    private val requestCalendarPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (granted) {
                lifecycleScope.launch { preferences.setShowCalendarEvent(true) }
            } else {
                toast(getString(R.string.calendar_permission_denied_toast))
            }
        }

    // Wallpaper image pick (WV5 v1): Android's permissionless photo picker,
    // single image. The multi-image gallery path (READ_MEDIA_IMAGES) comes with
    // the reduced edit mode (WV5d).
    private val pickWallpaperImage =
        registerForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
            uri?.let { setWallpaperFromUri(it) }
        }

    private val createDocument =
        registerForActivityResult(ActivityResultContracts.CreateDocument("application/zip")) { uri ->
            uri?.let(::doExport)
        }

    private val openDocument =
        registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            uri?.let(::doImport)
        }

    override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
        setPreferencesFromResource(R.xml.nyx_preferences, rootKey)

        monochromeSwitch = findPreference<SwitchPreferenceCompat>("monochrome_icons")?.apply {
            isPersistent = false // DataStore is the source of truth, not SharedPreferences
            setOnPreferenceChangeListener { _, newValue ->
                lifecycleScope.launch { preferences.setMonochromeIcons(newValue as Boolean) }
                true
            }
        }

        notificationDotsSwitch = findPreference<SwitchPreferenceCompat>("notification_dots")?.apply {
            isPersistent = false
            setOnPreferenceChangeListener { _, newValue ->
                val enabled = newValue as Boolean
                lifecycleScope.launch { preferences.setNotificationDots(enabled) }
                // Enabling only gates rendering — dots need notification access. If it's
                // not granted yet, send the user to the system screen to grant it.
                if (enabled && !hasNotificationAccess()) {
                    toast(getString(R.string.notification_dots_grant_toast))
                    openNotificationAccessSettings()
                }
                true
            }
        }

        searchAutoLaunchSwitch = findPreference<SwitchPreferenceCompat>("search_auto_launch")?.apply {
            isPersistent = false
            setOnPreferenceChangeListener { _, newValue ->
                lifecycleScope.launch { preferences.setSearchAutoLaunch(newValue as Boolean) }
                true
            }
        }

        alarmSwitch = findPreference<SwitchPreferenceCompat>("show_alarm")?.apply {
            isPersistent = false
            setOnPreferenceChangeListener { _, newValue ->
                lifecycleScope.launch { preferences.setShowAlarm(newValue as Boolean) }
                true
            }
        }

        calendarSwitch = findPreference<SwitchPreferenceCompat>("show_calendar_event")?.apply {
            isPersistent = false
            setOnPreferenceChangeListener { _, newValue ->
                if (newValue as Boolean) {
                    // Don't flip the switch yet — flip it only once permission is
                    // granted (the flow observer sets isChecked after the write).
                    handleCalendarPermissionRequest()
                    false
                } else {
                    lifecycleScope.launch { preferences.setShowCalendarEvent(false) }
                    true
                }
            }
        }

        findPreference<Preference>("choose_wallpaper")?.setOnPreferenceClickListener {
            pickWallpaperImage.launch(
                PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly),
            )
            true
        }
        findPreference<Preference>("clear_wallpaper")?.setOnPreferenceClickListener {
            lifecycleScope.launch {
                wallpaperImageSetter.clear()
                toast(getString(R.string.wallpaper_cleared_toast))
            }
            true
        }

        findPreference<Preference>("hidden_apps")?.setOnPreferenceClickListener {
            showHiddenAppsManager()
            true
        }

        findPreference<Preference>("export_layout")?.setOnPreferenceClickListener {
            createDocument.launch("nyx-backup.zip")
            true
        }
        findPreference<Preference>("import_layout")?.setOnPreferenceClickListener {
            openDocument.launch(arrayOf("application/zip", "application/octet-stream", "*/*"))
            true
        }
        findPreference<Preference>("factory_reset")?.setOnPreferenceClickListener {
            showFactoryResetDialog()
            true
        }

        crashReportPref = findPreference<Preference>("crash_reports")?.apply {
            setOnPreferenceClickListener {
                showCrashReportConsentDialog()
                true
            }
        }

        setupDevCommands()
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        // Reflect the stored crash-report decision in the preference summary.
        viewLifecycleOwner.lifecycleScope.launch { refreshCrashReportSummary() }
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch {
                    preferences.monochromeIcons().collect { enabled ->
                        if (monochromeSwitch?.isChecked != enabled) monochromeSwitch?.isChecked = enabled
                    }
                }
                launch {
                    preferences.notificationDots().collect { enabled ->
                        if (notificationDotsSwitch?.isChecked != enabled) notificationDotsSwitch?.isChecked = enabled
                    }
                }
                launch {
                    preferences.searchAutoLaunch().collect { enabled ->
                        if (searchAutoLaunchSwitch?.isChecked != enabled) searchAutoLaunchSwitch?.isChecked = enabled
                    }
                }
                launch {
                    preferences.showAlarmFlow.collect { enabled ->
                        if (alarmSwitch?.isChecked != enabled) alarmSwitch?.isChecked = enabled
                    }
                }
                launch {
                    preferences.showCalendarEventFlow.collect { enabled ->
                        if (calendarSwitch?.isChecked != enabled) calendarSwitch?.isChecked = enabled
                    }
                }
            }
        }
    }

    /**
     * Enabling the calendar toggle needs READ_CALENDAR: if already granted, persist
     * true; if a rationale is due, explain then request; otherwise request directly.
     * The switch itself flips on only via the [preferences.showCalendarEventFlow]
     * observer after the write (HIE-INV-6).
     */
    private fun handleCalendarPermissionRequest() {
        when {
            ContextCompat.checkSelfPermission(requireContext(), Manifest.permission.READ_CALENDAR) ==
                PackageManager.PERMISSION_GRANTED -> {
                lifecycleScope.launch { preferences.setShowCalendarEvent(true) }
            }
            shouldShowRequestPermissionRationale(Manifest.permission.READ_CALENDAR) -> {
                MaterialAlertDialogBuilder(requireContext())
                    .setTitle(R.string.calendar_permission_title)
                    .setMessage(R.string.calendar_permission_rationale)
                    .setPositiveButton(android.R.string.ok) { _, _ ->
                        requestCalendarPermission.launch(Manifest.permission.READ_CALENDAR)
                    }
                    .setNegativeButton(android.R.string.cancel, null)
                    .show()
            }
            else -> requestCalendarPermission.launch(Manifest.permission.READ_CALENDAR)
        }
    }

    /**
     * ACRA dev commands (mirrors Kolibri's), gated by BuildConfig.SHOW_DEV_COMMANDS
     * (always on in debug; release only with -PdevCommands / -PdailyDriver). The
     * whole category is hidden when the flag is off.
     */
    private fun setupDevCommands() {
        val category = findPreference<PreferenceCategory>("dev_commands")
        if (!BuildConfig.SHOW_DEV_COMMANDS) {
            category?.isVisible = false
            return
        }
        findPreference<Preference>("dev_throw_test")?.setOnPreferenceClickListener {
            throw RuntimeException("ACRA throw test from Nyx settings (v${BuildConfig.VERSION_NAME})")
        }
        findPreference<Preference>("dev_silent_error")?.setOnPreferenceClickListener {
            TimberWrapper.silentError(
                RuntimeException("ACRA silent-error test from Nyx (v${BuildConfig.VERSION_NAME})"),
                "Nyx ACRA silent-error test",
            )
            true
        }
        findPreference<Preference>("dev_warn_test")?.setOnPreferenceClickListener {
            Timber.w("Nyx ACRA warn test — untagged, must NOT reach the server")
            true
        }
    }

    private fun doExport(uri: Uri) = lifecycleScope.launch {
        // Open the SAF stream off the main thread (a DocumentsProvider binder IPC
        // can block); the manager also hops to IO for the ZIP transfer.
        val ok = runCatching {
            withContext(Dispatchers.IO) {
                requireContext().contentResolver.openOutputStream(uri)?.use { out ->
                    backupManager.export(out, BuildConfig.VERSION_NAME, System.currentTimeMillis())
                } ?: false
            }
        }.getOrDefault(false)
        toast(getString(if (ok) R.string.backup_export_done else R.string.backup_export_failed))
    }

    private fun doImport(uri: Uri) = lifecycleScope.launch {
        val result = runCatching {
            withContext(Dispatchers.IO) {
                requireContext().contentResolver.openInputStream(uri)?.use { inp ->
                    backupManager.import(inp, NyxBackupOptions())
                }
            }
        }.getOrNull()
        when (result) {
            ImportResult.Success -> {
                toast(getString(R.string.backup_import_done))
                requireActivity().finish() // back to home, which re-renders from the restored state
            }
            ImportResult.InvalidData -> toast(getString(R.string.backup_import_invalid))
            null -> toast(getString(R.string.backup_import_failed))
        }
    }

    /**
     * Hidden-apps manager: a multi-choice list of ALL apps, checked = hidden. Loads the app
     * list + current hidden set off the current dispatcher, then applies the diff as one
     * atomic write on OK. The drawer re-renders reactively (HomeViewModel.hiddenApps).
     */
    private fun showHiddenAppsManager() = lifecycleScope.launch {
        val apps = getDrawerApps()
        if (apps.isEmpty()) {
            toast(getString(R.string.settings_hidden_apps_empty))
            return@launch
        }
        val hidden = hiddenAppsRepository.hidden().first()
        val labels = apps.map { it.displayName }.toTypedArray()
        val checked = BooleanArray(apps.size) { apps[it].key in hidden }
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.settings_hidden_apps_title)
            .setMultiChoiceItems(labels, checked) { _, which, isChecked -> checked[which] = isChecked }
            .setNegativeButton(android.R.string.cancel, null)
            .setPositiveButton(android.R.string.ok) { _, _ ->
                val shownKeys = apps.map { it.key }.toSet()
                val checkedShown = apps.filterIndexed { i, _ -> checked[i] }.map { it.key }.toSet()
                lifecycleScope.launch {
                    // Merge (not replace) via the pure HiddenAppsSelection so hidden keys for apps
                    // not shown here (uninstalled / cross-device) are preserved. Computed inside the
                    // transform so it applies to the latest persisted set.
                    hiddenAppsRepository.update { current ->
                        val merged = HiddenAppsSelection.merge(current, shownKeys, checkedShown)
                        if (merged == current) null else merged
                    }
                }
            }
            .show()
    }

    /**
     * Crash-report consent (Settings entry, mirrors Kolibri): the shared [ConsentDialog]
     * re-opened on demand; the choice is applied + persisted via [ConsentController] and the
     * summary + a toast confirm it.
     */
    private fun showCrashReportConsentDialog() {
        lifecycleScope.launch {
            // Dismiss any still-tracked dialog before showing a new one (mirrors Kolibri):
            // ConsentDialog.show suspends before the modal appears, so a fast double-tap could
            // otherwise stack two setCancelable(false) dialogs and leak the untracked first.
            consentDialog?.dismiss()
            consentDialog = ConsentDialog.show(requireActivity()) { granted ->
                consentController.applyConsent(granted)
                toast(getString(if (granted) R.string.toast_crash_reports_enabled else R.string.toast_crash_reports_disabled))
                crashReportPref?.summary = crashReportSummary(granted)
            }
        }
    }

    private suspend fun refreshCrashReportSummary() {
        val granted = when (val action = consentController.resolveStartupAction()) {
            is ConsentController.StartupAction.Reaffirm -> action.granted
            // NeverAsked (ShowDialog) or unreadable (Skip): crash reporting is off.
            else -> false
        }
        crashReportPref?.summary = crashReportSummary(granted)
    }

    private fun crashReportSummary(granted: Boolean): String =
        getString(if (granted) R.string.crash_report_summary_enabled else R.string.crash_report_summary_disabled)

    /**
     * Factory reset (mirrors Kolibri): confirm, then wipe all state. The positive
     * button is destructive, so it needs an explicit confirm; cancel is a no-op.
     */
    private fun showFactoryResetDialog() {
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.factory_reset_dialog_title)
            .setMessage(R.string.factory_reset_dialog_message)
            .setNegativeButton(android.R.string.cancel, null)
            .setPositiveButton(R.string.factory_reset_confirm) { _, _ -> doFactoryReset() }
            .show()
    }

    private fun doFactoryReset() = lifecycleScope.launch {
        val ok = resetManager.reset()
        if (ok) {
            // Reset clears the seed flags, but the first-run seed only fires in
            // MainActivity.onCreate — which won't re-run on the way back to an already
            // created home. So re-seed the defaults here, so "reset" lands on the default
            // state (dock apps + Play Store on the grid, and the Google drawer folder),
            // not an empty screen.
            firstRunSeeder.seedHomeLayout()
            firstRunSeeder.seedDrawerFolders()
        }
        toast(getString(if (ok) R.string.factory_reset_done else R.string.factory_reset_failed))
        // Back to home, which re-renders from the re-seeded default state.
        if (ok) requireActivity().finish()
    }

    private fun setWallpaperFromUri(uri: Uri) = lifecycleScope.launch {
        val ok = wallpaperImageSetter.setFromUri(uri)
        toast(getString(if (ok) R.string.wallpaper_set_toast else R.string.wallpaper_set_failed_toast))
        if (ok) requireActivity().finish() // back to home, which re-renders from the saved state
    }

    /** Whether the user has granted Nyx notification-listener access (dots need it). */
    private fun hasNotificationAccess(): Boolean =
        NotificationManagerCompat.getEnabledListenerPackages(requireContext())
            .contains(requireContext().packageName)

    /** Open the system notification-access screen so the user can grant Nyx access. */
    private fun openNotificationAccessSettings() {
        // startActivity can throw ActivityNotFoundException on OEMs without this screen;
        // the preceding toast already told the user what to do.
        runCatching { startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)) }
    }

    private fun toast(text: String) = showToastSafe(text)

    override fun onDestroyView() {
        // ConsentDialog is setCancelable(false); dismiss it so its window doesn't leak.
        consentDialog?.dismiss()
        consentDialog = null
        crashReportPref = null
        super.onDestroyView()
    }
}
