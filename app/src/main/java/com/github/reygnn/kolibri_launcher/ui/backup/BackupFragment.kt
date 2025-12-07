package com.github.reygnn.kolibri_launcher.ui.backup

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.github.reygnn.kolibri_launcher.R
import com.github.reygnn.kolibri_launcher.core.AppConstants
import com.github.reygnn.kolibri_launcher.databinding.DialogImportOptionsBinding
import com.github.reygnn.kolibri_launcher.databinding.FragmentBackupBinding
import com.github.reygnn.kolibri_launcher.domain.model.BackupPreview
import com.github.reygnn.kolibri_launcher.domain.model.ImportOptions
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

    private val viewModel: BackupViewModel by viewModels()

    // Exception Handler für alle Coroutines in diesem Fragment
    private val exceptionHandler = CoroutineExceptionHandler { _, throwable ->
        Timber.Forest.e(throwable, "Uncaught coroutine exception in BackupFragment")
        view?.let {
            showError(getString(R.string.error_generic))
        }
    }

    private val exportLauncher = registerForActivityResult(
        ActivityResultContracts.CreateDocument(AppConstants.MIME_TYPE_JSON)
    ) { uri ->
        uri?.let {
            try {
                viewModel.exportBackup(it.toString())
            } catch (e: Exception) {
                Timber.Forest.e(e, "Export failed")
                showError(getString(R.string.backup_export_failed))
            }
        }
    }

    private val importLauncher = registerForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        uri?.let {
            try {
                viewModel.resetBackupState()

                viewModel.previewBackup(it.toString())
                showImportOptionsDialog(it.toString())
            } catch (e: Exception) {
                Timber.Forest.e(e, "Import preview failed")
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

    private fun setupClickListeners() {
        binding.buttonExportBackup.setOnClickListener {
            val timestamp = SimpleDateFormat(AppConstants.DATE_FORMAT_BACKUP_FILENAME, Locale.getDefault())
                .format(Date())
            val filename = "${AppConstants.BACKUP_FILE_PREFIX}$timestamp${AppConstants.BACKUP_FILE_EXTENSION}"
            exportLauncher.launch(filename)
        }

        binding.buttonImportBackup.setOnClickListener {
            importLauncher.launch(arrayOf(AppConstants.MIME_TYPE_JSON))
        }
    }

    private fun showImportOptionsDialog(uriString: String) {
        // Fragment-State-Check
        if (!isAdded || isStateSaved || isDetached) {
            Timber.Forest.w("Cannot show import dialog - invalid fragment state")
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
                    Timber.Forest.e("Preview timeout or loading failed")
                    hideLoading()
                    showError(getString(R.string.error_generic))
                    return@launch
                }

                if (!isAdded || _binding == null) return@launch

                hideLoading()

                // 3. DANN Dialog erstellen mit bereits geladenen Daten
                val dialogBinding = DialogImportOptionsBinding.inflate(layoutInflater)

                // Preview-Daten sofort setzen (nicht asynchron!)
                val dateText = if (preview.timestamp > 0L) {
                    SimpleDateFormat(AppConstants.DATE_FORMAT_DISPLAY, Locale.getDefault())
                        .format(Date(preview.timestamp))
                } else {
                    getString(R.string.backup_preview_date_unknown)
                }

                dialogBinding.apply {
                    textBackupInfo.text = getString(
                        R.string.backup_preview_date,
                        dateText
                    )

                    val favoritesVisible = preview.favoriteCount > 0
                    checkboxImportFavorites.text = getString(
                        R.string.import_option_favorites,
                        preview.favoriteCount
                    )
                    checkboxImportFavorites.isVisible = favoritesVisible
                    checkboxImportFavorites.isChecked = favoritesVisible


                    val orderVisible = preview.orderCount > 0
                    checkboxImportOrder.text = getString(
                        R.string.import_option_order
                    )
                    checkboxImportOrder.isVisible = orderVisible
                    checkboxImportOrder.isChecked = orderVisible


                    val hiddenVisible = preview.hiddenCount > 0
                    checkboxImportHiddenApps.text = getString(
                        R.string.import_option_hidden_apps,
                        preview.hiddenCount
                    )
                    checkboxImportHiddenApps.isVisible = hiddenVisible
                    checkboxImportHiddenApps.isChecked = hiddenVisible


                    val namesVisible = preview.customNamesCount > 0
                    checkboxImportCustomNames.text = getString(
                        R.string.import_option_custom_names,
                        preview.customNamesCount
                    )
                    checkboxImportCustomNames.isVisible = namesVisible
                    checkboxImportCustomNames.isChecked = namesVisible


                    val swipeCount = listOf(preview.hasSwipeLeft, preview.hasSwipeRight).count { it }
                    val swipeActionsVisible = swipeCount > 0
                    checkboxImportSwipeActions.text = buildSwipeActionsText(preview)
                    checkboxImportSwipeActions.isVisible = swipeActionsVisible
                    checkboxImportSwipeActions.isChecked = swipeActionsVisible


                    val themeVisible = preview.hasThemeSettings
                    checkboxImportThemeSettings.text = getString(R.string.import_option_theme)
                    checkboxImportThemeSettings.isVisible = themeVisible
                    checkboxImportThemeSettings.isChecked = themeVisible

                    val gestureVisible = preview.hasGestureSettings
                    checkboxImportGestureSettings.text = getString(R.string.import_option_gestures)
                    checkboxImportGestureSettings.isVisible = gestureVisible
                    checkboxImportGestureSettings.isChecked = gestureVisible

                    val timeEventsVisible = preview.hasTimeBasedEvents
                    checkboxImportTimeBasedEvents.text = getString(R.string.import_option_time_events)
                    checkboxImportTimeBasedEvents.isVisible = timeEventsVisible
                    checkboxImportTimeBasedEvents.isChecked = timeEventsVisible

                    val qolVisible = preview.hasQualityOfLife
                    checkboxImportQualityOfLife.text = getString(R.string.import_option_qol)
                    checkboxImportQualityOfLife.isVisible = qolVisible
                    checkboxImportQualityOfLife.isChecked = qolVisible

                    val powerUserVisible = preview.hasPowerUserSettings
                    checkboxImportPowerUserSettings.text = getString(R.string.import_option_power_user)
                    checkboxImportPowerUserSettings.isVisible = powerUserVisible
                    checkboxImportPowerUserSettings.isChecked = powerUserVisible
                }

                // 4. Dialog anzeigen
                MaterialAlertDialogBuilder(requireContext())
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

            } catch (e: Exception) {
                Timber.Forest.e(e, "Error loading backup preview")
                hideLoading()
                showError(getString(R.string.error_generic))
            }
        }
    }

    /**
     * NEU: Baut den Text für die Swipe Actions Checkbox basierend auf Preview-Daten.
     */
    private fun buildSwipeActionsText(preview: BackupPreview): String {
        val swipeCount = listOf(preview.hasSwipeLeft, preview.hasSwipeRight).count { it }
        return getString(R.string.import_option_swipe_actions, swipeCount)
    }

    private fun observeBackupState() {
        viewLifecycleOwner.lifecycleScope.launch(exceptionHandler) {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.backupState.collect { state ->
                    handleBackupState(state)
                }
            }
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

                // Unterscheide zwischen App-Import und Settings-Import
                val message = if (state.importedCount > 0) {
                    // Apps wurden importiert
                    if (state.skippedCount > 0) {
                        getString(
                            R.string.backup_import_success_with_skipped,
                            state.importedCount,
                            state.skippedCount
                        )
                    } else {
                        getString(R.string.backup_import_success_simple, state.importedCount)
                    }
                } else {
                    // Nur Settings importiert (Gestures/TimeEvents/Theme/SwipeActions)
                    getString(R.string.backup_import_success_settings_only)
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
            Timber.Forest.i(message)
        }
    }

    private fun showImportSuccess(message: String, missingApps: Set<String>) {
        _binding?.let {
            val displayMessage = if (missingApps.isNotEmpty()) {
                val appList = missingApps.take(AppConstants.MAX_MISSING_APPS_IN_SNACKBAR).joinToString("\n") { app ->
                    app.split("/")[0]
                }

                val moreText = if (missingApps.size > AppConstants.MAX_MISSING_APPS_IN_SNACKBAR) {
                    "\n... ${getString(R.string.backup_and_more, missingApps.size - AppConstants.MAX_MISSING_APPS_IN_SNACKBAR)}"
                } else {
                    ""
                }
                "$message\n\n${getString(R.string.backup_missing_apps)}:\n$appList$moreText"
            } else {
                message
            }

            Snackbar.make(it.root, displayMessage, Snackbar.LENGTH_LONG).show()
            Timber.Forest.i("Import success: $message, missing: ${missingApps.size}")
        }
    }

    private fun showError(message: String) {
        _binding?.let {
            Snackbar.make(it.root, message, Snackbar.LENGTH_LONG).show()
            Timber.Forest.e("Backup error: $message")
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}