package com.github.reygnn.kolibri_launcher.ui.backup

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.lifecycle.lifecycleScope
import com.github.reygnn.kolibri_launcher.R
import com.github.reygnn.kolibri_launcher.core.AppConstants
import com.github.reygnn.kolibri_launcher.core.TimberWrapper
import com.github.reygnn.kolibri_launcher.databinding.DialogImportOptionsBinding
import com.github.reygnn.kolibri_launcher.databinding.FragmentBackupBinding
import com.github.reygnn.kolibri_launcher.domain.model.BackupPreview
import com.github.reygnn.kolibri_launcher.domain.model.ImportOptions
import com.github.reygnn.kolibri_launcher.ui.flow.collectOnStarted
import androidx.appcompat.app.AlertDialog
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.snackbar.Snackbar
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import timber.log.Timber
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Fragment für Backup und Restore von App-Einstellungen.
 *
 * Crash-Schutz:
 * - CoroutineExceptionHandler für async Operationen
 * - Fragment-State-Checks vor Dialog-Anzeige
 * - Try-catch für I/O-Operationen (File-System-Zugriff)
 * - Null-Checks für View-Binding
 */
@AndroidEntryPoint
class BackupFragment : Fragment() {

    private var _binding: FragmentBackupBinding? = null
    private val binding get() = _binding!!

    // Tracked so onDestroyView can dismiss it — otherwise a rotation with the
    // import-options dialog open leaks its window.
    private var currentDialog: AlertDialog? = null

    private val viewModel: BackupViewModel by viewModels()

    // Exception Handler für alle Coroutines in diesem Fragment
    private val exceptionHandler = CoroutineExceptionHandler { _, throwable ->
        Timber.e(throwable, "Uncaught coroutine exception in BackupFragment")
        view?.let {
            showError(getString(R.string.error_generic))
        }
    }

    private val exportLauncher = registerForActivityResult(
        ActivityResultContracts.CreateDocument(AppConstants.MIME_TYPE_ZIP)
    ) { uri ->
        uri?.let {
            // Outer Catchall kept: registerForActivityResult callback runs
            // on a system-callback boundary; `viewModel.exportBackup` itself
            // is launchSafe so synchronous throws are unlikely, but we own
            // the boundary. §9.15-Sweep widened to Throwable.
            try {
                viewModel.exportBackup(it.toString())
            } catch (e: Throwable) {
                TimberWrapper.silentError(e, "Export failed")
                showError(getString(R.string.backup_export_failed))
            }
        }
    }

    private val importLauncher = registerForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        uri?.let {
            // Outer Catchall kept: same shape as exportLauncher above.
            // showImportOptionsDialog has its own inner catch; this catch
            // covers `viewModel.previewBackup` plus the dialog setup.
            try {
                viewModel.resetBackupState()

                viewModel.previewBackup(it.toString())
                showImportOptionsDialog(it.toString())
            } catch (e: Throwable) {
                TimberWrapper.silentError(e, "Import preview failed")
                showError(getString(R.string.error_generic))
            }
        }
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentBackupBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        setupClickListeners()
        observeBackupState()
    }

    private val backupFilenameBuilder = BackupFilenameBuilder()

    private fun setupClickListeners() {
        binding.buttonExportBackup.setOnClickListener {
            exportLauncher.launch(backupFilenameBuilder.build())
        }

        binding.buttonImportBackup.setOnClickListener {
            importLauncher.launch(arrayOf(AppConstants.MIME_TYPE_JSON, AppConstants.MIME_TYPE_ZIP))
        }
    }

    private fun showImportOptionsDialog(uriString: String) {
        // Fragment-State-Check
        if (!isAdded || isStateSaved || isDetached) {
            Timber.w("Cannot show import dialog - invalid fragment state")
            return
        }

        // Zeige Loading während Preview geladen wird
        showLoading()

        viewLifecycleOwner.lifecycleScope.launch(exceptionHandler) {
            try {
                val preview = withTimeoutOrNull(AppConstants.BACKUP_PREVIEW_TIMEOUT_MS) {
                    viewModel.backupPreview.first { it != null }
                }

                // Wenn preview null ist (Timeout) oder Fragment weg ist -> Abbruch
                if (preview == null) {
                    TimberWrapper.silentError("Preview timeout or loading failed")
                    hideLoading()
                    showError(getString(R.string.error_generic))
                    return@launch
                }

                if (!isAdded || _binding == null) return@launch

                hideLoading()

                // 3. DANN Dialog erstellen mit bereits geladenen Daten
                val dialogBinding = DialogImportOptionsBinding.inflate(layoutInflater)

                // Pure UI-State aus der Preview ableiten (getestet in ImportOptionsUiStateTest)
                val uiState = ImportOptionsUiState.from(preview)

                // Display-Datum bleibt hier - Locale + StringResource nur im Fragment
                val dateText = if (uiState.dateHasTimestamp) {
                    SimpleDateFormat(AppConstants.DATE_FORMAT_DISPLAY, Locale.getDefault())
                        .format(Date(preview.timestamp))
                } else {
                    getString(R.string.backup_preview_date_unknown)
                }

                dialogBinding.apply {
                    textBackupInfo.text = getString(R.string.backup_preview_date, dateText)

                    checkboxImportFavorites.text = getString(R.string.import_option_favorites, preview.favoriteCount)
                    checkboxImportFavorites.isVisible = uiState.favorites.visible
                    checkboxImportFavorites.isChecked = uiState.favorites.checked

                    checkboxImportOrder.text = getString(R.string.import_option_order)
                    checkboxImportOrder.isVisible = uiState.order.visible
                    checkboxImportOrder.isChecked = uiState.order.checked

                    checkboxImportHiddenApps.text = getString(R.string.import_option_hidden_apps, preview.hiddenCount)
                    checkboxImportHiddenApps.isVisible = uiState.hiddenApps.visible
                    checkboxImportHiddenApps.isChecked = uiState.hiddenApps.checked

                    checkboxImportCustomNames.text = getString(R.string.import_option_custom_names, preview.customNamesCount)
                    checkboxImportCustomNames.isVisible = uiState.customNames.visible
                    checkboxImportCustomNames.isChecked = uiState.customNames.checked

                    checkboxImportSwipeActions.text = getString(R.string.import_option_swipe_actions, uiState.swipeActionCount)
                    checkboxImportSwipeActions.isVisible = uiState.swipeActions.visible
                    checkboxImportSwipeActions.isChecked = uiState.swipeActions.checked

                    checkboxImportThemeSettings.text = getString(R.string.import_option_theme)
                    checkboxImportThemeSettings.isVisible = uiState.themeSettings.visible
                    checkboxImportThemeSettings.isChecked = uiState.themeSettings.checked

                    checkboxImportGestureSettings.text = getString(R.string.import_option_gestures)
                    checkboxImportGestureSettings.isVisible = uiState.gestureSettings.visible
                    checkboxImportGestureSettings.isChecked = uiState.gestureSettings.checked

                    checkboxImportTimeBasedEvents.text = getString(R.string.import_option_time_events)
                    checkboxImportTimeBasedEvents.isVisible = uiState.timeBasedEvents.visible
                    checkboxImportTimeBasedEvents.isChecked = uiState.timeBasedEvents.checked

                    checkboxImportQualityOfLife.text = getString(R.string.import_option_qol)
                    checkboxImportQualityOfLife.isVisible = uiState.qualityOfLife.visible
                    checkboxImportQualityOfLife.isChecked = uiState.qualityOfLife.checked

                    checkboxImportPowerUserSettings.text = getString(R.string.import_option_power_user)
                    checkboxImportPowerUserSettings.isVisible = uiState.powerUserSettings.visible
                    checkboxImportPowerUserSettings.isChecked = uiState.powerUserSettings.checked
                }

                // 4. Dialog anzeigen
                currentDialog?.dismiss()
                currentDialog = MaterialAlertDialogBuilder(requireContext())
                    .setTitle(R.string.import_options_title)
                    .setView(dialogBinding.root)
                    .setPositiveButton(R.string.import_button) { _, _ ->
                        val options = ImportOptions(
                            importFavorites = dialogBinding.checkboxImportFavorites.isChecked,
                            importOrder = dialogBinding.checkboxImportOrder.isChecked,
                            importHiddenApps = dialogBinding.checkboxImportHiddenApps.isChecked,
                            importCustomNames = dialogBinding.checkboxImportCustomNames.isChecked,
                            importSwipeActions = dialogBinding.checkboxImportSwipeActions.isChecked,
                            importThemeSettings = dialogBinding.checkboxImportThemeSettings.isChecked,
                            importGestureSettings = dialogBinding.checkboxImportGestureSettings.isChecked,
                            importTimeBasedEvents = dialogBinding.checkboxImportTimeBasedEvents.isChecked,
                            importQualityOfLife = dialogBinding.checkboxImportQualityOfLife.isChecked,
                            importPowerUserSettings = dialogBinding.checkboxImportPowerUserSettings.isChecked
                        )

                        if (options.importNothing) {
                            showError(getString(R.string.import_no_options_selected))
                        } else {
                            viewModel.importBackup(uriString, options)
                        }
                    }
                    .setNegativeButton(android.R.string.cancel, null)
                    .show()

            // Outer Catchall kept: DialogImportOptionsBinding.inflate +
            // MaterialAlertDialogBuilder + show() — Dialog/View-Inflation
            // chain may OutOfMemoryError on low-memory devices, plus
            // BadTokenException / IllegalStateException when the activity
            // is finishing. OOM extends Error/Throwable, NOT Exception —
            // same pattern as §9.8 ZoomableImageView and §9.13
            // BackupRepositoryImpl. The hideLoading + showError fallback
            // gives the user an exit either way.
            } catch (e: Throwable) {
                TimberWrapper.silentError(e, "Error loading backup preview")
                hideLoading()
                showError(getString(R.string.error_generic))
            }
        }
    }

    private fun observeBackupState() {
        collectOnStarted(
            flow = viewModel.backupState,
            errorTag = "backupState",
            coroutineContext = exceptionHandler,
        ) { state ->
            handleBackupState(state)
        }
    }

    private fun handleBackupState(state: BackupState) {
        when (state) {
            is BackupState.Idle -> {
                hideLoading()
            }
            is BackupState.Loading -> {
                showLoading()
            }
            is BackupState.ExportSuccess -> {
                hideLoading()
                showSuccess(getString(R.string.backup_export_success))
                viewModel.resetBackupState()
            }
            is BackupState.ImportSuccess -> {
                hideLoading()

                // Pure Logic entscheidet die Variante (getestet in ImportSuccessMessageTest)
                val message = when (
                    val msg = ImportSuccessMessage.select(state.importedCount, state.skippedCount)
                ) {
                    is ImportSuccessMessage.AppsImportedWithSkipped -> getString(
                        R.string.backup_import_success_with_skipped,
                        msg.importedCount,
                        msg.skippedCount,
                    )
                    is ImportSuccessMessage.AppsImported -> getString(
                        R.string.backup_import_success_simple,
                        msg.importedCount,
                    )
                    ImportSuccessMessage.SettingsOnly -> getString(
                        R.string.backup_import_success_settings_only,
                    )
                }

                showImportSuccess(message, state.missingApps)
                viewModel.resetBackupState()
            }
            is BackupState.LimitExceeded -> {
                hideLoading()
                val message = getString(
                    R.string.backup_limit_exceeded,
                    state.packageCount,
                    state.limit
                )
                showError(message)
                viewModel.resetBackupState()
            }
            is BackupState.UnsupportedVersion -> {
                hideLoading()
                val message = getString(R.string.backup_unsupported_version, state.version)
                showError(message)
                viewModel.resetBackupState()
            }
            is BackupState.InvalidFormat -> {
                hideLoading()
                showError(getString(R.string.backup_invalid_format))
                viewModel.resetBackupState()
            }
            is BackupState.Error -> {
                hideLoading()
                showError(state.message)
                viewModel.resetBackupState()
            }
        }
    }

    private fun showLoading() {
        _binding?.let {
            it.progressBar.visibility = View.VISIBLE
            it.buttonExportBackup.isEnabled = false
            it.buttonImportBackup.isEnabled = false
        }
    }

    private fun hideLoading() {
        _binding?.let {
            it.progressBar.visibility = View.GONE
            it.buttonExportBackup.isEnabled = true
            it.buttonImportBackup.isEnabled = true
        }
    }

    private fun showSuccess(message: String) {
        _binding?.let {
            Snackbar.make(it.root, message, Snackbar.LENGTH_LONG).show()
            Timber.i(message)
        }
    }

    private val missingAppsFormatter = MissingAppsFormatter()

    private fun showImportSuccess(message: String, missingApps: Set<String>) {
        _binding?.let {
            val formatted = missingAppsFormatter.format(missingApps)
            val displayMessage = if (formatted.listText.isEmpty()) {
                message
            } else {
                buildString {
                    append(message)
                    append("\n\n")
                    append(getString(R.string.backup_missing_apps))
                    append(":\n")
                    append(formatted.listText)
                    if (formatted.hasOverflow) {
                        append("\n... ")
                        append(getString(R.string.backup_and_more, formatted.overflowCount))
                    }
                }
            }

            Snackbar.make(it.root, displayMessage, Snackbar.LENGTH_LONG).show()
            Timber.i("Import success: $message, missing: ${missingApps.size}")
        }
    }

    /**
     * UI-only error display — does not log. Callers that have a Throwable
     * must call [TimberWrapper.silentError] themselves before invoking this.
     *
     * Why: the fragment-scoped [exceptionHandler] (Rule 9 safety net) calls
     * [showError] as its UI fallback. A `silentError` here would throw in
     * DEBUG (`crashInDebug`) and escape that handler, surfacing as
     * `RuntimeException: Exception while trying to handle coroutine
     * exception`. State-driven outcomes (LimitExceeded / UnsupportedVersion
     * / InvalidFormat) are not programmer errors either, so a Throwable-less
     * `silentError` would only produce Logcat noise — `AcraTree` filters it
     * out anyway.
     */
    private fun showError(message: String) {
        _binding?.let {
            Snackbar.make(it.root, message, Snackbar.LENGTH_LONG).show()
        }
    }

    override fun onDestroyView() {
        currentDialog?.dismiss()
        currentDialog = null
        super.onDestroyView()
        _binding = null
    }
}