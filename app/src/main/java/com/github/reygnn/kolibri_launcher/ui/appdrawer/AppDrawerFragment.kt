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
import androidx.navigation.fragment.findNavController
import androidx.recyclerview.widget.LinearLayoutManager
import com.github.reygnn.kolibri_launcher.R
import com.github.reygnn.kolibri_launcher.core.AppConstants
import com.github.reygnn.kolibri_launcher.core.TimberWrapper
import com.github.reygnn.kolibri_launcher.databinding.FragmentAppDrawerBinding
import com.github.reygnn.kolibri_launcher.domain.model.AppInfo
import com.github.reygnn.kolibri_launcher.domain.model.MenuContext
import com.github.reygnn.kolibri_launcher.domain.model.SortOrder
import com.github.reygnn.kolibri_launcher.ui.appcontextmenu.AppContextMenuDialogFragment
import com.github.reygnn.kolibri_launcher.ui.appcontextmenu.ContextMenuHelper
import com.github.reygnn.kolibri_launcher.ui.appcontextmenu.ContextMenuResult
import com.github.reygnn.kolibri_launcher.ui.flow.collectOnStarted
import com.github.reygnn.kolibri_launcher.ui.extensions.handleShortcutLaunch
import com.github.reygnn.kolibri_launcher.domain.usecase.LaunchShortcutUseCase
import com.github.reygnn.kolibri_launcher.ui.main.LauncherViewModel
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import timber.log.Timber
import javax.inject.Inject

/**
 * AppDrawerFragment — the in-app launcher's app list / search screen.
 *
 * Crash safety follows the four-category frame (CLAUDE.md Rule 11):
 *  - EXTERNAL system calls (`startActivity`, `InputMethodManager.show*`)
 *    keep their try/catch with surface-to-user fallback.
 *  - Coroutines launched from view-lifecycle scope use `fragmentExceptionHandler`
 *    plus structural `_binding?.let { } / isAdded` guards instead of nested
 *    try/catch. The handler suppresses uncaught throwables there.
 *  - Pure operations (ViewModel forwards, adapter setters, listener wiring)
 *    are NOT wrapped — programmer errors should reach `silentError` (Rule 9)
 *    and crash loudly in DEBUG. The TODO §2 sweep on this file removed ~22
 *    such defensive wrappers in 2026-05-01.
 *  - Lifecycle teardown (`onDestroyView`) keeps a single outer catch around
 *    the cleanup block so the `finally { super.onDestroyView() }` always runs.
 *
 * Critical for launcher — without a working app drawer, the user cannot
 * reach non-favorite apps.
 */
@AndroidEntryPoint
class AppDrawerFragment : Fragment() {

    // ===========================================
    // INJECTED DEPENDENCIES
    // ===========================================

    @Inject
    lateinit var launchShortcutUseCase: LaunchShortcutUseCase

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

        // Each setup function below has its own internal catch where needed
        // (real I/O / lifecycle paths). Wrapping the orchestrator in a
        // catch(Throwable) was redundant — see TODO §2 / Rule 11.
        setupRecyclerView()
        setupSearch()
        setupSortFab()
        setupSwipeToDismiss()
        observeViewModel()
        setupFragmentResultListener()
    }

    private fun observeViewModel() {
        // Observer 1: App list - Critical for drawer functionality
        viewModel.drawerApps.observe(viewLifecycleOwner) { sortedApps ->
            if (sortedApps != null) {
                masterAppList = sortedApps
                displayFilteredApps(viewModel.appDrawerSearchQuery.value)
            }
        }

        // Observer 2: Sort order
        viewModel.sortOrder.observe(viewLifecycleOwner) { order ->
            if (_binding == null) return@observe
            val iconRes = when (order) {
                SortOrder.ALPHABETICAL -> android.R.drawable.ic_menu_sort_alphabetically
                SortOrder.TIME_WEIGHTED_USAGE -> android.R.drawable.ic_menu_recent_history
                null -> return@observe
            }
            binding.fabSort.setImageResource(iconRes)
        }

        // Observer 3: UI colors
        collectOnStarted(
            flow = viewModel.uiColorsState,
            errorTag = "uiColorsState",
            coroutineContext = Dispatchers.Main + fragmentExceptionHandler,
        ) { colors ->
            if (_binding == null || !isAdded) return@collectOnStarted
            appDrawerAdapter?.setUiColors(colors.textColor, colors.shadowColor)
        }

        // Observer 4: search query → debounced filter
        // The fragmentExceptionHandler on the launch covers any exceptions
        // that escape the inner blocks; CancellationException needs no
        // explicit catch in the inner search-job because `launch { delay; ... }`
        // re-throws it to its parent automatically.
        viewLifecycleOwner.lifecycleScope.launch(Dispatchers.Main + fragmentExceptionHandler) {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.appDrawerSearchQuery.collect { query ->
                    searchJob?.cancel()
                    searchJob = launch {
                        try {
                            delay(AppConstants.SEARCH_DEBOUNCE_DELAY_MS)
                            displayFilteredApps(query)
                        } catch (e: CancellationException) {
                            // Expected on rapid query changes — searchJob.cancel()
                        }
                    }
                }
            }
        }
    }

    private fun setupFragmentResultListener() {
        childFragmentManager.setFragmentResultListener(
            AppContextMenuDialogFragment.REQUEST_KEY,
            viewLifecycleOwner
        ) { _, bundle ->
            // Inner catch kept: this lambda is invoked by the FragmentManager
            // some time after registration, when the dialog produces a result.
            // Anything in the body — bundle parsing, downstream when-branches
            // routing into Activity/Intent calls — can plausibly throw at that
            // later point, and there's no outer launchSafe / coroutine handler
            // here to catch it.
            try {
                val app = longClickedApp
                if (app == null) {
                    Timber.w("Fragment result received but longClickedApp is null")
                    return@setFragmentResultListener
                }

                val action = bundle.getString(AppContextMenuDialogFragment.Companion.RESULT_KEY_ACTION)

                when (val result = ContextMenuResult.parse(action)) {
                    is ContextMenuResult.LaunchShortcut -> handleShortcutLaunch(
                        bundle,
                        viewModel,
                        launchShortcutUseCase
                    )
                    is ContextMenuResult.AppInfo -> showAppInfo(app)
                    is ContextMenuResult.ToggleFavorite -> toggleFavorite(app)
                    is ContextMenuResult.HideApp -> hideApp(app)
                    is ContextMenuResult.ResetUsage -> resetAppUsage(app)
                    // Structurally unreachable: per the architecture
                    // rule (see GetFavoriteAppsUseCase KDoc), hidden
                    // apps applies only to the AppDrawer, so
                    // GetDrawerAppsUseCase (Z. 56) filters hidden
                    // apps out of this listing. A hidden app cannot
                    // be long-pressed here, so the dialog cannot
                    // emit UNHIDE_APP from this fragment. The
                    // branch exists for sealed-when exhaustiveness
                    // only. Compare HomeFragment, where the same
                    // action is live: a favorite that is also
                    // hidden appears in the home screen's favorite
                    // area, can be long-pressed there, and produces
                    // UNHIDE_APP — handled via viewModel.onShowApp.
                    is ContextMenuResult.UnhideApp -> Unit
                    is ContextMenuResult.Unknown -> Timber.w(
                        "Unknown context menu action: ${result.action}"
                    )
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                TimberWrapper.silentError(e, "Error in fragment result listener")
            }
        }
    }

    private fun toggleFavorite(app: AppInfo) {
        viewModel.onToggleFavorite(app)
    }

    private fun hideApp(app: AppInfo) {
        viewModel.onHideApp(app)
    }

    private fun showAppInfo(app: AppInfo) {
        // EXTERNAL: startActivity can throw ActivityNotFoundException
        // if the user uninstalled the app between the long-press and this
        // call. The catch falls back to a viewModel error path that
        // surfaces the failure to the user, so we keep it.
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
        shouldScrollToTop = true
        viewModel.onResetAppUsage(app)
    }

    private fun setupRecyclerView() {
        appDrawerAdapter = AppDrawerAdapter(
            onAppClicked = { app -> viewModel.onAppClicked(app) },
            onAppLongClicked = { app -> showAppContextMenu(app) }
        )

        binding.appsRecyclerView.apply {
            adapter = appDrawerAdapter
            layoutManager = LinearLayoutManager(requireContext())

            // CRASH-SAFE: animations off (prevents bugs on rapid updates).
            itemAnimator = null

            // PERFORMANCE (Monk Approved):
            // The drawer fills the screen (match_parent), so the RecyclerView's
            // size never changes. setHasFixedSize(true) skips a layout pass on
            // every keystroke in the search box.
            setHasFixedSize(true)
        }
    }

    private fun setupSearch() {
        binding.searchEditText.setText(viewModel.appDrawerSearchQuery.value)
        binding.searchEditText.doOnTextChanged { text, _, _, _ ->
            viewModel.onAppDrawerSearchQueryChanged(text.toString())
        }
    }

    private fun setupSortFab() {
        binding.fabSort.setOnClickListener {
            shouldScrollToTop = true
            viewModel.toggleSortOrder()
        }
    }

    /**
     * Wires the app-drawer dismiss gesture: a decisive downward swipe
     * anywhere in the drawer pops the navigation back to the home screen.
     *
     * The gesture detection itself lives in [SwipeDownDismissLayout]
     * (the root view in `fragment_app_drawer.xml`). That class exists
     * because RecyclerView's `requestDisallowInterceptTouchEvent` makes
     * normal `OnTouchListener` / `onInterceptTouchEvent` approaches fail
     * once the list starts scrolling — see the class KDoc for the full
     * mechanism. Don't try to move the detection logic here; it has to
     * live in the View hierarchy where `dispatchTouchEvent` is reachable.
     *
     * The `isAdded` guard handles the teardown race where the swipe
     * gesture completes during fragment detachment (rotation, system
     * kill): `popBackStack` on a detached fragment crashes. The keyboard
     * is hidden automatically via `onPause` once the lifecycle transition
     * triggered by `popBackStack` runs, so no explicit `hideKeyboard`
     * call is needed here.
     */
    private fun setupSwipeToDismiss() {
        binding.appDrawerRoot.onSwipeDown = {
            if (isAdded) {
                findNavController().popBackStack()
            }
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
     * The `_binding != null` checks below are real lifecycle-race guards
     * (the submitList callback runs after a DiffUtil round-trip on a
     * background thread, so the fragment may have been torn down by then).
     */
    private fun submitListToAdapter(list: List<AppInfo>) {
        if (_binding != null && isAdded) {
            appDrawerAdapter?.submitList(list.toList()) {
                if (shouldScrollToTop) {
                    if (_binding != null) {
                        binding.appsRecyclerView.scrollToPosition(0)
                    }
                    shouldScrollToTop = false
                }
            }
        } else {
            Timber.w("Adapter not initialized or fragment not added")
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
        showStatusBar()
        handleAutoShowKeyboard()  // launches its own coroutine with fragmentExceptionHandler
    }

    override fun onPause() {
        super.onPause()
        hideKeyboard()  // has its own internal catch around the IMM call
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
            // 1. Setting asynchron laden — kept as Fallback-Pattern: a thrown
            //    DataStore read defaults to "do not show keyboard", which is
            //    the safer choice for the user.
            val isAutoShowEnabled = try {
                viewModel.isAutoShowKeyboardEnabled()
            } catch (e: Throwable) {
                TimberWrapper.silentError(e, "Error reading autoShowKeyboard setting")
                false
            }

            // 2. Binding check (could have gone away during suspend).
            val currentBinding = _binding ?: return@launch
            val editText = currentBinding.searchEditText

            // 3. Pick the strategy (testable logic).
            val strategy = keyboardShowCoordinator.determineStrategy(
                isViewLaidOut = editText.isLaidOut,
                isViewEffectivelyVisible = editText.canReceiveKeyboardInput(),
                isFragmentAdded = isAdded,
                isAutoShowEnabled = isAutoShowEnabled
            )

            // 4. Execute the strategy.
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
        // Outer catch kept: lifecycle teardown defensive — if anything in
        // here throws (ContextMenuHelper.dismiss can race with the
        // FragmentManager state, viewModel call could throw on a
        // teardown sequence), we still need the finally{} to null the
        // adapter and call super.
        try {
            searchJob?.cancel()
            searchJob = null

            ContextMenuHelper.dismiss(childFragmentManager)

            if (_binding != null) {
                binding.appsRecyclerView.adapter = null
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