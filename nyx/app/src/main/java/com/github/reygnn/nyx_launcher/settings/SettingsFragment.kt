package com.github.reygnn.nyx_launcher.settings

import com.github.reygnn.launcher.feature.wallpaper.WallpaperOperations
import com.github.reygnn.launcher.feature.wallpaper.WallpaperImagePicker
import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.Settings
import androidx.core.app.NotificationManagerCompat
import android.os.Bundle
import android.view.View
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.fragment.app.viewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.preference.ListPreference
import androidx.preference.Preference
import androidx.preference.PreferenceCategory
import androidx.preference.PreferenceFragmentCompat
import androidx.preference.SwitchPreferenceCompat
import com.github.reygnn.launcher.feature.crashreporting.health.CrashReportingHealth
import com.github.reygnn.launcher.feature.crashreporting.health.CrashReportingHealthMonitor
import com.github.reygnn.launcher.feature.crashreporting.health.CrashReportingHealthState
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import androidx.appcompat.app.AlertDialog
import com.github.reygnn.launcher.common.ui.collectOnStarted
import com.github.reygnn.launcher.common.ui.showToastSafe
import com.github.reygnn.launcher.core.TimberWrapper
import com.github.reygnn.launcher.feature.crashreporting.consent.ConsentController
import com.github.reygnn.launcher.feature.crashreporting.consent.ConsentDialog
import com.github.reygnn.nyx_launcher.BuildConfig
import com.github.reygnn.nyx_launcher.R
import com.github.reygnn.nyx_launcher.data.home.NyxWallpaperImageSetter
import com.github.reygnn.nyx_launcher.home.model.IconStyle
import com.github.reygnn.nyx_launcher.home.repository.PreferencesRepository
import com.github.reygnn.nyx_launcher.home.repository.ResetRepository
import com.github.reygnn.nyx_launcher.home.FirstRunSeeder
import com.github.reygnn.nyx_launcher.home.model.HiddenAppsSelection
import com.github.reygnn.nyx_launcher.home.model.displayName
import com.github.reygnn.nyx_launcher.home.repository.HiddenAppsRepository
import com.github.reygnn.nyx_launcher.home.usecase.GetDrawerAppsUseCase
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import timber.log.Timber
import java.text.DateFormat
import java.util.Date
import javax.inject.Inject
import kotlin.coroutines.EmptyCoroutineContext

/**
 * Settings as a Material 3 [PreferenceFragmentCompat] with categorised rows
 * (mirrors Kolibri's SettingsFragment). Preferences are NOT auto-persisted to
 * SharedPreferences (`isPersistent = false`) — the DataStore-backed
 * [PreferencesRepository] is the single source of truth: the switch drives it on
 * change and its flow drives the switch's checked state.
 */
@AndroidEntryPoint
class SettingsFragment : PreferenceFragmentCompat() {

    private val backupViewModel: NyxBackupViewModel by viewModels()
    @Inject lateinit var resetRepository: ResetRepository
    @Inject lateinit var firstRunSeeder: FirstRunSeeder
    @Inject lateinit var preferences: PreferencesRepository
    @Inject lateinit var wallpaperImageSetter: NyxWallpaperImageSetter
    @Inject lateinit var getDrawerApps: GetDrawerAppsUseCase
    @Inject lateinit var hiddenAppsRepository: HiddenAppsRepository
    @Inject lateinit var consentController: ConsentController
    @Inject lateinit var crashReportingHealthMonitor: CrashReportingHealthMonitor

    private var crashReportPref: Preference? = null
    // ConsentDialog is setCancelable(false); tracked so onDestroyView can dismiss it.
    private var consentDialog: AlertDialog? = null

    private var iconStylePref: ListPreference? = null
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
        // 3b-4: the shared picker (GetContent, "image/*") — Downloads and file managers reachable.
        registerForActivityResult(WallpaperImagePicker.contract()) { uri ->
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

        iconStylePref = findPreference<ListPreference>("icon_style")?.apply {
            isPersistent = false // DataStore is the source of truth, not SharedPreferences
            setOnPreferenceChangeListener { _, newValue ->
                // no suspension point — enum parse of a preference string.
                val style = runCatching { IconStyle.valueOf(newValue as String) }.getOrDefault(IconStyle.COLOR)
                lifecycleScope.launch { preferences.setIconStyle(style) }
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
            WallpaperImagePicker.launch(pickWallpaperImage)
            true
        }
        findPreference<Preference>("clear_wallpaper")?.setOnPreferenceClickListener {
            lifecycleScope.launch {
                // 3b-1: the removal reports whether it took effect; if not, the wallpaper stays.
                val removed = wallpaperImageSetter.clear()
                toast(getString(if (removed) R.string.wallpaper_cleared_toast else R.string.wallpaper_clear_failed_toast))
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
        collectOnStarted(backupViewModel.event, errorTag = "backupEvents", coroutineContext = EmptyCoroutineContext) { onBackupEvent(it) }
        // Reflect the stored crash-report decision in the preference summary.
        viewLifecycleOwner.lifecycleScope.launch { refreshCrashReportSummary() }
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch {
                    preferences.iconStyle().collect { style ->
                        if (iconStylePref?.value != style.name) iconStylePref?.value = style.name
                    }
                }
                launch {
                    preferences.notificationDots().collect { enabled ->
                        if (notificationDotsSwitch?.isChecked != enabled) notificationDotsSwitch?.isChecked = enabled
                        updateNotificationDotsSummary()
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
                        updateCalendarSummary()
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
            hasCalendarPermission() -> {
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

    // Backup through NyxBackupViewModel (2b-3b): it reports every outcome as an event, and a
    // refused file comes back as its message at once, never as the restore dialog.
    private fun doExport(uri: Uri) = backupViewModel.export(uri.toString())

    private fun doImport(uri: Uri) = backupViewModel.previewForImport(uri.toString())

    private fun onBackupEvent(event: BackupEvent) {
        when (event) {
            is BackupEvent.Show -> toast(textOf(event.message))
            is BackupEvent.ChooseImportOptions -> showImportOptions(event)
            BackupEvent.CloseSettings -> activity?.finish() // home re-renders from the restored state
        }
    }

    /** The restore dialog: what the backup contains, each part as a switch (all on). */
    private fun showImportOptions(event: BackupEvent.ChooseImportOptions) {
        val choices = event.ui.choices
        val preview = event.preview
        val labels = choices.map { choice ->
            when (choice) {
                ImportChoice.LAYOUT -> getString(R.string.backup_option_layout, preview.homeItemCount ?: 0, preview.drawerFolderCount)
                ImportChoice.HIDDEN_APPS -> getString(R.string.backup_option_hidden_apps, preview.hiddenAppCount ?: 0)
                ImportChoice.SETTINGS -> getString(R.string.backup_option_settings)
                ImportChoice.WALLPAPER -> getString(R.string.backup_option_wallpaper, preview.wallpaperLayerCount)
            }
        }.toTypedArray()
        val checked = BooleanArray(choices.size) { true }
        val date = if (event.ui.dateHasTimestamp) {
            DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(Date(preview.timestamp))
        } else {
            getString(R.string.backup_preview_date_unknown)
        }
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(getString(R.string.backup_import_dialog_title, date))
            .setMultiChoiceItems(labels, checked) { _, which, isChecked -> checked[which] = isChecked }
            .setPositiveButton(R.string.backup_import_action) { _, _ ->
                val selected = choices.filterIndexedTo(HashSet()) { index, _ -> checked[index] }
                backupViewModel.import(event.uriString, event.ui.toImportOptions(selected))
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun textOf(message: BackupMessage): String = when (message) {
        BackupMessage.ExportDone -> getString(R.string.backup_export_done)
        BackupMessage.ExportFailed -> getString(R.string.backup_export_failed)
        is BackupMessage.ImportDone -> if (message.droppedWallpaperLayers == 0) {
            getString(R.string.backup_import_done)
        } else {
            resources.getQuantityString(R.plurals.backup_import_done_dropped_layers, message.droppedWallpaperLayers, message.droppedWallpaperLayers)
        }
        is BackupMessage.ForeignBackup -> getString(R.string.backup_foreign_app, message.appId)
        BackupMessage.OutdatedBackup -> getString(R.string.backup_outdated_format)
        is BackupMessage.UnsupportedVersion -> getString(R.string.backup_unsupported_version, message.version)
        BackupMessage.InvalidBackup -> getString(R.string.backup_import_invalid)
        BackupMessage.ImportFailed -> getString(R.string.backup_import_failed)
        BackupMessage.NothingSelected -> getString(R.string.backup_import_nothing_selected)
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
                // Summary from the just-made choice + the in-memory bootstrap-health flag
                // (no store re-read, which could race the still-running persist write —
                // AUDIT-10 #1, same as Kolibri): a fresh grant on a broken bootstrap shows
                // BROKEN, not a false "enabled".
                crashReportPref?.summary = crashReportSummary(
                    when {
                        !granted -> CrashReportingHealthState.NOT_APPLICABLE
                        CrashReportingHealth.isBootstrapHealthy -> CrashReportingHealthState.HEALTHY
                        else -> CrashReportingHealthState.BROKEN
                    },
                )
            }
        }
    }

    /**
     * An HONEST health indicator, not a consent-only one (which read "enabled" even when the
     * bootstrap gate died — Kolibri's 2026-08 bug, shared fix since SPEC_NYX_REWRITE 1c-3):
     * evaluate() folds consent + the bootstrap-health flag into one verdict. I/O problems come
     * back as a value; an UNKNOWN verdict leaves the summary as it is (safe stale).
     */
    private suspend fun refreshCrashReportSummary() {
        val state = crashReportingHealthMonitor.evaluate()
        if (state != CrashReportingHealthState.UNKNOWN) crashReportPref?.summary = crashReportSummary(state)
    }

    private fun crashReportSummary(state: CrashReportingHealthState): String = getString(
        when (state) {
            CrashReportingHealthState.HEALTHY -> R.string.crash_report_summary_enabled
            CrashReportingHealthState.BROKEN -> R.string.crash_report_summary_broken
            CrashReportingHealthState.NOT_APPLICABLE,
            CrashReportingHealthState.UNKNOWN -> R.string.crash_report_summary_disabled
        },
    )

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
        // The first-run seed only fires in MainActivity.onCreate, which won't re-run on the way
        // back to an already created home, so the defaults are seeded here — after every reset,
        // also an incomplete one (R2, see performFactoryReset).
        val message = performFactoryReset(
            reset = { resetRepository.factoryReset() },
            seedDefaults = {
                firstRunSeeder.seedHomeLayout()
                firstRunSeeder.seedDrawerFolders()
            },
        )
        toast(getString(message))
        // Back to home in both cases; it re-renders from the reset and re-seeded state.
        requireActivity().finish()
    }

    private fun setWallpaperFromUri(uri: Uri) = lifecycleScope.launch {
        when (wallpaperImageSetter.setFromUri(uri)) {
            WallpaperOperations.ImageResult.Applied -> {
                toast(getString(R.string.wallpaper_set_toast))
                requireActivity().finish() // back to home, which re-renders from the saved state
            }
            WallpaperOperations.ImageResult.CopyFailed -> toast(getString(R.string.wallpaper_set_failed_toast))
            // A removal won the race (audit A21): no error, and the user stays in Settings.
            WallpaperOperations.ImageResult.Discarded -> Unit
        }
    }

    override fun onResume() {
        super.onResume()
        // Re-check access on return from the system settings screen (or a later revoke)
        // so the toggle summary reflects reality instead of silently misleading.
        updateNotificationDotsSummary()
        updateCalendarSummary()
    }

    /** Reflect the notification-access state in the dots toggle summary when it's on. */
    private fun updateNotificationDotsSummary() {
        val sw = notificationDotsSwitch ?: return
        sw.summary = if (sw.isChecked && !hasNotificationAccess()) {
            getString(R.string.notification_dots_no_access_summary)
        } else {
            getString(R.string.notification_dots_summary)
        }
    }

    /**
     * Reflect a READ_CALENDAR revoked outside Nyx in the calendar toggle summary when it's on
     * (§Audit-3 A3-07). Mirrors the dots toggle: the stored preference is kept, so re-granting in
     * system settings brings events back without re-toggling; rendering is fail-closed meanwhile.
     */
    private fun updateCalendarSummary() {
        val sw = calendarSwitch ?: return
        sw.summary = if (sw.isChecked && !hasCalendarPermission()) {
            getString(R.string.show_calendar_event_no_access_summary)
        } else {
            getString(R.string.show_calendar_event_summary)
        }
    }

    private fun hasCalendarPermission(): Boolean =
        ContextCompat.checkSelfPermission(requireContext(), Manifest.permission.READ_CALENDAR) ==
            PackageManager.PERMISSION_GRANTED

    /** Whether the user has granted Nyx notification-listener access (dots need it). */
    private fun hasNotificationAccess(): Boolean =
        NotificationManagerCompat.getEnabledListenerPackages(requireContext())
            .contains(requireContext().packageName)

    /** Open the system notification-access screen so the user can grant Nyx access. */
    private fun openNotificationAccessSettings() {
        // startActivity can throw ActivityNotFoundException on OEMs without this screen;
        // the preceding toast already told the user what to do.
        // no suspension point — startActivity is synchronous; a missing settings screen must not crash.
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
