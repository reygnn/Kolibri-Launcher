package com.github.reygnn.kolibri_launcher

import android.os.Bundle
import android.view.View
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
 * ARCHITECTURE NOTE: Shared UI Components with OnboardingActivity
 *
 * This activity provides the user interface for managing hidden apps. It deliberately reuses
 * several UI-related components from OnboardingActivity to ensure a consistent user experience
 * and avoid code duplication.
 *
 * SHARED COMPONENTS (UI Layer):
 * - XML Layout: `activity_onboarding.xml` is used for both screens
 * - RecyclerView Adapter: `OnboardingAppListAdapter` displays the selectable app list
 * - UI State Data Class: `OnboardingUiState` describes the screen state (title, subtitle, app list)
 *
 * DELIBERATELY SEPARATED COMPONENTS (Business Logic Layer):
 * - ViewModel: This activity uses its own `HiddenAppsViewModel` with separate logic for
 *   loading and saving hidden apps via the `AppVisibilityRepository`
 * - Events: Uses a dedicated `HiddenAppsEvent` sealed class for communication, preventing
 *   this screen from needing to know about irrelevant events from the onboarding flow
 *   (e.g., `ShowLimitReachedToast`)
 *
 * This approach maximizes UI code reuse while ensuring clean separation of responsibilities
 * at the business logic layer (Single Responsibility Principle).
 */
@AndroidEntryPoint
class HiddenAppsActivity : BaseActivity<UiEvent, HiddenAppsViewModel>() {

    // CRASH-SAFE: Nullable binding mit safe getter
    private var _binding: ActivityOnboardingBinding? = null
    private val binding get() = _binding ?: throw IllegalStateException("Binding accessed after onDestroy")

    override val viewModel: HiddenAppsViewModel by viewModels()
    private var appSelectionAdapter: OnboardingAppListAdapter? = null

    // Search Debouncing
    private val searchQueryFlow = MutableStateFlow("")
    private companion object {
        private const val SEARCH_DEBOUNCE_MS = 300L
        private const val STATE_SEARCH_QUERY = "search_query"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        viewModel.initialize()

        try {
            WindowCompat.setDecorFitsSystemWindows(window, false)
            _binding = ActivityOnboardingBinding.inflate(layoutInflater)
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
            finish() // Graceful exit bei kritischem Fehler
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
            // CRASH-SAFE: Cleanup in richtiger Reihenfolge
            appSelectionAdapter = null
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
            appSelectionAdapter = OnboardingAppListAdapter { appInfo ->
                try {
                    viewModel.onAppToggled(appInfo)
                } catch (e: Exception) {
                    TimberWrapper.silentError(e, "Error toggling app: ${appInfo.packageName}")
                }
            }

            binding.allAppsRecyclerView.apply {
                adapter = appSelectionAdapter
                layoutManager = LinearLayoutManager(this@HiddenAppsActivity)
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
                    finish() // Fallback: einfach schließen
                }
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
                        throw e  // ✅ HINZUFÜGEN
                    } catch (e: Exception) {
                        TimberWrapper.silentError(e, "Error updating UI")
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

            // CRASH-SAFE: Check ob adapter noch existiert
            appSelectionAdapter?.submitList(state.selectableApps) ?: run {
                Timber.w("Adapter is null, cannot submit list")
            }

            updateSelectionChips(state.selectedApps)
        } catch (e: IllegalStateException) {
            TimberWrapper.silentError(e, "View not attached, skipping UI update")
        } catch (e: Exception) {
            TimberWrapper.silentError(e, "Error updating UI")
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

    /**
     * Implements the abstract method from BaseActivity.
     * This screen's ViewModel only uses generic UiEvents (like ShowToast), which are already
     * handled in the BaseActivity. Therefore, this method can remain empty.
     */
    override fun handleSpecificEvent(event: UiEvent) {
        // No app-specific events are sent from AppNamesViewModel, so this is intentionally empty.
    }
}