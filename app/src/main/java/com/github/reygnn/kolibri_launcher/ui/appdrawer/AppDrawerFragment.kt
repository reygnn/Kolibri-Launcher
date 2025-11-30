package com.github.reygnn.kolibri_launcher.ui.appdrawer

import android.content.Context
import android.content.Intent
import android.content.pm.LauncherApps
import android.content.pm.ShortcutInfo
import android.graphics.Rect
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.InputMethodManager
import androidx.coordinatorlayout.widget.CoordinatorLayout
import androidx.core.view.ViewCompat
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
    private val viewModel: LauncherViewModel by activityViewModels()

    private var _binding: FragmentAppDrawerBinding? = null
    private val binding get() = _binding!!

    private val appSearchFilter = AppSearchFilter()
    private var appDrawerAdapter: AppDrawerAdapter? = null
    private var masterAppList: List<AppInfo> = emptyList()
    private var longClickedApp: AppInfo? = null
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

    // Ultra Paranoia: Coroutine exception handler
    private val fragmentExceptionHandler = CoroutineExceptionHandler { _, throwable ->
        try {
            TimberWrapper.silentError(throwable, "Uncaught exception in AppDrawerFragment")
        } catch (e: Throwable) {
            // Even logging can fail
        }
    }

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
            setupWindowInsets(view)
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
                        Timber.Forest.d("AppDrawerFragment lambda called for ${app.displayName}")
                        viewModel.onAppClicked(app)
                    } catch (e: Throwable) {
                        TimberWrapper.silentError(e, "Error in app click for ${app.packageName}")
                    }
                },
                onAppLongClicked = { app ->
                    try {
                        showAppContextMenu(app)
                    } catch (e: Throwable) {
                        TimberWrapper.silentError(
                            e,
                            "Error in app long click for ${app.packageName}"
                        )
                    }
                }
            )

            binding.appsRecyclerView.apply {
                adapter = appDrawerAdapter
                layoutManager = LinearLayoutManager(requireContext())
                // CRASH-SAFE: Disable animations to prevent IllegalStateException
                itemAnimator = null
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

    private fun setupWindowInsets(view: View) {
        try {
            val fabMargin = try {
                resources.getDimensionPixelSize(R.dimen.spacing_large)
            } catch (e: Throwable) {
                TimberWrapper.silentError(e, "Error getting fab margin")
                AppConstants.FALLBACK_DIMEN_PX
            }

            val initialContentPadding = try {
                Rect(
                    binding.contentContainer.paddingLeft,
                    binding.contentContainer.paddingTop,
                    binding.contentContainer.paddingRight,
                    binding.contentContainer.paddingBottom
                )
            } catch (e: Throwable) {
                TimberWrapper.silentError(e, "Error getting initial padding")
                Rect(0, 0, 0, 0)
            }

            ViewCompat.setOnApplyWindowInsetsListener(binding.appDrawerRoot) { _, insets ->
                try {
                    val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())

                    try {
                        binding.contentContainer.setPadding(
                            initialContentPadding.left,
                            initialContentPadding.top + systemBars.top,
                            initialContentPadding.right,
                            initialContentPadding.bottom + systemBars.bottom
                        )
                    } catch (e: Throwable) {
                        TimberWrapper.silentError(e, "Error setting content padding")
                    }

                    try {
                        val fabLayoutParams =
                            binding.fabSort.layoutParams as? CoordinatorLayout.LayoutParams
                        if (fabLayoutParams != null) {
                            fabLayoutParams.bottomMargin = systemBars.bottom + fabMargin
                            binding.fabSort.layoutParams = fabLayoutParams
                        }
                    } catch (e: Throwable) {
                        TimberWrapper.silentError(e, "Error setting fab margin")
                    }

                    insets
                } catch (e: Throwable) {
                    TimberWrapper.silentError(e, "Error applying window insets")
                    insets  // Return original insets
                }
            }
        } catch (e: Throwable) {
            TimberWrapper.silentError(e, "Error setting up window insets")
            // Window insets won't work, but drawer still functional
        }
    }

    override fun onResume() {
        super.onResume()
        try {
            // Wenn AppDrawer sichtbar wird -> Statusleiste explizit einblenden
            showStatusBar()

            viewLifecycleOwner.lifecycleScope.launch(fragmentExceptionHandler) {
                try {
                    val autoShowKeyboard = viewModel.isAutoShowKeyboardEnabled()
                    if (autoShowKeyboard) {
                        showKeyboard()
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Throwable) {
                    TimberWrapper.silentError(e, "Error checking autoShowKeyboard setting")
                }
            }
        } catch (e: Throwable) {
            TimberWrapper.silentError(e, "Error in onResume showing status bar")
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

    private fun showKeyboard() {
        // Statt post() nehmen wir eine Coroutine im View-Lifecycle.
        // Wenn der View stirbt, wird dieser Block NICHT mehr ausgeführt (oder abgebrochen).
        viewLifecycleOwner.lifecycleScope.launch {
            try {
                // Wartet, bis der View gezeichnet ist (besser als post delay)
                binding.searchEditText.doOnLayout {
                    if (!isAdded) return@doOnLayout

                    val imm =
                        context?.getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager
                    if (binding.searchEditText.requestFocus()) {
                        imm?.showSoftInput(binding.searchEditText, InputMethodManager.SHOW_IMPLICIT)
                    }
                }
            } catch (e: Throwable) {
                // Ignorieren
            }
        }
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