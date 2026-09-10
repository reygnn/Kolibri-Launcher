package com.github.reygnn.kolibri_launcher.ui.onboarding

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.View
import android.widget.Toast
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.core.view.ViewCompat
import androidx.core.view.isVisible
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding
import androidx.core.widget.doOnTextChanged
import androidx.recyclerview.widget.LinearLayoutManager
import com.github.reygnn.kolibri_launcher.R
import com.github.reygnn.kolibri_launcher.core.AppConstants
import com.github.reygnn.kolibri_launcher.core.TimberWrapper
import com.github.reygnn.kolibri_launcher.databinding.ActivityOnboardingBinding
import com.github.reygnn.kolibri_launcher.domain.model.AppInfo
import com.github.reygnn.kolibri_launcher.ui.base.BaseActivity
import com.github.reygnn.kolibri_launcher.ui.flow.collectOnStarted
import com.github.reygnn.kolibri_launcher.ui.main.MainActivity
import com.github.reygnn.kolibri_launcher.ui.util.DefaultLauncherHelper
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

    // ActivityResult launchers must be registered before the Activity reaches
    // STARTED, so they live as field initializers (same pattern as BackupFragment).

    // Reports the outcome of the in-place ROLE_HOME dialog as a short toast.
    // Ground truth is the role state, not the result code (OEM-inconsistent), so we
    // re-read isDefault(). Onboarding continues either way.
    private val roleRequestLauncher: ActivityResultLauncher<Intent> =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
            val messageRes = if (DefaultLauncherHelper.isDefault(this)) {
                R.string.onboarding_default_launcher_set
            } else {
                R.string.onboarding_default_launcher_not_set
            }
            showToastSafe(messageRes, Toast.LENGTH_SHORT)
        }

    // OpenDocument returns the picked file's Uri (or null if cancelled). On a
    // pick we hand the Uri straight to the ViewModel for a full restore.
    private val restoreDocumentLauncher: ActivityResultLauncher<Array<String>> =
        registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
            // A null Uri means the user cancelled the picker. Handing the Uri to the
            // ViewModel is a plain call whose work runs inside launchSafe — nothing
            // here can throw, so no catch (Rule 11).
            uri?.let { viewModel.restoreBackupAndFinish(it.toString()) }
        }

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
        binding.setDefaultLauncherButton.setOnClickListener {
            if (DefaultLauncherHelper.isDefault(this)) {
                // Already default (e.g. a re-tap). requestDefault would fall back to
                // the Home-settings screen with no result callback, so confirm here.
                showToastSafe(R.string.onboarding_default_launcher_set, Toast.LENGTH_SHORT)
            } else {
                // Normal case: the in-place role dialog reports via roleRequestLauncher;
                // onError covers the rare Home-settings-fallback launch failure.
                DefaultLauncherHelper.requestDefault(
                    activity = this,
                    roleLauncher = roleRequestLauncher,
                    onError = { showToastSafe(R.string.onboarding_default_launcher_not_set, Toast.LENGTH_SHORT) }
                )
            }
        }

        binding.restoreBackupButton.setOnClickListener {
            // Inner catch: launching the system document picker is a synchronous
            // Android call that can throw on some OEMs (no matching activity).
            try {
                restoreDocumentLauncher.launch(
                    arrayOf(AppConstants.MIME_TYPE_JSON, AppConstants.MIME_TYPE_ZIP)
                )
            } catch (e: Throwable) {
                TimberWrapper.silentError(e, "Error launching backup picker")
                showToastSafe(getString(R.string.onboarding_restore_failed), Toast.LENGTH_LONG)
            }
        }

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
        // First-run-only extras (set default launcher / restore backup). Gone in
        // EDIT_FAVORITES; HiddenAppsActivity reuses this layout but never sets the
        // flag, so the container stays at its XML `gone` default there.
        binding.setupExtrasContainer.isVisible = state.showSetupExtras
        // While a restore is in flight, disable Done + the setup-extra buttons so a
        // concurrent Done tap can't overwrite the just-restored favorites (the VM
        // guards this too; this is the immediate UI feedback).
        binding.doneButton.isEnabled = !state.isRestoring
        binding.setDefaultLauncherButton.isEnabled = !state.isRestoring
        binding.restoreBackupButton.isEnabled = !state.isRestoring
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
                showToastSafe(getString(event.messageResId), Toast.LENGTH_LONG)
            }
            is OnboardingEvent.ShowLimitReachedToast -> {
                showToastSafe(getString(R.string.favorites_limit_reached, event.limit))
            }
            is OnboardingEvent.ShowMissingAppsToast -> {
                showToastSafe(
                    resources.getQuantityString(
                        R.plurals.onboarding_restore_missing_apps, event.count, event.count
                    ),
                    Toast.LENGTH_SHORT
                )
            }
            is OnboardingEvent.ShowDroppedLayersToast -> {
                showToastSafe(
                    resources.getQuantityString(
                        R.plurals.backup_import_wallpaper_layers_dropped, event.count, event.count
                    ),
                    Toast.LENGTH_SHORT
                )
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