package com.github.reygnn.kolibri_launcher.ui.appdrawer

import android.content.Context
import android.content.Intent
import android.content.pm.LauncherApps
import android.content.pm.ShortcutInfo
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.InputMethodManager
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.core.view.doOnLayout
import androidx.core.widget.doOnTextChanged
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.LinearLayoutManager
import com.github.reygnn.kolibri_launcher.R
import com.github.reygnn.kolibri_launcher.core.AppConstants
import com.github.reygnn.kolibri_launcher.core.TimberWrapper
import com.github.reygnn.kolibri_launcher.databinding.FragmentAppDrawerBinding
import com.github.reygnn.kolibri_launcher.domain.model.AppInfo
import com.github.reygnn.kolibri_launcher.domain.model.MenuContext
import com.github.reygnn.kolibri_launcher.domain.model.SortOrder
import com.github.reygnn.kolibri_launcher.ui.appcontextmenu.AppContextMenuAction
import com.github.reygnn.kolibri_launcher.ui.appcontextmenu.AppContextMenuDialogFragment
import com.github.reygnn.kolibri_launcher.ui.appcontextmenu.ContextMenuHelper
import com.github.reygnn.kolibri_launcher.ui.main.LauncherViewModel
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import timber.log.Timber

/**
 * ULTRA CRASH-SAFE AppDrawerFragment
 *
 * Multi-layer exception handling:
 * - All operations catch Throwable (Exception + Error)
 * - CoroutineExceptionHandler for all coroutines
 * - Safe RecyclerView operations with fallbacks
 * - Protected search with debounce error handling
 * - Safe dialog management with state checks
 * - Triple-layer observer protection
 *
 * Critical for launcher - without working app drawer, user cannot open apps!
 */
@AndroidEntryPoint
class AppDrawerFragment : Fragment(R.layout.fragment_app_drawer) {

    // ===========================================
    // VIEWMODEL
    // ===========================================

    private val viewModel: LauncherViewModel by activityViewModels()

    // ===========================================
    // VIEW BINDING
    // ===========================================

    private var _binding: FragmentAppDrawerBinding? = null
    private val binding get() = _binding!!

    // ===========================================
    // ADAPTER & DATA
    // ===========================================

    private var appDrawerAdapter: AppDrawerAdapter? = null
    private var masterAppList: List<AppInfo> = emptyList()

    // ===========================================
    // CONTEXT MENU STATE
    // ===========================================

    private var longClickedApp: AppInfo? = null

    // ===========================================
    // SEARCH STATE
    // ===========================================

    private var searchJob: Job? = null

    /**
     * Controls post-update scrolling behavior.
     *
     * WHY THIS "OLD-SCHOOL" FLAG BEATS A "SEXY" FLOW:
     *
     * Modern Android architecture often suggests using `SharedFlow` or `Channels` (One-Shot Events)
     * in the ViewModel to trigger UI actions like scrolling. However, in the context of
     * `ListAdapter` and `AsyncListDiffer`, that approach introduces a critical race condition.
     *
     * The Problem with Flows:
     * A Flow emits the "Scroll" event immediately when the data changes. The Fragment receives this
     * instantly and calls `scrollToPosition(0)`. However, the Adapter calculates the DiffUtil
     * on a background thread. As a result, the RecyclerView tries to scroll *before* the new
     * items are actually bound and laid out. This results in scrolling the *old* list,
     * inconsistent positioning, or no visible effect at all.
     *
     * The Solution (The "Magic"):
     * By setting this flag to true and checking it inside the `submitList(list) { ... }`
     * commit callback, we guarantee that the scroll command executes strictly *after*
     * the RecyclerView has finished its layout pass with the new data.
     *
     * It may not look like modern "Reactive" code, but it provides frame-perfect UI timing
     * that a decoupled Flow simply cannot guarantee without ugly `postDelayed` hacks.
     */
    private var shouldScrollToTop = false

    // ===========================================
    // COROUTINE MANAGEMENT
    // ===========================================

    private val fragmentExceptionHandler = CoroutineExceptionHandler { _, throwable ->
        try {
            TimberWrapper.silentError(throwable, "Uncaught exception in AppDrawerFragment")
        } catch (e: Throwable) {
            // Even logging can fail
        }
    }

    // ===========================================
    // HELPER CLASSES
    // ===========================================

    private val appSearchFilter = AppSearchFilter()
    private val keyboardShowCoordinator = KeyboardShowCoordinator()


    // ===========================================
    // LIFECYCLE
    // ===========================================

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentAppDrawerBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        try {
            setupRecyclerView()
            setupSearch()
            setupSortFab()
            observeViewModel()
            setupFragmentResultListener()

            // throw RuntimeException("ACRA Test Crash")

        } catch (e: Throwable) {
            TimberWrapper.silentError(e, "Error in onViewCreated")
        }
    }

    private fun observeViewModel() {
        // Observer 1: App list - Critical for drawer functionality
        try {
            viewModel.drawerApps.observe(viewLifecycleOwner) { sortedApps ->
                try {
                    if (sortedApps != null) {
                        masterAppList = sortedApps
                        displayFilteredApps(viewModel.appDrawerSearchQuery.value)
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Throwable) {
                    TimberWrapper.silentError(e, "Error updating master app list")
                    // Don't clear masterAppList - keep showing old data
                }
            }
        } catch (e: Throwable) {
            TimberWrapper.silentError(e, "Error setting up drawerApps observer")
        }

        // Observer 2: Sort order
        try {
            viewModel.sortOrder.observe(viewLifecycleOwner) { order ->
                if (_binding == null) return@observe

                try {
                    val iconRes = when (order) {
                        SortOrder.ALPHABETICAL -> android.R.drawable.ic_menu_sort_alphabetically
                        SortOrder.TIME_WEIGHTED_USAGE -> android.R.drawable.ic_menu_recent_history
                        null -> return@observe
                    }
                    binding.fabSort.setImageResource(iconRes)
                } catch (e: Throwable) {
                    TimberWrapper.silentError(e, "Error updating sort icon")
                    // Keep old icon - not critical
                }
            }
        } catch (e: Throwable) {
            TimberWrapper.silentError(e, "Error setting up sortOrder observer")
        }

        // Observer 3: UI colors
        viewLifecycleOwner.lifecycleScope.launch(Dispatchers.Main + fragmentExceptionHandler) {
            try {
                repeatOnLifecycle(Lifecycle.State.STARTED) {
                    try {
                        viewModel.uiColorsState.collect { colors ->
                            if (_binding == null || !isAdded) return@collect

                            try {
                                appDrawerAdapter?.setUiColors(colors.textColor, colors.shadowColor)
                            } catch (e: Throwable) {
                                TimberWrapper.silentError(e, "Error updating adapter colors")
                                // Keep old colors - not critical
                            }
                        }
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Throwable) {
                        TimberWrapper.silentError(e, "Error in uiColorsState collection")
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                TimberWrapper.silentError(e, "Error in repeatOnLifecycle")
            }
        }

        // Observer 4: Für die Suchanfrage
        viewLifecycleOwner.lifecycleScope.launch(Dispatchers.Main + fragmentExceptionHandler) {
            try {
                repeatOnLifecycle(Lifecycle.State.STARTED) {
                    viewModel.appDrawerSearchQuery.collect { query ->
                        searchJob?.cancel()
                        searchJob = launch {
                            try {
                                delay(AppConstants.SEARCH_DEBOUNCE_DELAY_MS)
                                displayFilteredApps(query)
                            } catch (e: CancellationException) {
                            } catch (e: Throwable) {
                                TimberWrapper.silentError(e, "Error in search delay")
                                try {
                                    displayFilteredApps(query)
                                } catch (fallbackError: Throwable) {
                                    TimberWrapper.silentError(
                                        fallbackError,
                                        "Error in search fallback"
                                    )
                                }
                            }
                        }
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                TimberWrapper.silentError(e, "Error in repeatOnLifecycle for search query")
            }
        }
    }

    private fun setupFragmentResultListener() {
        try {
            childFragmentManager.setFragmentResultListener(
                AppContextMenuDialogFragment.REQUEST_KEY,
                viewLifecycleOwner
            ) { _, bundle ->
                try {
                    val app = longClickedApp
                    if (app == null) {
                        Timber.Forest.w("Fragment result received but longClickedApp is null")
                        return@setFragmentResultListener
                    }

                    val action = try {
                        bundle.getString(AppContextMenuDialogFragment.Companion.RESULT_KEY_ACTION)
                    } catch (e: Throwable) {
                        TimberWrapper.silentError(e, "Error getting action from bundle")
                        null
                    }

                    when (action) {
                        AppConstants.ACTION_LAUNCH_SHORTCUT -> handleShortcutLaunch(bundle)
                        AppContextMenuAction.Companion.ACTION_ID_APP_INFO -> showAppInfo(app)
                        AppContextMenuAction.Companion.ACTION_ID_TOGGLE_FAVORITE -> toggleFavorite(
                            app
                        )

                        AppContextMenuAction.Companion.ACTION_ID_HIDE_APP -> hideApp(app)
                        AppContextMenuAction.Companion.ACTION_ID_RESET_USAGE -> resetAppUsage(app)
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Throwable) {
                    TimberWrapper.silentError(e, "Error in fragment result listener")
                }
            }
        } catch (e: Throwable) {
            TimberWrapper.silentError(e, "Error setting up fragment result listener")
        }
    }

    private fun handleShortcutLaunch(bundle: Bundle) {
        try {
            val shortcut = try {
                bundle.getParcelable(
                    AppContextMenuDialogFragment.Companion.RESULT_KEY_SHORTCUT,
                    ShortcutInfo::class.java
                )
            } catch (e: Throwable) {
                TimberWrapper.silentError(e, "Error getting shortcut from bundle")
                null
            }

            if (shortcut == null) {
                viewModel.onAppInfoError()
                return
            }

            try {
                val launcherApps = requireContext()
                    .getSystemService(Context.LAUNCHER_APPS_SERVICE) as? LauncherApps

                if (launcherApps == null) {
                    TimberWrapper.silentError("LauncherApps service is null")
                    viewModel.onAppInfoError()
                    return
                }

                launcherApps.startShortcut(shortcut, null, null)
            } catch (e: Throwable) {
                TimberWrapper.silentError(e, "Error launching shortcut")
                viewModel.onAppInfoError()
            }
        } catch (e: Throwable) {
            TimberWrapper.silentError(e, "Error in handleShortcutLaunch")
        }
    }

    private fun toggleFavorite(app: AppInfo) {
        try {
            viewModel.onToggleFavorite(app)
        } catch (e: Throwable) {
            TimberWrapper.silentError(e, "Error toggling favorite for ${app.packageName}")
        }
    }

    private fun hideApp(app: AppInfo) {
        try {
            viewModel.onHideApp(app)
        } catch (e: Throwable) {
            TimberWrapper.silentError(e, "Error hiding app ${app.packageName}")
        }
    }

    private fun showAppInfo(app: AppInfo) {
        try {
            val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                data = Uri.fromParts("package", app.packageName, null)
                flags = Intent.FLAG_ACTIVITY_NEW_TASK
            }
            startActivity(intent)
        } catch (e: Throwable) {
            TimberWrapper.silentError(e, "Error showing app info for ${app.packageName}")
            viewModel.onAppInfoError()
        }
    }

    private fun resetAppUsage(app: AppInfo) {
        try {
            shouldScrollToTop = true
            viewModel.onResetAppUsage(app)
        } catch (e: Throwable) {
            TimberWrapper.silentError(e, "Error resetting app usage for ${app.packageName}")
        }
    }

    private fun setupRecyclerView() {
        try {
            appDrawerAdapter = AppDrawerAdapter(
                onAppClicked = { app ->
                    try {
                        // Timber log removed for brevity if needed, or keep it
                        viewModel.onAppClicked(app)
                    } catch (e: Throwable) {
                        TimberWrapper.silentError(e, "Error in app click for ${app.packageName}")
                    }
                },
                onAppLongClicked = { app ->
                    try {
                        showAppContextMenu(app)
                    } catch (e: Throwable) {
                        TimberWrapper.silentError(e, "Error in app long click")
                    }
                }
            )

            binding.appsRecyclerView.apply {
                adapter = appDrawerAdapter
                layoutManager = LinearLayoutManager(requireContext())

                // CRASH-SAFE: Animationen aus (verhindert Bugs bei schnellen Updates)
                itemAnimator = null

                // PERFORMANCE (Monk Approved):
                // Da der Drawer den Screen füllt (match_parent), ändert sich die
                // Grösse des RecyclerViews nie. Das spart Layout-Berechnungen
                // bei jedem einzelnen Tastenanschlag in der Suche!
                setHasFixedSize(false)
            }
        } catch (e: Throwable) {
            TimberWrapper.silentError(e, "CRITICAL: Error setting up RecyclerView")
            // RecyclerView setup failed - drawer won't work, but app won't crash
        }
    }

    private fun setupSearch() {
        try {
            binding.searchEditText.setText(viewModel.appDrawerSearchQuery.value)

            binding.searchEditText.doOnTextChanged { text, _, _, _ ->
                viewModel.onAppDrawerSearchQueryChanged(text.toString())
            }
        } catch (e: Throwable) {
            TimberWrapper.silentError(e, "Error setting up search")
        }
    }

    private fun setupSortFab() {
        try {
            binding.fabSort.setOnClickListener {
                try {
                    shouldScrollToTop = true
                    viewModel.toggleSortOrder()
                } catch (e: Throwable) {
                    TimberWrapper.silentError(e, "Error toggling sort order")
                }
            }
        } catch (e: Throwable) {
            TimberWrapper.silentError(e, "Error setting up sort FAB")
            // FAB won't work, but apps still displayed
        }
    }

    private fun displayFilteredApps(query: String) {
        val currentBinding = _binding
        if (currentBinding == null || !isAdded) return

        viewLifecycleOwner.lifecycleScope.launch(fragmentExceptionHandler) {
            try {
                // 1. Setting holen (Suspend call safe im ViewModel)
                val isAutoLaunchEnabled = try {
                    viewModel.isAutoLaunchEnabled()
                } catch (e: Throwable) {
                    false // Fallback
                }

                // 2. PURE LOGIC aufrufen (Crash-Safe calculation)
                val result = appSearchFilter.filterAndDecide(
                    allApps = masterAppList,
                    query = query,
                    isAutoLaunchEnabled = isAutoLaunchEnabled
                )

                // 3. UI Update basierend auf Ergebnis (Sealed Interface = Exhaustive when)
                when (result) {
                    is AppSearchFilter.FilterResult.ShowList -> {
                        submitListToAdapter(result.apps)
                    }

                    is AppSearchFilter.FilterResult.AutoLaunch -> {
                        viewModel.onAppClicked(result.app)
                        hideKeyboard()
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                TimberWrapper.silentError(e, "Error in displayFilteredApps logic")
                // Fallback: Einfach alles anzeigen
                submitListToAdapter(masterAppList)
            }
        }
    }

    /*
     * Extrahiert, um Codeduplizierung zu vermeiden. Sendet die Liste sicher an den Adapter.
     */
    private fun submitListToAdapter(list: List<AppInfo>) {
        try {
            if (_binding != null && isAdded) {
                appDrawerAdapter?.submitList(list.toList()) {
                    try {
                        if (shouldScrollToTop) {
                            // Checken ob Binding noch da ist, um Crash beim schnellen Wechsel zu vermeiden
                            if (_binding != null) {
                                binding.appsRecyclerView.scrollToPosition(0)
                            }
                            shouldScrollToTop = false
                        }
                    } catch (e: Throwable) {
                        TimberWrapper.silentError(e, "Error scrolling to top after submit")
                        shouldScrollToTop = false
                    }
                }
            } else {
                Timber.Forest.w("Adapter not initialized or fragment not added")
            }
        } catch (e: Throwable) {
            TimberWrapper.silentError(e, "Error submitting list to adapter")
        }
    }

    private fun showAppContextMenu(app: AppInfo) {
        longClickedApp = app

        viewLifecycleOwner.lifecycleScope.launch(fragmentExceptionHandler) {
            try {
                val hasUsage = try {
                    viewModel.hasUsageData(app.packageName)
                } catch (e: Throwable) {
                    false
                }

                if (!isAdded || isDetached) return@launch
                ContextMenuHelper.show(
                    fragmentManager = childFragmentManager,
                    app = app,
                    menuContext = MenuContext.APP_DRAWER,
                    hasUsage = hasUsage
                )

            } catch (e: Throwable) {
                TimberWrapper.silentError(e, "Error in showAppContextMenu")
            }
        }
    }

    override fun onResume() {
        super.onResume()
        try {
            showStatusBar()
            handleAutoShowKeyboard()
        } catch (e: Throwable) {
            TimberWrapper.silentError(e, "Error in onResume")
        }
    }

    override fun onPause() {
        super.onPause()
        try {
            hideKeyboard()
        } catch (e: Throwable) {
            TimberWrapper.silentError(e, "Error in onPause hiding keyboard")
        }
    }

    private fun showStatusBar() {
        val window = activity?.window ?: return
        val controller = WindowInsetsControllerCompat(window, window.decorView)
        controller.show(WindowInsetsCompat.Type.statusBars())
    }

    /**
     * Koordiniert das automatische Anzeigen des Keyboards beim Öffnen des App Drawers.
     *
     * WICHTIG: Diese Methode löst das "doOnLayout wird nie aufgerufen" Problem,
     * indem sie prüft, ob der View bereits gelayoutet ist.
     *
     * @see KeyboardShowCoordinator
     */
    private fun handleAutoShowKeyboard() {
        viewLifecycleOwner.lifecycleScope.launch(fragmentExceptionHandler) {
            try {
                // 1. Setting asynchron laden
                val isAutoShowEnabled = try {
                    viewModel.isAutoShowKeyboardEnabled()
                } catch (e: Throwable) {
                    TimberWrapper.silentError(e, "Error reading autoShowKeyboard setting")
                    false // Fail-safe: nicht anzeigen
                }

                // 2. Binding Check (könnte während suspend weg sein)
                val currentBinding = _binding ?: return@launch
                val editText = currentBinding.searchEditText

                // 3. Strategie bestimmen (testbare Logik)
                val strategy = keyboardShowCoordinator.determineStrategy(
                    isViewLaidOut = editText.isLaidOut,
                    isViewEffectivelyVisible = editText.canReceiveKeyboardInput(),
                    isFragmentAdded = isAdded,
                    isAutoShowEnabled = isAutoShowEnabled
                )

                // 4. Strategie ausführen
                when (strategy) {
                    is KeyboardShowCoordinator.ShowKeyboardStrategy.ShowImmediately -> {
                        Timber.d("Keyboard: View already laid out → showing immediately")
                        showKeyboardNow(editText)
                    }

                    is KeyboardShowCoordinator.ShowKeyboardStrategy.WaitForLayout -> {
                        Timber.d("Keyboard: Waiting for layout pass")
                        editText.doOnLayout { view ->
                            if (isAdded && _binding != null) {
                                showKeyboardNow(view)
                            }
                        }
                    }

                    is KeyboardShowCoordinator.ShowKeyboardStrategy.Skip -> {
                        Timber.d("Keyboard: Skipped (${strategy.reason})")
                        // Nichts tun
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                TimberWrapper.silentError(e, "Error in handleAutoShowKeyboard")
            }
        }
    }

    /**
     * Zeigt das Keyboard SOFORT an.
     * Voraussetzung: View ist bereits gelayoutet und hat Focus-Fähigkeit.
     *
     * @param view Der View, der den Fokus erhalten soll (typischerweise EditText)
     */
    private fun showKeyboardNow(view: View) {
        try {
            if (!isAdded) {
                Timber.w("showKeyboardNow called but fragment not added")
                return
            }

            val imm = context?.getSystemService(Context.INPUT_METHOD_SERVICE)
                    as? InputMethodManager

            if (imm == null) {
                Timber.w("InputMethodManager not available")
                return
            }

            if (view.requestFocus()) {
                view.isFocusableInTouchMode = true  // Für nachfolgende Touch-Events
                // SHOW_IMPLICIT: System entscheidet, ob Keyboard passt
                // Alternative: SHOW_FORCED wäre aggressiver, aber weniger "höflich"
                val shown = imm.showSoftInput(view, InputMethodManager.SHOW_IMPLICIT)
                Timber.d("Keyboard showSoftInput result: $shown")
            } else {
                Timber.w("View could not request focus")
            }
        } catch (e: Throwable) {
            TimberWrapper.silentError(e, "Error in showKeyboardNow")
        }
    }

    /**
     * Prüft, ob ein View tatsächlich Keyboard-Input empfangen kann.
     *
     * Ein View kann `isLaidOut = true` sein, aber trotzdem keinen Input empfangen wenn:
     * - visibility != VISIBLE (GONE oder INVISIBLE)
     * - nicht attached zum Window
     * - das Window selbst nicht sichtbar ist
     *
     * @return true wenn der View bereit ist, Keyboard-Input zu empfangen
     */
    private fun View.canReceiveKeyboardInput(): Boolean {
        return isVisible &&
                isAttachedToWindow &&
                windowVisibility == View.VISIBLE
    }

    private fun hideKeyboard() {
        try {
            if (!isAdded) return

            val imm = context?.getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager
            val view = view // Zwischenspeichern, da view null werden kann

            if (view != null) {
                imm?.hideSoftInputFromWindow(view.windowToken, 0)
            }
        } catch (e: Throwable) {
            TimberWrapper.silentError(e, "Error hiding keyboard")
        }
    }

    override fun onDestroyView() {
        try {
            searchJob?.cancel()
            searchJob = null

            ContextMenuHelper.dismiss(childFragmentManager)

            try {
                if (_binding != null) {
                    binding.appsRecyclerView.adapter = null
                }
            } catch (e: Throwable) {
                TimberWrapper.silentError(e, "Error clearing adapter")
            }

            viewModel.onAppDrawerClosed()

            _binding = null
            longClickedApp = null
        } catch (e: Throwable) {
            TimberWrapper.silentError(e, "Error in onDestroyView")
        } finally {
            appDrawerAdapter = null
            super.onDestroyView()
        }
    }
}