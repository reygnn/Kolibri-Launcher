package com.github.reygnn.kolibri_launcher.ui.usageexport

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.github.reygnn.kolibri_launcher.R
import com.github.reygnn.kolibri_launcher.core.TimberWrapper
import com.github.reygnn.kolibri_launcher.databinding.FragmentUsageExportBinding
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

@AndroidEntryPoint
class UsageExportFragment : Fragment() {

    private var _binding: FragmentUsageExportBinding? = null
    private val binding get() = _binding!!

    private val viewModel: UsageExportViewModel by viewModels()

    private val exportLauncher = registerForActivityResult(
        ActivityResultContracts.CreateDocument("application/json")
    ) { uri ->
        uri?.let { viewModel.exportToFile(it.toString()) }
    }

    private val importLauncher = registerForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        uri?.let { showImportModeDialog(it.toString()) }
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentUsageExportBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        try {
            setupButtons()
            observeViewModel()
        } catch (e: Throwable) {
            TimberWrapper.silentError(e, "Error in UsageExportFragment.onViewCreated")
        }
    }

    private fun setupButtons() {
        binding.buttonExport.setOnClickListener {
            try {
                val timestamp = LocalDateTime.now()
                    .format(DateTimeFormatter.ofPattern("yyyy-MM-dd_HHmmss"))
                val filename = "kolibri_usage_$timestamp.json"
                exportLauncher.launch(filename)
            } catch (e: Throwable) {
                TimberWrapper.silentError(e, "Error launching export picker")
                Toast.makeText(requireContext(), R.string.usage_export_error, Toast.LENGTH_SHORT).show()
            }
        }

        binding.buttonImport.setOnClickListener {
            try {
                importLauncher.launch(arrayOf("application/json"))
            } catch (e: Throwable) {
                TimberWrapper.silentError(e, "Error launching import picker")
                Toast.makeText(requireContext(), R.string.usage_import_error, Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun showImportModeDialog(uriString: String) {
        try {
            if (!isAdded) return

            MaterialAlertDialogBuilder(requireContext())
                .setTitle(R.string.usage_import_mode_title)
                .setMessage(R.string.usage_import_mode_message)
                .setPositiveButton(R.string.usage_import_merge) { _, _ ->
                    viewModel.importFromFile(uriString, mergeWithExisting = true)
                }
                .setNegativeButton(R.string.usage_import_replace) { _, _ ->
                    viewModel.importFromFile(uriString, mergeWithExisting = false)
                }
                .setNeutralButton(R.string.cancel, null)
                .show()
        } catch (e: Throwable) {
            TimberWrapper.silentError(e, "Error showing import mode dialog")
        }
    }

    private fun observeViewModel() {
        viewLifecycleOwner.lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch {
                    try {
                        viewModel.uiEvent.collect { event ->
                            if (!isAdded || isDetached) return@collect
                            handleUiEvent(event)
                        }
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Throwable) {
                        TimberWrapper.silentError(e, "Error collecting UI events")
                    }
                }

                launch {
                    try {
                        viewModel.isLoading.collect { isLoading ->
                            if (!isAdded || isDetached) return@collect
                            binding.progressBar.visibility = if (isLoading) View.VISIBLE else View.GONE
                            binding.buttonExport.isEnabled = !isLoading
                            binding.buttonImport.isEnabled = !isLoading
                        }
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Throwable) {
                        TimberWrapper.silentError(e, "Error collecting loading state")
                    }
                }
            }
        }
    }

    private fun handleUiEvent(event: UsageExportUiEvent) {
        try {
            when (event) {
                is UsageExportUiEvent.ExportSuccess -> {
                    Toast.makeText(
                        requireContext(),
                        R.string.usage_export_success,
                        Toast.LENGTH_SHORT
                    ).show()
                }

                is UsageExportUiEvent.ExportError -> {
                    Toast.makeText(
                        requireContext(),
                        getString(R.string.usage_export_failed, event.message),
                        Toast.LENGTH_LONG
                    ).show()
                }

                is UsageExportUiEvent.ImportSuccess -> {
                    Toast.makeText(
                        requireContext(),
                        getString(
                            R.string.usage_import_success,
                            event.packagesImported,
                            event.timestampsImported
                        ),
                        Toast.LENGTH_SHORT
                    ).show()
                }

                is UsageExportUiEvent.ImportError -> {
                    Toast.makeText(
                        requireContext(),
                        getString(R.string.usage_import_failed, event.message),
                        Toast.LENGTH_LONG
                    ).show()
                }

                is UsageExportUiEvent.InvalidFormat -> {
                    Toast.makeText(
                        requireContext(),
                        R.string.usage_import_invalid_format,
                        Toast.LENGTH_LONG
                    ).show()
                }

                is UsageExportUiEvent.UnsupportedVersion -> {
                    Toast.makeText(
                        requireContext(),
                        getString(R.string.usage_import_unsupported_version, event.version),
                        Toast.LENGTH_LONG
                    ).show()
                }
            }
        } catch (e: Throwable) {
            TimberWrapper.silentError(e, "Error handling UI event")
        }
    }

    override fun onDestroyView() {
        _binding = null
        super.onDestroyView()
    }
}