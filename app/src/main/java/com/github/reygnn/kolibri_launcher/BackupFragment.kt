package com.github.reygnn.kolibri_launcher

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
import com.github.reygnn.kolibri_launcher.databinding.FragmentBackupBinding
import com.google.android.material.snackbar.Snackbar
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.launch
import timber.log.Timber
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@AndroidEntryPoint
class BackupFragment : Fragment() {

    private var _binding: FragmentBackupBinding? = null
    private val binding get() = _binding!!

    private val viewModel: BackupViewModel by viewModels()

    private val exportLauncher = registerForActivityResult(
        ActivityResultContracts.CreateDocument("application/json")
    ) { uri ->
        uri?.let { viewModel.exportBackup(it) }
    }

    private val importLauncher = registerForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        uri?.let { viewModel.importBackup(it) }
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

    private fun observeBackupState() {
        viewLifecycleOwner.lifecycleScope.launch {
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
        binding.progressBar.visibility = View.VISIBLE
        binding.buttonExportBackup.isEnabled = false
        binding.buttonImportBackup.isEnabled = false
    }

    private fun hideLoading() {
        binding.progressBar.visibility = View.GONE
        binding.buttonExportBackup.isEnabled = true
        binding.buttonImportBackup.isEnabled = true
    }

    private fun showSuccess(message: String) {
        Snackbar.make(binding.root, message, Snackbar.LENGTH_LONG).show()
        Timber.i(message)
    }

    private fun showImportSuccess(message: String, missingApps: Set<String>) {
        val displayMessage = if (missingApps.isNotEmpty()) {
            val appList = missingApps.joinToString("\n") { it.split("/")[0] }
            "$message\n\n${getString(R.string.backup_missing_apps)}:\n$appList"
        } else {
            message
        }

        Snackbar.make(binding.root, displayMessage, Snackbar.LENGTH_LONG).show()
        Timber.i("Import success: $message, missing: ${missingApps.size}")
    }

    private fun showError(message: String) {
        Snackbar.make(binding.root, message, Snackbar.LENGTH_LONG).show()
        Timber.e("Backup error: $message")
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}