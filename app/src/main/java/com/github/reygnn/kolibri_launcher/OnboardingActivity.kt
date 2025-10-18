package com.github.reygnn.kolibri_launcher

import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.Toast
import androidx.activity.viewModels
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding
import androidx.core.widget.doOnTextChanged
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.LinearLayoutManager
import com.github.reygnn.kolibri_launcher.databinding.ActivityOnboardingBinding
import com.google.android.material.chip.Chip
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.launch
import timber.log.Timber

/**
 * ARCHITECTURE NOTE: Shared UI Components with HiddenAppsActivity
 *
 * This activity handles the initial onboarding flow where users select their favorite apps.
 * Several of its UI components are reused by HiddenAppsActivity to provide a consistent
 * user experience across different app selection screens.
 *
 * SHARED COMPONENTS (UI Layer):
 * - XML Layout: `activity_onboarding.xml` is reused by HiddenAppsActivity
 * - RecyclerView Adapter: `OnboardingAppListAdapter` is used by both activities
 * - UI State Data Class: `OnboardingUiState` is used by both to describe screen state
 *
 * ACTIVITY-SPECIFIC COMPONENTS (Business Logic Layer):
 * - ViewModel: Uses `OnboardingViewModel` for favorite app selection logic
 * - Events: Uses `OnboardingEvent` sealed class (includes NavigateToMain, ShowError,
 *   ShowLimitReachedToast)
 * - Launch Modes: Supports INITIAL_SETUP (first run) and EDIT_FAVORITES (from settings)
 *
 * DESIGN RATIONALE:
 * By sharing UI components, we ensure visual consistency between the onboarding experience
 * and hidden app management, while keeping the business logic separate. This follows the
 * Single Responsibility Principle - each ViewModel handles only its specific use case.
 **/

@AndroidEntryPoint
class OnboardingActivity : BaseActivity<OnboardingEvent, OnboardingViewModel>() {

    override val viewModel: OnboardingViewModel by viewModels()

    private var launchMode: LaunchMode = LaunchMode.INITIAL_SETUP

    companion object {
        const val EXTRA_LAUNCH_MODE = "EXTRA_LAUNCH_MODE"
        private const val SEARCH_DEBOUNCE_MS = 300L
        private const val STATE_SEARCH_QUERY = "search_query"
        private const val STATE_LAUNCH_MODE = "launch_mode"
    }

    // CRASH-SAFE: Nullable binding
    private var _binding: ActivityOnboardingBinding? = null
    private val binding get() = _binding ?: throw IllegalStateException("Binding accessed after onDestroy")

    private var allAppsAdapter: OnboardingAppListAdapter? = null

    // Search Debouncing
    private val searchQueryFlow = MutableStateFlow("")

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        try {
            // Launch Mode bestimmen
            launchMode = if (savedInstanceState != null) {
                savedInstanceState.getSerializable(STATE_LAUNCH_MODE, LaunchMode::class.java) ?: LaunchMode.INITIAL_SETUP
            } else {
                val modeName = intent.getStringExtra(EXTRA_LAUNCH_MODE)
                modeName?.let { LaunchMode.valueOf(it) } ?: LaunchMode.INITIAL_SETUP
            }

            viewModel.setLaunchMode(launchMode)
            viewModel.loadInitialData()

            WindowCompat.setDecorFitsSystemWindows(window, false)
            _binding = ActivityOnboardingBinding.inflate(layoutInflater)
            setContentView(binding.root)

            handleWindowInsets()
            setupRecyclerViews()
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
            outState.putSerializable(STATE_LAUNCH_MODE, launchMode)
        } catch (e: Exception) {
            TimberWrapper.silentError(e, "Error saving instance state")
        }
    }

    override fun onDestroy() {
        try {
            // CRASH-SAFE: Cleanup in richtiger Reihenfolge
            allAppsAdapter = null
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

    private fun setupRecyclerViews() {
        try {
            allAppsAdapter = OnboardingAppListAdapter { appInfo ->
                try {
                    viewModel.onAppToggled(appInfo)
                } catch (e: Exception) {
                    TimberWrapper.silentError(e, "Error toggling app: ${appInfo.packageName}")
                }
            }

            binding.allAppsRecyclerView.apply {
                adapter = allAppsAdapter
                layoutManager = LinearLayoutManager(this@OnboardingActivity)
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
                try {
                    viewModel.onDoneClicked()
                } catch (e: Exception) {
                    TimberWrapper.silentError(e, "Error in done button click")
                    if (launchMode == LaunchMode.INITIAL_SETUP) {
                        goToMainActivity()
                    } else {
                        finish()
                    }
                }
            }
        } catch (e: Exception) {
            TimberWrapper.silentError(e, "Error setting up click listeners")
        }
    }

    private fun observeViewModel() {
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch {
                    try {
                        viewModel.uiState.collect { state ->
                            updateUi(state)
                        }
                    } catch (e: CancellationException) {
                        throw e  // Re-throw
                    } catch (e: Exception) {
                        TimberWrapper.silentError(e, "Error observing UI")
                    }
                }
            }
        }
    }

    private fun updateUi(state: OnboardingUiState) {
        // CRASH-SAFE: Check ob binding noch gültig ist
        if (_binding == null) {
            Timber.w("Attempted to update UI after binding was destroyed")
            return
        }

        try {
            binding.titleText.setText(state.titleResId)
            binding.subtitleText.setText(state.subtitleResId)

            allAppsAdapter?.submitList(state.selectableApps) ?: run {
                Timber.w("Adapter is null, cannot submit list")
            }

            updateSelectionChips(state.selectedApps)
        } catch (e: IllegalStateException) {
            TimberWrapper.silentError(e, "View not attached, skipping UI update")
        } catch (e: Exception) {
            TimberWrapper.silentError(e, "Error updating UI")
        }
    }

    override fun handleSpecificEvent(event: OnboardingEvent) {
        try {
            when (event) {
                is OnboardingEvent.NavigateToMain -> {
                    if (launchMode == LaunchMode.INITIAL_SETUP) {
                        goToMainActivity()
                    } else {
                        finish()
                    }
                }
                is OnboardingEvent.ShowError -> {
                    try {
                        Toast.makeText(
                            this@OnboardingActivity,
                            event.message,
                            Toast.LENGTH_LONG
                        ).show()
                    } catch (e: Exception) {
                        TimberWrapper.silentError(e, "Error showing error toast")
                    }
                }
                is OnboardingEvent.ShowLimitReachedToast -> {
                    try {
                        val message = getString(R.string.favorites_limit_reached, event.limit)
                        Toast.makeText(
                            this@OnboardingActivity,
                            message,
                            Toast.LENGTH_SHORT
                        ).show()
                    } catch (e: Exception) {
                        TimberWrapper.silentError(e, "Error showing limit toast")
                    }
                }
            }
        } catch (e: Exception) {
            TimberWrapper.silentError(e, "Error handling event")
        }
    }

    private fun updateSelectionChips(selectedApps: List<AppInfo>) {
        if (_binding == null) return

        try {
            binding.chipsScrollView.visibility = if (selectedApps.isEmpty()) View.GONE else View.VISIBLE
            binding.selectionChipGroup.removeAllViews()

            for (app in selectedApps) {
                try {
                    val chip = Chip(this).apply {
                        text = app.displayName
                        isCloseIconVisible = true
                        setOnCloseIconClickListener {
                            try {
                                viewModel.onAppToggled(app)
                            } catch (e: Exception) {
                                TimberWrapper.silentError(e, "Error toggling app from chip")
                            }
                        }
                    }
                    binding.selectionChipGroup.addView(chip)
                } catch (e: Exception) {
                    TimberWrapper.silentError(e, "Error creating chip for ${app.displayName}")
                }
            }
        } catch (e: Exception) {
            TimberWrapper.silentError(e, "Error updating selection chips")
        }
    }

    private fun goToMainActivity() {
        try {
            val intent = Intent(this, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
            }
            startActivity(intent)
            finish()
        } catch (e: Exception) {
            TimberWrapper.silentError(e, "Error navigating to MainActivity")
            finish() // Fallback: einfach schließen
        }
    }
}