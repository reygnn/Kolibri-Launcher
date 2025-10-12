package com.github.reygnn.kolibri_launcher

import android.os.Bundle
import android.view.View
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.Toast
import androidx.activity.viewModels
import androidx.appcompat.app.AlertDialog
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding
import androidx.core.widget.doOnTextChanged
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.LinearLayoutManager
import com.github.reygnn.kolibri_launcher.databinding.ActivityAppNamesBinding
import com.google.android.material.chip.Chip
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.launch
import timber.log.Timber

/**
 * CRASH-SAFE VERSION
 *
 * Crashsicherheit durch:
 * - Nullable binding mit proper cleanup
 * - Lifecycle-aware coroutines
 * - Safe RecyclerView state restoration
 * - Defensive null checks
 * - Try-catch für alle kritischen Operationen
 * - Memory leak prevention
 * - Safe dialog handling
 */
@AndroidEntryPoint
class AppNamesActivity : BaseActivity<AppNamesViewModel>() {

    // CRASH-SAFE: Nullable binding
    private var _binding: ActivityAppNamesBinding? = null
    private val binding get() = _binding ?: throw IllegalStateException("Binding accessed after onDestroy")

    override val viewModel: AppNamesViewModel by viewModels()
    private var adapter: AppNamesAdapter? = null
    private var currentDialog: AlertDialog? = null

    // Search Debouncing
    private val searchQueryFlow = MutableStateFlow("")

    private companion object {
        private const val SEARCH_DEBOUNCE_MS = 300L
        private const val STATE_SEARCH_QUERY = "search_query"
        private const val MAX_APP_NAME_LENGTH = 50
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        try {
            WindowCompat.setDecorFitsSystemWindows(window, false)
            _binding = ActivityAppNamesBinding.inflate(layoutInflater)
            setContentView(binding.root)

            handleWindowInsets()
            setupRecyclerView()
            setupSearchListener()
            setupClickListeners()
            observeViewModel()

            // Search Query wiederherstellen
            savedInstanceState?.getString(STATE_SEARCH_QUERY)?.let { query ->
                binding.searchEditText.setText(query)
                binding.searchEditText.setSelection(query.length)
            }
        } catch (e: Exception) {
            TimberWrapper.silentError(e, "Fatal error in onCreate")
            finish() // Graceful exit
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        try {
            _binding?.let {
                outState.putString(STATE_SEARCH_QUERY, it.searchEditText.text.toString())
            }
        } catch (e: Exception) {
            TimberWrapper.silentError(e, "Error saving instance state")
        }
    }

    override fun onDestroy() {
        try {
            // CRASH-SAFE: Dialog schließen bevor Activity destroyed wird
            currentDialog?.dismiss()
            currentDialog = null

            // Cleanup in richtiger Reihenfolge
            adapter = null
            _binding = null
        } catch (e: Exception) {
            TimberWrapper.silentError(e, "Error in onDestroy")
        } finally {
            super.onDestroy()
        }
    }

    private fun handleWindowInsets() {
        try {
            ViewCompat.setOnApplyWindowInsetsListener(binding.mainLayout) { view, windowInsets ->
                val insets = windowInsets.getInsets(WindowInsetsCompat.Type.systemBars())
                view.updatePadding(
                    left = insets.left,
                    top = insets.top,
                    right = insets.right,
                    bottom = insets.bottom
                )
                WindowInsetsCompat.CONSUMED
            }
        } catch (e: Exception) {
            TimberWrapper.silentError(e, "Error handling window insets")
        }
    }

    private fun setupRecyclerView() {
        try {
            adapter = AppNamesAdapter { appInfo ->
                try {
                    showRenameDialog(appInfo)
                } catch (e: Exception) {
                    TimberWrapper.silentError(e, "Error showing rename dialog for ${appInfo.packageName}")
                    showError(getString(R.string.error_generic))
                }
            }

            binding.allAppsRecyclerView.apply {
                adapter = this@AppNamesActivity.adapter
                layoutManager = LinearLayoutManager(this@AppNamesActivity)
                setHasFixedSize(true)
                // CRASH-SAFE: Verhindere IllegalStateException bei state restoration
                itemAnimator = null
            }
        } catch (e: Exception) {
            TimberWrapper.silentError(e, "Error setting up RecyclerView")
        }
    }

    @OptIn(FlowPreview::class)
    private fun setupSearchListener() {
        binding.searchEditText.doOnTextChanged { text, _, _, _ ->
            searchQueryFlow.value = text?.toString() ?: ""
        }

        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                searchQueryFlow
                    .debounce(SEARCH_DEBOUNCE_MS)
                    .collect { query ->
                        try {
                            viewModel.onSearchQueryChanged(query)
                        } catch (e: CancellationException) {
                            throw e
                        } catch (e: Exception) {
                            TimberWrapper.silentError(e, "Error in search query changed")
                        }
                    }
            }
        }
    }

    private fun setupClickListeners() {
        try {
            binding.doneButton.setOnClickListener {
                finish()
            }
        } catch (e: Exception) {
            TimberWrapper.silentError(e, "Error setting up click listeners")
        }
    }

    private fun observeViewModel() {
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.uiState.collect { state ->
                    try {
                        updateUi(state)
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        TimberWrapper.silentError(e, "Error updating UI")
                    }
                }
            }
        }
    }

    private fun updateUi(state: AppNamesUiState) {
        // CRASH-SAFE: Check ob binding noch gültig ist
        if (_binding == null) {
            Timber.w("Attempted to update UI after binding was destroyed")
            return
        }

        try {
            adapter?.submitList(state.displayedApps) ?: run {
                Timber.w("Adapter is null, cannot submit list")
            }

            updateCustomNameChips(state.appsWithCustomNames)
        } catch (e: IllegalStateException) {
            TimberWrapper.silentError(e, "View not attached, skipping UI update")
        } catch (e: Exception) {
            TimberWrapper.silentError(e, "Error updating UI")
        }
    }

    private fun updateCustomNameChips(appsWithCustomNames: List<AppInfo>) {
        if (_binding == null) return

        try {
            binding.chipsScrollView.visibility = if (appsWithCustomNames.isEmpty()) View.GONE else View.VISIBLE
            binding.appNameChipGroup.removeAllViews()

            for (app in appsWithCustomNames) {
                try {
                    val chip = Chip(this).apply {
                        text = app.displayName
                        isCloseIconVisible = true
                        setOnCloseIconClickListener {
                            try {
                                viewModel.removeCustomName(app.packageName)
                            } catch (e: Exception) {
                                TimberWrapper.silentError(e, "Error removing custom name for ${app.packageName}")
                            }
                        }
                    }
                    binding.appNameChipGroup.addView(chip)
                } catch (e: Exception) {
                    TimberWrapper.silentError(e, "Error creating chip for ${app.displayName}")
                }
            }
        } catch (e: Exception) {
            TimberWrapper.silentError(e, "Error updating custom name chips")
        }
    }

    private fun showRenameDialog(app: AppInfo) {
        // CRASH-SAFE: Schließe vorherigen Dialog falls noch offen
        currentDialog?.dismiss()

        try {
            val editText = EditText(this).apply {
                setText(app.displayName)
                setSelection(app.displayName.length)
                hint = getString(R.string.rename_hint)
            }

            currentDialog = AlertDialog.Builder(this)
                .setTitle(getString(R.string.rename_dialog_title, app.originalName))
                .setView(editText)
                .setPositiveButton(R.string.save) { _, _ ->
                    try {
                        val newName = editText.text.toString().trim()
                        handleRename(app, newName)
                    } catch (e: Exception) {
                        TimberWrapper.silentError(e, "Error handling rename")
                        showError(getString(R.string.error_generic))
                    }
                }
                .setNegativeButton(R.string.cancel, null)
                .setOnDismissListener {
                    // CRASH-SAFE: Referenz löschen wenn Dialog dismissed wird
                    if (currentDialog?.isShowing == false) {
                        currentDialog = null
                    }
                }
                .create()

            currentDialog?.show()

            // Fokus und Tastatur
            try {
                editText.requestFocus()
                editText.postDelayed({
                    try {
                        val imm = getSystemService(INPUT_METHOD_SERVICE) as? InputMethodManager
                        imm?.showSoftInput(editText, InputMethodManager.SHOW_IMPLICIT)
                    } catch (e: Exception) {
                        TimberWrapper.silentError(e, "Error showing keyboard")
                    }
                }, 100)
            } catch (e: Exception) {
                TimberWrapper.silentError(e, "Error setting focus")
            }
        } catch (e: Exception) {
            TimberWrapper.silentError(e, "Error creating rename dialog")
            showError(getString(R.string.error_generic))
        }
    }

    private fun handleRename(app: AppInfo, newName: String) {
        try {
            when {
                newName.isEmpty() -> {
                    viewModel.removeCustomName(app.packageName)
                }
                newName.length > MAX_APP_NAME_LENGTH -> {
                    showError(getString(R.string.error_name_too_long, MAX_APP_NAME_LENGTH))
                }
                newName == app.originalName -> {
                    viewModel.removeCustomName(app.packageName)
                }
                else -> {
                    viewModel.setCustomName(app.packageName, newName)
                }
            }
        } catch (e: Exception) {
            TimberWrapper.silentError(e, "Error in handleRename")
            showError(getString(R.string.error_generic))
        }
    }

    private fun showError(message: String) {
        try {
            Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
        } catch (e: Exception) {
            TimberWrapper.silentError(e, "Error showing toast")
        }
    }
}