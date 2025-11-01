package com.github.reygnn.kolibri_launcher

import android.net.Uri
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.activity.result.contract.ActivityResultContracts
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.github.reygnn.kolibri_launcher.databinding.DialogImportOptionsBinding
import com.github.reygnn.kolibri_launcher.databinding.FragmentBackupBinding
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.snackbar.Snackbar
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.launch
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
        Timber.e(throwable, "Uncaught coroutine exception in BackupFragment")
        view?.let {
            showError(getString(R.string.error_generic))
        }
    }

    private val exportLauncher = registerForActivityResult(
        ActivityResultContracts.CreateDocument("application/json")
    ) { uri ->
        uri?.let {
            try {
                viewModel.exportBackup(it)
            } catch (e: Exception) {
                Timber.e(e, "Export failed")
                showError(getString(R.string.backup_export_failed))
            }
        }
    }

    private val importLauncher = registerForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        uri?.let {
            try {
                viewModel.previewBackup(it)
                showImportOptionsDialog(it)
            } catch (e: Exception) {
                Timber.e(e, "Import preview failed")
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
            val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault())
                .format(Date())
            val filename = "kolibri_backup_$timestamp.json"
            exportLauncher.launch(filename)
        }

        binding.buttonImportBackup.setOnClickListener {
            importLauncher.launch(arrayOf("application/json"))
        }
    }

    private fun showImportOptionsDialog(uri: Uri) {
        // Fragment-State prüfen bevor Dialog angezeigt wird
        if (!isAdded || isStateSaved || isDetached) {
            Timber.w("Cannot show import dialog - invalid fragment state")
            return
        }

        val dialogBinding = DialogImportOptionsBinding.inflate(layoutInflater)

        // Preview-Daten beobachten und Dialog aktualisieren
        viewLifecycleOwner.lifecycleScope.launch(exceptionHandler) {
            viewModel.backupPreview.collect { preview ->
                // Null-Check für Binding und Fragment-State
                if (_binding == null || !isAdded) return@collect

                preview?.let {
                    val date = SimpleDateFormat("dd.MM.yyyy HH:mm", Locale.getDefault())
                        .format(Date(it.timestamp))

                    // Dialog-Inhalte aktualisieren
                    dialogBinding.textBackupInfo.text = getString(
                        R.string.backup_preview_date,
                        date
                    )

                    dialogBinding.checkboxImportFavorites.text = getString(
                        R.string.import_option_favorites,
                        it.favoriteCount
                    )

                    dialogBinding.checkboxImportOrder.text = getString(
                        R.string.import_option_order
                    )

                    dialogBinding.checkboxImportHiddenApps.text = getString(
                        R.string.import_option_hidden_apps,
                        it.hiddenCount
                    )

                    dialogBinding.checkboxImportCustomNames.text = getString(
                        R.string.import_option_custom_names,
                        it.customNamesCount
                    )
                }
            }
        }

        MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.import_options_title)
            .setView(dialogBinding.root)
            .setPositiveButton(R.string.import_button) { _, _ ->
                val options = ImportOptions(
                    importFavorites = dialogBinding.checkboxImportFavorites.isChecked,
                    importOrder = dialogBinding.checkboxImportOrder.isChecked,
                    importHiddenApps = dialogBinding.checkboxImportHiddenApps.isChecked,
                    importCustomNames = dialogBinding.checkboxImportCustomNames.isChecked
                )

                if (options.importNothing) {
                    showError(getString(R.string.import_no_options_selected))
                } else {
                    viewModel.importBackup(uri, options)
                }
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun observeBackupState() {
        viewLifecycleOwner.lifecycleScope.launch(exceptionHandler) {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.backupState.collect { state ->
                    // Null-Check für Binding
                    if (_binding == null || !isAdded) return@collect

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
                val message = if (state.skippedCount > 0) {
                    getString(
                        R.string.backup_import_success_with_skipped,
                        state.importedCount,
                        state.skippedCount
                    )
                } else {
                    getString(R.string.backup_import_success_simple, state.importedCount)
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

    private fun showImportSuccess(message: String, missingApps: Set<String>) {
        _binding?.let {
            val displayMessage = if (missingApps.isNotEmpty()) {
                val appList = missingApps.take(5).joinToString("\n") { app ->
                    app.split("/")[0]
                }
                val moreText = if (missingApps.size > 5) {
                    "\n... ${getString(R.string.backup_and_more, missingApps.size - 5)}"
                } else {
                    ""
                }
                "$message\n\n${getString(R.string.backup_missing_apps)}:\n$appList$moreText"
            } else {
                message
            }

            Snackbar.make(it.root, displayMessage, Snackbar.LENGTH_LONG).show()
            Timber.i("Import success: $message, missing: ${missingApps.size}")
        }
    }

    private fun showError(message: String) {
        _binding?.let {
            Snackbar.make(it.root, message, Snackbar.LENGTH_LONG).show()
            Timber.e("Backup error: $message")
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}