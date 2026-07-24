package com.github.reygnn.kolibri_launcher.ui.onboarding

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
import androidx.recyclerview.widget.LinearLayoutManager
import com.github.reygnn.kolibri_launcher.R
import com.github.reygnn.kolibri_launcher.core.TimberWrapper
import com.github.reygnn.kolibri_launcher.databinding.ActivityOnboardingBinding
import com.github.reygnn.kolibri_launcher.domain.model.AppInfo
import com.github.reygnn.kolibri_launcher.ui.base.BaseActivity
import com.github.reygnn.kolibri_launcher.ui.flow.collectOnStarted
import com.github.reygnn.kolibri_launcher.ui.main.MainActivity
import com.github.reygnn.kolibri_launcher.ui.util.showToastSafe
import com.google.android.material.chip.Chip
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.debounce
import timber.log.Timber
import kotlin.coroutines.EmptyCoroutineContext

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

            // throw RuntimeException("ACRA Test Crash")

        } catch (e: Throwable) {
            TimberWrapper.silentError(e, "Fatal error in onCreate")
            finish() // Graceful exit
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        _binding?.let {
            outState.putString(STATE_SEARCH_QUERY, it.searchEditText.text.toString())
        }
        outState.putSerializable(STATE_LAUNCH_MODE, launchMode)
    }

    /**
     * ULTRA-CLEAN DESTROY
     * Sorgt für sofortige Freigabe von Listenern und View-Referenzen.
     */
    override fun onDestroy() {
        // Outer catch kept: lifecycle teardown defensive — finally{} must
        // always reach super.onDestroy() even if a cleanup step throws.
        try {
            if (_binding != null) {
                // GC-OPTIMIERUNG: Dynamische Views und Listener entfernen
                // (Chips halten Referenzen auf ViewModel via Listener)
                binding.selectionChipGroup.removeAllViews()
                // Adapter vom RecyclerView trennen
                binding.allAppsRecyclerView.adapter = null
            }
            allAppsAdapter = null
            _binding = null
        } catch (e: Throwable) {
            TimberWrapper.silentError(e, "Error in onDestroy")
        } finally {
            super.onDestroy()
        }
    }

    private fun handleWindowInsets() {
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
    }

    private fun setupRecyclerViews() {
        allAppsAdapter = OnboardingAppListAdapter { appInfo ->
            viewModel.onAppToggled(appInfo)
        }
        binding.allAppsRecyclerView.apply {
            adapter = allAppsAdapter
            layoutManager = LinearLayoutManager(this@OnboardingActivity)
            setHasFixedSize(true)
            // CRASH-SAFE: Verhindere IllegalStateException bei state restoration
            itemAnimator = null
        }
    }

    @OptIn(FlowPreview::class)
    private fun setupSearchListener() {
        binding.searchEditText.doOnTextChanged { text, _, _, _ ->
            searchQueryFlow.value = text?.toString() ?: ""
        }

        collectOnStarted(
            flow = searchQueryFlow.debounce(SEARCH_DEBOUNCE_MS),
            errorTag = "searchQuery",
            coroutineContext = EmptyCoroutineContext,
        ) { query ->
            try {
                viewModel.onSearchQueryChanged(query)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                TimberWrapper.silentError(e, "Error in search query changed")
            }
        }
    }

    private fun setupClickListeners() {
        binding.doneButton.setOnClickListener {
            // Inner catch kept: viewModel.onDoneClicked is the user-facing
            // path that completes onboarding; if the underlying use case
            // throws, the fallback navigation (goToMainActivity / finish)
            // is the user's escape hatch.
            try {
                viewModel.onDoneClicked()
            } catch (e: Throwable) {
                TimberWrapper.silentError(e, "Error in done button click")
                if (launchMode == LaunchMode.INITIAL_SETUP) {
                    goToMainActivity()
                } else {
                    finish()
                }
            }
        }
    }

    private fun observeViewModel() {
        collectOnStarted(
            flow = viewModel.uiState,
            errorTag = "uiState",
            coroutineContext = EmptyCoroutineContext,
        ) { state ->
            updateUi(state)
        }
    }

    private fun updateUi(state: OnboardingUiState) {
        // CRASH-SAFE: Check ob binding noch gültig ist
        if (_binding == null) {
            Timber.w("Attempted to update UI after binding was destroyed")
            return
        }
        binding.titleText.setText(state.titleResId)
        binding.subtitleText.setText(state.subtitleResId)
        allAppsAdapter?.submitList(state.selectableApps) ?: run {
            Timber.w("Adapter is null, cannot submit list")
        }
        updateSelectionChips(state.selectedApps)
    }

    override fun handleSpecificEvent(event: OnboardingEvent) {
        // Toasts route through showToastSafe (ui/util), which owns the Samsung
        // StrictMode relax + the Throwable catch (Toast IPC has been observed to
        // throw on Samsung), shared with every other toast in the app.
        when (event) {
            is OnboardingEvent.NavigateToMain -> {
                if (launchMode == LaunchMode.INITIAL_SETUP) {
                    goToMainActivity()
                } else {
                    finish()
                }
            }
            is OnboardingEvent.ShowError -> {
                showToastSafe(event.message, Toast.LENGTH_LONG)
            }
            is OnboardingEvent.ShowLimitReachedToast -> {
                showToastSafe(getString(R.string.favorites_limit_reached, event.limit))
            }
        }
    }

    private fun updateSelectionChips(selectedApps: List<AppInfo>) {
        if (_binding == null) return
        binding.chipsScrollView.visibility = if (selectedApps.isEmpty()) View.INVISIBLE else View.VISIBLE
        binding.selectionChipGroup.removeAllViews()

        for (app in selectedApps) {
            val chip = Chip(this).apply {
                text = app.displayName
                isCloseIconVisible = true
                setOnCloseIconClickListener { viewModel.onAppToggled(app) }
            }
            binding.selectionChipGroup.addView(chip)
        }
    }

    private fun goToMainActivity() {
        try {
            val intent = Intent(this, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
            }
            startActivity(intent)
            finish()
        } catch (e: Throwable) {
            TimberWrapper.silentError(e, "Error navigating to MainActivity")
            finish() // Fallback: einfach schließen
        }
    }
}