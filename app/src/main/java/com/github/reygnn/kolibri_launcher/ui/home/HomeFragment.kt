package com.github.reygnn.kolibri_launcher.ui.home

import android.annotation.SuppressLint
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.content.res.Resources
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.text.format.DateFormat
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.ViewTreeObserver
import androidx.activity.OnBackPressedCallback
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.lifecycle.lifecycleScope
import android.widget.LinearLayout
import androidx.recyclerview.widget.LinearLayoutManager
import com.github.reygnn.kolibri_launcher.BuildConfig
import com.github.reygnn.kolibri_launcher.R
import com.github.reygnn.kolibri_launcher.core.AppConstants
import com.github.reygnn.kolibri_launcher.core.TimberWrapper
import com.github.reygnn.kolibri_launcher.databinding.FragmentHomeBinding
import com.github.reygnn.kolibri_launcher.domain.model.AppInfo
import com.github.reygnn.kolibri_launcher.domain.model.FavoritesAlignment
import com.github.reygnn.kolibri_launcher.domain.model.MenuContext
import com.github.reygnn.kolibri_launcher.domain.model.TimeBasedEvent
import com.github.reygnn.kolibri_launcher.domain.model.TimeBasedEventType
import com.github.reygnn.kolibri_launcher.domain.model.UiColorsState
import com.github.reygnn.kolibri_launcher.domain.model.WallpaperState
import com.github.reygnn.kolibri_launcher.ui.home.wallpaper.WallpaperCompositeKey
import com.github.reygnn.kolibri_launcher.ui.home.wallpaper.WallpaperCompositeCache
import com.github.reygnn.kolibri_launcher.ui.home.wallpaper.WallpaperViewBinder
import com.github.reygnn.kolibri_launcher.ui.home.wallpaper.WallpaperRenderScheduler
import com.github.reygnn.kolibri_launcher.ui.home.wallpaper.DecodedWallpaperBitmap
import com.github.reygnn.kolibri_launcher.ui.home.wallpaper.decodeBoundedWallpaperBitmap
import com.github.reygnn.kolibri_launcher.ui.util.LaunchTrace
import com.github.reygnn.kolibri_launcher.ui.util.WallpaperImagePicker
import com.github.reygnn.kolibri_launcher.ui.util.toHorizontalGravity
import com.github.reygnn.kolibri_launcher.ui.appcontextmenu.AppContextMenuDialogFragment
import com.github.reygnn.kolibri_launcher.ui.appcontextmenu.ContextMenuHelper
import com.github.reygnn.kolibri_launcher.ui.appcontextmenu.ContextMenuResult
import com.github.reygnn.kolibri_launcher.ui.flow.collectOnStarted
import com.github.reygnn.kolibri_launcher.ui.extensions.handleShortcutLaunch
import com.github.reygnn.kolibri_launcher.domain.model.UiState
import com.github.reygnn.kolibri_launcher.domain.usecase.LaunchShortcutUseCase
import com.github.reygnn.kolibri_launcher.ui.main.LauncherViewModel
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import timber.log.Timber
import javax.inject.Inject

/*
 * =============================================================================
 *                    HomeFragment — Size & Refactoring Notes
 * =============================================================================
 *
 * Status: Post-Brocken-A (2026-05-03) + later trimming. Now 1593 lines,
 * 11 try/catch blocks (7 Throwable). Down from 2657 / 59 / 51. The file
 * is at its structural floor — see "Why the floor is here" below
 * before proposing further size reduction.
 *
 *
 * Why the floor is here
 * ---------------------
 * If you are reading this because a fresh review flagged the file size
 * and you are tempted to split it, please read this section first. The
 * floor argument has receipts.
 *
 * Account for what is actually in the file:
 *
 *   ~150 lines  this header KDoc — documentation, not code
 *   ~60 lines   imports
 *   ~80 lines   onDestroyView teardown — 14 setListener(null) calls
 *               plus wallpaperView callback nulls plus _binding
 *               nulling. Each listener must be released explicitly;
 *               nothing here folds into a loop without losing type
 *               information or making the teardown harder to audit.
 *   ~120 lines  eight Android lifecycle overrides (onCreate,
 *               onCreateView, onViewCreated, onConfigurationChanged,
 *               onStart, onResume, onPause, onDestroyView). These are
 *               Android Fragment contract — they cannot live anywhere
 *               but in the Fragment subclass.
 *   ~280 lines  eight collectOnStarted observer blocks, each tied to
 *               a specific ViewModel flow with its own dispatch logic.
 *               The Fragment.collectOnStarted helper (in ui.flow)
 *               already collapsed nine repeatOnLifecycle blocks into
 *               a single extension; the per-observer bodies are at
 *               their minimum-information form.
 *   ~150 lines  setup methods (setupGestures, setupDoubleTapActions,
 *               setupBackPressHandler, setupHomeWindowInsets,
 *               setupFragmentResultListener, registerLayerImagePicker).
 *               Each requires binding, viewLifecycleOwner, or
 *               childFragmentManager — Fragment-bound by definition.
 *               Moving them to a sibling class adds construction and
 *               wiring boilerplate without removing complexity.
 *   ~1100 lines remaining — the actual rendering, layout, color, chip,
 *               and wallpaper glue. Already heavily delegated: pure
 *               logic lives in calculator / formatter / state classes
 *               (see "Pure-logic extractions" below) covered by JVM
 *               unit tests; wallpaper edit mode lives in
 *               WallpaperEditController; wallpaper diff lives in
 *               WallpaperViewBinder + WallpaperViewDiff. What remains
 *               here is the View-side glue that cannot be tested
 *               without Robolectric — and Robolectric is the wrong
 *               tool for fast feedback on this kind of plumbing.
 *
 * The C-developer instinct ("just #include the setup methods") would
 * relocate lines without reducing complexity. Same reasoning as the
 * Fragment-delegate split below: lines move, total surface stays the
 * same, plus extra import / construction / wiring boilerplate.
 *
 * Floor reached. Future size complaints in audits are budget-aware
 * statements ("this is a big file") rather than action items.
 *
 *
 * Pure-logic extractions: what's done
 * -----------------------------------
 * Decision logic most prone to silent regression has been pulled into
 * pure Kotlin classes covered by fast JVM unit tests, independent of
 * the Android framework:
 *
 *   LayerButtonsState       — layer-edit button visibility / enabled / alpha
 *   SnapIconResolver        — drawable selection for the four snap buttons
 *   DoubleClickDetector     — threshold detection with an injected clock
 *   FilenameBuilder         — export filenames (backup + usage) with injected clock
 *   ImportOptionsUiState    — dialog checkbox state from BackupPreview
 *   MissingAppsFormatter    — truncation / overflow of the missing-apps list
 *   ImportSuccessMessage    — sealed success-message selection
 *   ContextMenuResult       — context-menu action routing (parse + sealed
 *                             interface; bundle read stays in the Fragment)
 *   WallpaperEditTransition — enter / exit state transition for wallpaper
 *                             edit mode (sealed interface + WallpaperEditState
 *                             data class; listener setup stays in the
 *                             Fragment)
 *   WallpaperSaveAction     — three-way decision (all layers / single /
 *                             none) shared by save-button and pre-layer-op
 *                             transform saver
 *
 * Plus boilerplate reduction:
 *
 *   Fragment.collectOnStarted (ui.flow) — collapsed nine
 *     repeatOnLifecycle(STARTED) blocks (eight here, one in
 *     AppDrawerFragment) into a single extension.
 *
 *
 * The four-category frame for try/catch
 * -------------------------------------
 * The Brocken-A sweep classified every catch in this file under one
 * of four categories. The frame is preserved here because it is also
 * the rule for any catch added in the future. Reviewers: please
 * apply.
 *
 *   Expected errors (I/O, parse, missing package) — caught with
 *     specific exception types, surfaced where they matter, otherwise
 *     logged at the appropriate level. Never swallowed. Examples in
 *     this file: loadBitmapFromUri (FileNotFoundException +
 *     SecurityException + OutOfMemoryError under the Throwable
 *     umbrella), showAppInfo (ActivityNotFoundException),
 *     getDimensionPixelSize sites (Resources.NotFoundException under
 *     ProGuard).
 *
 *   Teardown races (fragment gone, coroutine still delivering) —
 *     prevented structurally via viewLifecycleOwner.lifecycleScope
 *     and local _binding snapshots, not masked with a post-hoc
 *     catch. Example: updateWallpaper.
 *
 *   Programmer errors (NPE, IllegalState, IndexOutOfBounds) — bugs,
 *     not conditions. Crash loudly in DEBUG via silentError;
 *     swallowing them produces invisibly broken home screens, which
 *     is the failure mode this file used to suffer from. Removed
 *     wholesale in the sweep.
 *
 *   Unrecoverable / HOME-Activity-resilience boundaries —
 *     system-callback paths where letting an exception propagate
 *     would crash the launcher. Two preserved sites:
 *     setupGestures' setOnTouchListener and
 *     DoubleClickListener.onClick. Both are documented inline. The
 *     same trade-off applies in per-item recovery loops
 *     (renderFavorites, createAppButton outer
 *     catches): one bad item shouldn't break the whole list.
 *
 *
 * Fragment-delegate split: deferred indefinitely
 * ----------------------------------------------
 * Splitting this file along the ViewModel's delegate/ pattern
 * (FavoritesRenderer, TimeChipsRenderer, etc.) is largely cosmetic:
 *
 *   - Fragment-side delegates still depend on View, binding, and
 *     LifecycleOwner. They cannot be unit-tested with JUnit + MockK
 *     the way ViewModel delegates can — testing them needs
 *     Robolectric or instrumented tests, both of which this project
 *     deliberately avoids. A split improves readability and review
 *     surface, but does not directly improve unit-test coverage.
 *   - The lines don't disappear; they move. Same total complexity,
 *     spread over more files plus extra import / construction /
 *     wiring boilerplate.
 *   - Solo-developer project: merge-conflict surface is irrelevant.
 *
 * Triggers that would flip this from "won't do" to "do":
 *   - Multiple developers hitting merge conflicts in HomeFragment
 *     regularly.
 *   - Reviews on HomeFragment PRs consistently slow or shallow.
 *   - One UI region accumulating new features fast enough that
 *     isolating it would materially speed up feature work.
 *
 * None of these currently apply.
 *
 *
 * Would more Robolectric / androidTest coverage flip this?
 * --------------------------------------------------------
 * Reasonable question, asked here so the next audit doesn't have to
 * re-derive the answer. No, it would not — for three reasons.
 *
 *   1. The bug-prone logic is already extracted. Every calculator,
 *      formatter, state class, resolver, transition, and detector
 *      listed above has JVM unit tests. What remains in the Fragment
 *      is View-setter glue: "take calculator output, call setter
 *      with it." A Robolectric test for that glue would assert
 *      "setter was called with X" — a tautology against code that
 *      literally calls setter with X. No new logical coverage.
 *
 *   2. A renderer split does not produce a testable API. A
 *      hypothetical FavoritesRenderer would still need binding,
 *      resources, and viewModel; its only output is View mutation.
 *      Testing it requires inflating a binding (Robolectric or
 *      instrumented), mocking a ViewModel, and asserting a View
 *      tree — i.e. testing the Fragment with one extra indirection.
 *      The "unit test" framing is misleading.
 *
 *   3. Robolectric and androidTests are strictly more expensive to
 *      maintain than JVM tests. The KolibriLauncherApp test-app
 *      leak (TODO §6) is a representative pain point: an
 *      AGP-/Robolectric-version interaction silently sprung an OOM
 *      across the suite. Each Robolectric test added is a new slot
 *      for that class of failure. The current dose — one
 *      HomeFragmentRobolectricTest as smoke-backstop — is the right
 *      one. Multiplying by a sibling-renderer count would multiply
 *      the maintenance surface without multiplying coverage.
 *
 * Rule 10 (empty androidTest/) is a deliberate, well-reasoned choice.
 * The trade-offs do not change because a code split exists.
 *
 * =============================================================================
 */

@AndroidEntryPoint
class HomeFragment : Fragment() {

    // ===========================================
    // INJECTED DEPENDENCIES
    // ===========================================

    @Inject
    lateinit var launchShortcutUseCase: LaunchShortcutUseCase

    /** In-memory cache of the decoded display composite (Option D §9.4) — lets
     *  drawer→home re-attach the wallpaper without re-decoding. */
    @Inject
    lateinit var compositeCache: WallpaperCompositeCache

    // ===========================================
    // VIEWMODEL
    // ===========================================

    private val viewModel: LauncherViewModel by activityViewModels()

    // ===========================================
    // VIEW BINDING
    // ===========================================

    private var _binding: FragmentHomeBinding? = null
    private val binding get() = _binding!!

    private val favoritesAdapter by lazy {
        HomeFavoritesAdapter(
            onAppClick = { app -> viewModel.onAppClicked(app) },
            onAppLongClick = { app -> showAppContextMenu(app) },
        )
    }

    // ===========================================
    // LAYOUT CACHE - COMPUTED VALUES
    // ===========================================

    private var currentTextSizePx: Float = 0f
    private var currentVerticalPaddingPx: Int = 0
    private var isCurrentFontBold: Boolean = AppConstants.DEFAULT_FONT_BOLD
    private var currentFavoritesAlignment: FavoritesAlignment = AppConstants.DEFAULT_FAVORITES_ALIGNMENT
    private var currentUserPreferredMarginPx: Int = 0

    // ===========================================
    // CONTEXT MENU STATE
    // ===========================================

    private var longClickedApp: AppInfo? = null

    // CoroutineExceptionHandler for HomeFragment scopes. Two responsibilities,
    // mirrored from MainActivity.mainActivityExceptionHandler:
    //
    //   1. Log uncaught exceptions via silentError (RELEASE: logged; DEBUG:
    //      silentError throws RuntimeException per Rule 9).
    //   2. Re-throw the original throwable in DEBUG so programmer errors
    //      surface instead of being silently absorbed by the handler.
    //
    // The previous shape used a bare `catch (e: Throwable)` with no fallback,
    // which swallowed silentError's Rule-9 RuntimeException — defeating
    // Rule 9 across every coroutine running under this handler. Layout now:
    // `try` is tight around the logging call only; the DEBUG re-throw lives
    // outside the catch. The catch's last-resort fallback is System.err so
    // we don't lose the original error if Timber itself crashes.
    private val fragmentExceptionHandler = CoroutineExceptionHandler { _, throwable ->
        try {
            TimberWrapper.silentError(throwable, "Uncaught exception in HomeFragment")
        } catch (loggingError: Throwable) {
            // No suspension point in this block — synchronous body (AUDIT-12 whitelist review).
            // Catch kept (Expected error, four-category frame): the
            // recursion-into-error-pipeline guard documented in the field
            // KDoc above. If Timber itself crashes, fall back to stderr
            // so the original error is not lost.
            System.err.println(
                "HomeFragment logging failed: ${loggingError.message}; " +
                    "original error: ${throwable.message}"
            )
        }

        if (BuildConfig.DEBUG) {
            throw throwable
        }
    }

    // ===========================================
    // HELPER / CALCULATOR CLASSES
    // ===========================================

    private val layoutCalculator = LayoutCalculator()
    private val topMarginCalculator = TopMarginCalculator()
    private val contentSpacingCalculator = ContentSpacingCalculator()
    private val wallpaperViewBinder = WallpaperViewBinder(
        // suspend loader: the decode runs off the main thread. The binder only
        // calls it for plans that actually load bitmaps (SwitchToSingleLayer /
        // FullRebuild), so a property-only update never hits I/O.
        // Traced (jank): the bounded BitmapFactory decode, off-main inside
        // withContext(IO). The biggest time cost of a rebuild, but not a
        // Main-thread frame-drop source. Synchronous on the IO thread.
        bitmapLoader = { uri ->
            withContext(Dispatchers.IO) {
                LaunchTrace.section(LaunchTrace.Names.WALLPAPER_DECODE) {
                    loadBitmapFromUri(uri)
                }
            }
        }
    )

    /**
     * Serializes wallpaper renders latest-wins: a newer state cancels the
     * in-flight render of the previous one. The invariant lives in the
     * scheduler so it can be unit-tested — see WallpaperRenderScheduler.
     */
    private val wallpaperRenderScheduler = WallpaperRenderScheduler()

    /**
     * Owns the wallpaper-edit-mode click listeners, layer-buttons state,
     * snap controls, toolbar dim/dock, and view-transform persistence
     * during edit mode. Lifetime is tied to [_binding] — created in
     * [onViewCreated], nulled in [onDestroyView]. The controller's
     * methods don't repeat `_binding == null` guards because the
     * controller cannot outlive the binding.
     */
    private var wallpaperEditController: WallpaperEditController? = null

    /**
     * Last edit-mode value the wallpaper was rendered for, so the edit-mode
     * observer only re-renders on an actual toggle. Without this, the observer's
     * initial `STARTED` emission double-renders on every view re-creation
     * (drawer→home) — Observer 7 (state) already renders the correct target there.
     */
    private var lastRenderedWallpaperEditMode: Boolean? = null

    private var layerPickerLauncher: androidx.activity.result.ActivityResultLauncher<String>? = null


    // ===========================================
    // LIFECYCLE
    // ===========================================

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // registerForActivityResult must run before the fragment reaches
        // STARTED and exactly once per fragment instance. onViewCreated runs
        // again on every Home<->AppDrawer view recreation, so registering the
        // launcher there accumulated a fresh registration (callback +
        // LifecycleObserver, keyed fragment_<who>_rq#N) per round trip — they
        // are only removed on the fragment's own ON_DESTROY, not on view
        // teardown. onCreate runs once per fragment instance, so the launcher
        // is registered once. (AUDIT-5 #2.)
        registerLayerImagePicker()
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentHomeBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        // No try/catch: this is synchronous lifecycle init, not a coroutine
        // body. Each call below is either a pure StateFlow read, a setter,
        // a system-API call wrapped at its own boundary, or a coroutine
        // launcher that has its own safety net via launchSafe / catchSafe.
        // A bare catch(Throwable) here would swallow programmer errors
        // (NPE, IllegalState) and turn them into invisible "home screen
        // half-broken" states — exactly the failure mode the file-header
        // try/catch-audit calls out. Real init failures (inflate, OOM)
        // belong to silentDeath, not silentError.
        recalculateLayoutCache(
            viewModel.layoutScaleState.value,
            viewModel.verticalPaddingState.value,
            viewModel.isFontBoldState.value
        )

        currentFavoritesAlignment = viewModel.favoritesAlignmentState.value
        applyTopMargin(viewModel.contentTopMarginState.value)
        applyLayoutToExistingViews()

        hideStatusBar()
        setupBackPressHandler()
        setupFavoritesRecyclerView()
        setupHomeGestures()
        setupDoubleTapActions()
        setupFragmentResultListener()
        setupHomeWindowInsets()

        wallpaperEditController = WallpaperEditController(
            binding = binding,
            viewModel = viewModel,
            launchLayerPicker = {
                layerPickerLauncher?.let { WallpaperImagePicker.launch(it) }
            },
            rerenderWallpaper = { updateWallpaper(viewModel.wallpaperState.value) },
        )

        observeViewModel()
        observeLayoutChanges()
    }

    /**
     * Registers the ActivityResultLauncher for the layer image picker.
     * Called from [onCreate] — exactly once per fragment instance and before
     * onStart() (Fragment lifecycle requirement). Do NOT call it from
     * [onViewCreated]: that runs again on every view recreation and would
     * accumulate one registration per Home<->AppDrawer round trip.
     */
    private fun registerLayerImagePicker() {
        layerPickerLauncher = registerForActivityResult(
            WallpaperImagePicker.contract()
        ) { uri ->
            if (uri != null) {
                // The delegate copies the image to internal storage right
                // away, so takePersistableUriPermission would be wasted
                // effort here (the original content URI is never used again).
                //
                // No try/catch: onAddWallpaperLayer is fire-and-forget
                // (`scope.launchSafe { … }`) and Timber.d cannot throw —
                // a bare catch(Throwable) here was dead code.
                viewModel.onAddWallpaperLayer(uri)
                Timber.d("Layer added from picker: $uri")
            }
        }
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        Timber.d("⟳ Configuration changed - orientation=${newConfig.orientation}")
        // v4 §3a/R2: display metrics changed -> the composite key changed, so any cached
        // composite is now wrong-resolution and misses. Re-render the current state (a miss
        // falls to the correct per-layer path) and request a warm at the new resolution; the
        // next drawer->home is a fresh one-texture hit. No DataStore emission fires on a config
        // change, so this is the trigger.
        if (view != null) {
            updateWallpaper(viewModel.wallpaperState.value)
        }
        viewModel.onDisplayConfigChanged()
    }

    // ============================================================================
    // OBSERVERS - PURE REACTIVE!
    // ============================================================================

    private fun observeViewModel() {
        // Per-observer inner try/catch blocks removed in the §9.3
        // follow-up sweep. `collectOnStarted` already wraps each
        // observer body in its own two-layer catch (collect + lifecycle)
        // — the inner per-collect catches were redundant Rule 11
        // violations against pure View-property writes / when-pattern
        // dispatch / state reads.

        // Observer 1: Favorites
        collectOnStarted(
            flow = viewModel.favoriteAppsState,
            errorTag = "favorites",
            coroutineContext = Dispatchers.Main + fragmentExceptionHandler,
        ) { favState ->
            if (_binding == null) return@collectOnStarted

            Timber.d("Favorites state: ${favState::class.simpleName}")

            when (favState) {
                is UiState.Loading -> clearAllViews()
                is UiState.Success -> {
                    val colors = viewModel.uiColorsState.value
                    renderFavorites(favState.data.apps, colors)
                }

                is UiState.Error -> {
                    viewModel.onFavoriteAppsError(favState.message)
                    clearAllViews()
                }
            }
        }

        // Observer 3: Time, date, battery — one per-field collector each.
        // uiState is a combine of time/date/battery/events, so a battery tick
        // (frequent while charging) re-emits a distinct HomeUiState even when
        // time/date are unchanged. A single collector setting all three would
        // then re-run setText (measure/layout/invalidate) on the unchanged
        // time/date fields. Per-field map + distinctUntilChanged sets each
        // TextView only when its own string actually changes — same idiom as
        // Observer 4 below. (AUDIT-16 N1.)
        collectOnStarted(
            flow = viewModel.uiState.map { it.timeString }.distinctUntilChanged(),
            errorTag = "timeString",
            coroutineContext = Dispatchers.Main + fragmentExceptionHandler,
        ) { timeString ->
            if (_binding == null) return@collectOnStarted
            binding.timeText.text = timeString
        }

        collectOnStarted(
            flow = viewModel.uiState.map { it.dateString }.distinctUntilChanged(),
            errorTag = "dateString",
            coroutineContext = Dispatchers.Main + fragmentExceptionHandler,
        ) { dateString ->
            if (_binding == null) return@collectOnStarted
            binding.dateText.text = dateString
        }

        collectOnStarted(
            flow = viewModel.uiState.map { it.batteryString }.distinctUntilChanged(),
            errorTag = "batteryString",
            coroutineContext = Dispatchers.Main + fragmentExceptionHandler,
        ) { batteryString ->
            if (_binding == null) return@collectOnStarted
            binding.batteryText.text = batteryString
        }

        // Observer 4: TimeBasedEvents
        collectOnStarted(
            flow = viewModel.uiState
                .map { it.timeBasedEvents }
                .distinctUntilChanged(),
            errorTag = "events",
            coroutineContext = Dispatchers.Main + fragmentExceptionHandler,
        ) { events ->
            if (_binding == null) return@collectOnStarted
            updateEventsIndicator(events)
        }

        // Observer 5: Colors
        collectOnStarted(
            flow = viewModel.uiColorsState,
            errorTag = "colors",
            coroutineContext = Dispatchers.Main + fragmentExceptionHandler,
        ) { colors ->
            if (_binding == null) return@collectOnStarted
            updateAllColors(colors)
        }

        // Observer 7: Wallpaper State
        collectOnStarted(
            flow = viewModel.wallpaperState,
            errorTag = "wallpaper state",
            coroutineContext = Dispatchers.Main + fragmentExceptionHandler,
        ) { wallpaperState ->
            if (_binding == null) return@collectOnStarted
            updateWallpaper(wallpaperState)
        }

        // Observer 8: Wallpaper Edit Mode
        collectOnStarted(
            flow = viewModel.isWallpaperEditMode,
            errorTag = "wallpaper edit mode",
            coroutineContext = Dispatchers.Main + fragmentExceptionHandler,
        ) { isEditMode ->
            if (_binding == null) return@collectOnStarted
            // Inner try/catch removed per Rule 11 — applyEditMode has its
            // own outer catch as the orchestration boundary.
            wallpaperEditController?.applyEditMode(isEditMode)
            applyWallpaperEditModeToGestures(isEditMode)
            // Re-evaluate the scrim: it must be hidden while adjusting the
            // wallpaper (so the user sees its true appearance) and restored after.
            applyScrim()
            // Swap representation ONLY on an actual toggle: EDIT shows the real
            // layers, DISPLAY shows the flattened composite (Option D §9.4). On the
            // initial STARTED emission (e.g. drawer→home view re-creation) Observer 7
            // already renders the correct target, so re-rendering here would just
            // double-decode. applyEditMode does not depend on the bound bitmaps
            // (layer UI refreshes in onRebuildComplete), so this async re-render is
            // safe to follow it.
            if (lastRenderedWallpaperEditMode != null && lastRenderedWallpaperEditMode != isEditMode) {
                updateWallpaper(viewModel.wallpaperState.value)
            }
            lastRenderedWallpaperEditMode = isEditMode
        }

        // Observer: persisted FAB position. Re-applies the cluster's
        // on-screen location whenever DataStore emits a new value, so
        // a drag-then-rotate-then-edit-again sequence places the FAB
        // where the user last left it.
        collectOnStarted(
            flow = viewModel.fabPosition,
            errorTag = "wallpaper-edit FAB position",
            coroutineContext = Dispatchers.Main + fragmentExceptionHandler,
        ) { position ->
            if (_binding == null) return@collectOnStarted
            wallpaperEditController?.applyFabPosition(position)
        }

        // Observer: wallpaper backdrop (system wallpaper / black). Keeps the
        // edit-panel toggle icon in sync with the persisted choice. The actual
        // on-screen backdrop is driven by MainActivity's own observer; this one
        // only reflects state into the edit UI.
        collectOnStarted(
            flow = viewModel.wallpaperBackdrop,
            errorTag = "wallpaper-edit backdrop",
            coroutineContext = Dispatchers.Main + fragmentExceptionHandler,
        ) { backdrop ->
            if (_binding == null) return@collectOnStarted
            wallpaperEditController?.applyBackdrop(backdrop)
        }

        // Observer: user-controlled wallpaper scrim. Its own collector (drives a
        // View alpha, not the layout cache) — edit-mode gating is handled in
        // applyScrim, re-triggered by Observer 8 above.
        collectOnStarted(
            flow = viewModel.wallpaperScrimAlphaState,
            errorTag = "wallpaper scrim",
            coroutineContext = Dispatchers.Main + fragmentExceptionHandler,
        ) {
            if (_binding == null) return@collectOnStarted
            applyScrim()
        }

    }

    /**
     * Applies the user's wallpaper scrim to the [R.id.wallpaperScrim] overlay.
     * Pure decision delegated to [ScrimRender]: hidden in wallpaper edit mode or
     * at a zero-rounding alpha, otherwise an opaque-black fill with the strength
     * in the alpha byte (View alpha stays 1 → no offscreen saveLayer).
     */
    private fun applyScrim() {
        val binding = _binding ?: return
        val color = ScrimRender.colorOrNull(
            alpha = viewModel.wallpaperScrimAlphaState.value,
            isEditMode = viewModel.isWallpaperEditMode.value,
        )
        if (color == null) {
            binding.wallpaperScrim.visibility = View.GONE
        } else {
            binding.wallpaperScrim.setBackgroundColor(color)
            binding.wallpaperScrim.visibility = View.VISIBLE
        }
    }

    private fun observeLayoutChanges() {
        collectOnStarted(
            flow = combine(
                viewModel.layoutScaleState,
                viewModel.verticalPaddingState,
                viewModel.isFontBoldState,
                viewModel.contentTopMarginState
            ) { scale, paddingFactor, isBold, marginScale ->

                LayoutConfig(
                    scale = scale,
                    paddingFactor = paddingFactor,
                    isBold = isBold,
                    marginScale = marginScale
                )
            },
            errorTag = "layout",
            coroutineContext = Dispatchers.Main + fragmentExceptionHandler,
        ) { config ->
            if (_binding == null) return@collectOnStarted

            // Inner try/catch removed — collectOnStarted has its own
            // outer catch; the three calls here are pure helpers
            // (math + property writes).
            recalculateLayoutCache(
                config.scale,
                config.paddingFactor,
                config.isBold,
            )
            applyTopMargin(config.marginScale)
            applyLayoutToExistingViews()
        }

        // Favorites alignment is observed separately because it does NOT
        // feed into the LayoutCalculator math (it only re-applies the
        // adapter styling). Keeping it out of the 4-arg combine above
        // avoids triggering a recalculateLayoutCache + applyTopMargin
        // pass on every alignment toggle.
        collectOnStarted(
            flow = viewModel.favoritesAlignmentState,
            errorTag = "favoritesAlignment",
            coroutineContext = Dispatchers.Main + fragmentExceptionHandler,
        ) { alignment ->
            if (_binding == null) return@collectOnStarted
            currentFavoritesAlignment = alignment
            applyLayoutToExistingViews()
        }
    }

    /**
     * Zentralisierte Berechnung der Cache-Werte.
     * Wird sowohl beim Start (onViewCreated) als auch bei Änderungen (Flow) genutzt.
     */
    private fun recalculateLayoutCache(scale: Float, paddingFactor: Float, isBold: Boolean) {
        try {
            val minSizePx = resources.getDimension(R.dimen.text_size_secondary_info)
            val maxSizePx = resources.getDimension(R.dimen.text_size_time) *
                    AppConstants.MAX_APP_TEXT_SCALE_RELATIVE_TO_TIME

            val cache = layoutCalculator.calculate(
                scale = scale,
                paddingFactor = paddingFactor,
                isBold = isBold,
                minTextSizePx = minSizePx,
                maxTextSizePx = maxSizePx
            )

            currentTextSizePx = cache.textSizePx
            currentVerticalPaddingPx = cache.verticalPaddingPx
            isCurrentFontBold = cache.isBold

        } catch (e: Resources.NotFoundException) {
            // Narrowed from Throwable: the only realistic throw site is
            // the two getDimension calls (a renamed/missing dimen under
            // ProGuard surfaces here). The fallback values are meaningful
            // UX recovery — without them the launcher would render with
            // garbage text size. Programmer errors in layoutCalculator
            // (pure float math) propagate as intended.
            TimberWrapper.silentError(e, "Error calculating layout cache")
            currentTextSizePx = AppConstants.FALLBACK_TEXT_SIZE_PX
            currentVerticalPaddingPx = AppConstants.FALLBACK_VERTICAL_PADDING_PX
            isCurrentFontBold = AppConstants.FALLBACK_FONT_BOLD
        }
    }

    // ============================================================================
    // LAYOUT & MARGIN CALCULATION (OPTIMIERT)
    // ============================================================================

    // Margin berechnen und anwenden
    private fun applyTopMargin(scale: Float) {
        if (_binding == null) return

        // Outer catch removed per Rule 11 — calculate() is pure float
        // math, the property compare + write are programmer-error-only,
        // updateDynamicSpacing() has its own internal handling. The
        // inner getDimensionPixelSize catch stays (real
        // Resources.NotFoundException under ProGuard / themed-context).
        val baseMargin = try {
            resources.getDimensionPixelSize(R.dimen.spacing_medium)
        } catch (e: Resources.NotFoundException) {
            // Narrowed from Exception (matches the sibling catch above): the only
            // realistic throw site is getDimensionPixelSize (a renamed/missing dimen
            // under ProGuard / a themed context surfaces here). No allocation in this
            // block, so no OutOfMemoryError to widen for.
            AppConstants.FALLBACK_DIMEN_PX
        }

        val calculatedUserMargin = topMarginCalculator.calculate(
            scale = scale,
            baseMarginPx = baseMargin,
            screenHeightPx = resources.displayMetrics.heightPixels,
        )

        if (currentUserPreferredMarginPx != calculatedUserMargin) {
            currentUserPreferredMarginPx = calculatedUserMargin
        }
        // Run unconditionally on initial start so the View's margin
        // matches the calculated value even if the XML default differed.
        updateDynamicSpacing()
    }

    /**
     * Computes the favorites top margin from the user's preferred content margin.
     * Call it whenever that setting changes; also invoked once when events load
     * (kept for symmetry, though the events indicator no longer affects spacing).
     */
    private fun updateDynamicSpacing() {
        if (_binding == null) return

        val favoritesContainer = binding.favoritesContainer

        // The old alarm/calendar chip row was a separate block whose height had to
        // be measured and compensated for here. Its replacement — the events
        // indicators — sits INSIDE timeContainer, in a horizontal row next to the
        // clock, so it adds no extra vertical block (the clock is taller than the
        // stacked glyphs): the favorites top margin now derives solely from the
        // user's preferred content margin.
        val newMargin = contentSpacingCalculator.calculate(
            currentUserPreferredMarginPx,
            0,
            false
        )

        val params = favoritesContainer.layoutParams as? ViewGroup.MarginLayoutParams ?: return
        if (params.topMargin != newMargin) {
            Timber.d("📏 Spacing applied: ${params.topMargin} → $newMargin")
            params.topMargin = newMargin
            favoritesContainer.layoutParams = params
        }
    }



    /**
     * Wendet die berechneten Cache-Werte auf die existierenden Views an.
     *
     * Outer catch removed per Rule 11 — body is pure View property
     * writes / safe-cast loop. Inner getDimensionPixelSize catch stays
     * (real Resources.NotFoundException under ProGuard).
     */
    private fun applyLayoutToExistingViews() {
        if (_binding == null) return
        favoritesAdapter.setStyling(
            buildFavoritesStyling(viewModel.uiColorsState.value)
        )
        applyTimeContainerAlignment()
    }

    /**
     * Mirrors the favorites alignment onto `timeContainer` so the clock,
     * date and battery TextViews shift in lockstep with the favorites
     * list. The container is wrap_content inside a match_parent vertical
     * LinearLayout, so its `LinearLayout.LayoutParams.gravity` controls
     * its horizontal placement within the parent. Same idiom as
     * `HomeFavoritesAdapter.onBindViewHolder`.
     */
    private fun applyTimeContainerAlignment() {
        val container = _binding?.timeContainer ?: return
        val lp = container.layoutParams as? LinearLayout.LayoutParams ?: return
        val newGravity = currentFavoritesAlignment.toHorizontalGravity()
        if (lp.gravity != newGravity) {
            lp.gravity = newGravity
            container.layoutParams = lp
        }
    }

    // ============================================================================
    // RENDERING - ULTRA SIMPLIFIED!
    // ============================================================================

    /**
     * Hands the favorites list and the current styling snapshot to
     * the [HomeFavoritesAdapter]. The adapter handles all item creation /
     * binding internally (see its KDoc); the host fragment is no
     * longer in the per-item construction business.
     */
    private fun renderFavorites(
        apps: List<AppInfo>,
        colors: UiColorsState
    ) {
        if (_binding == null) return
        Timber.d("Rendering ${apps.size} favorites")
        favoritesAdapter.setStyling(buildFavoritesStyling(colors))
        favoritesAdapter.submitList(apps)
    }

    private fun clearAllViews() {
        if (_binding != null && isAdded && !isDetached) {
            favoritesAdapter.submitList(emptyList())
        }
    }

    /**
     * Builds the styling snapshot for [HomeFavoritesAdapter] from the
     * fragment's currently-cached layout values and the given color
     * state. Called from [renderFavorites], [updateFavoriteButtonColors]
     * (after a theme change), and [applyLayoutToExistingViews] (after
     * a layout/scale/font change).
     */
    private fun buildFavoritesStyling(colors: UiColorsState): HomeFavoritesAdapter.Styling {
        val horizPaddingPx = try {
            resources.getDimensionPixelSize(R.dimen.touch_target_padding)
        } catch (e: Resources.NotFoundException) {
            AppConstants.FALLBACK_DIMEN_PX
        }
        return HomeFavoritesAdapter.Styling(
            textSizePx = currentTextSizePx,
            verticalPaddingPx = currentVerticalPaddingPx,
            horizPaddingPx = horizPaddingPx,
            isBold = isCurrentFontBold,
            textColor = colors.textColor,
            shadowColor = colors.shadowColor,
            outlineWidthPx = AppConstants.TEXT_OUTLINE_WIDTH_DP * resources.displayMetrics.density,
            alignment = currentFavoritesAlignment,
        )
    }

    private fun setupFavoritesRecyclerView() {
        binding.favoritesRecyclerView.apply {
            layoutManager = LinearLayoutManager(context)
            adapter = favoritesAdapter
            // Items are programmatically constructed Buttons whose width
            // changes per text — fixed-size optimization would not apply.
            setHasFixedSize(false)
            // No item animations: a restore (and any favorites reorder/add)
            // fires several submitList() rounds as the underlying combine
            // re-emits, so DefaultItemAnimator would play an add/move
            // animation per inserted row — the visible "populating one by
            // one" build-up. Matches every other RecyclerView in the app,
            // which all disable the animator. Styling rebinds already
            // suppress the cross-fade via STYLING_PAYLOAD in the adapter.
            itemAnimator = null
        }
    }

    // ============================================================================
    // COLOR UPDATES
    // ============================================================================

    private fun updateAllColors(colors: UiColorsState) {
        if (_binding == null) return

        val textColor = colors.textColor
        // Outline colour reuses the tonal shadowColor (TRANSPARENT when the user
        // disables the text-shadow setting → outline off). See TextOutline.
        val outlineColor = colors.shadowColor
        val outlineWidthPx = AppConstants.TEXT_OUTLINE_WIDTH_DP * resources.displayMetrics.density

        // Per-line try/catches removed per Rule 11 — setTextColor / setOutline
        // are pure property writes on never-null Views from the binding.
        binding.timeText.setTextColor(textColor)
        binding.timeText.setOutline(outlineWidthPx, outlineColor)

        binding.dateText.setTextColor(textColor)
        binding.dateText.setOutline(outlineWidthPx, outlineColor)

        binding.batteryText.setTextColor(textColor)
        binding.batteryText.setOutline(outlineWidthPx, outlineColor)

        // The events indicators are monochrome vector icons (alarm / calendar) in
        // OutlinedImageViews, so each gets the exact same adaptive treatment as the
        // clock/date/battery: the icon is tinted to the text colour and given the
        // tonal contrast outline (background-independent legibility). setIconColor is
        // the drawable-tint counterpart of setTextColor.
        binding.alarmIndicator.setIconColor(textColor)
        binding.alarmIndicator.setOutline(outlineWidthPx, outlineColor)
        binding.calendarIndicator.setIconColor(textColor)
        binding.calendarIndicator.setOutline(outlineWidthPx, outlineColor)
        updateFavoriteButtonColors(colors)
    }

    /**
     * Pushes the current color state through to the favorites adapter.
     * Called from `updateAllColors` whenever the theme flow emits.
     * The adapter rebinds visible items so the new color is applied.
     */
    private fun updateFavoriteButtonColors(colors: UiColorsState) {
        if (_binding == null) return
        favoritesAdapter.setStyling(buildFavoritesStyling(colors))
    }

    // ============================================================================
    // TIME-BASED EVENTS INDICATOR
    // ============================================================================

    /**
     * Toggles the subtle events indicators that replaced the old alarm/calendar
     * chip row: two monochrome icons (alarm, calendar), each shown only when an
     * upcoming event of its type exists. They are PASSIVE status symbols — not
     * clickable; the events dialog opens via the home double-tap
     * (`GestureDelegate.onDoubleTap`), the icons just signal that events exist.
     *
     * Toggled INVISIBLE (not GONE) so each keeps its reserved slot: the indicator
     * column has a constant width/height regardless of how many events exist, so the
     * clock next to it never shifts when an event appears/disappears — critical under
     * END alignment, where the right-anchored block would otherwise push the clock
     * left. `List.any { }` is a pure read and the visibility writes are pure View
     * property writes (Rule 11).
     */
    private fun updateEventsIndicator(events: List<TimeBasedEvent>) {
        if (_binding == null) return

        val hasAlarm = events.any { it.type == TimeBasedEventType.ALARM }
        val hasCalendar = events.any { it.type == TimeBasedEventType.CALENDAR }
        binding.alarmIndicator.visibility = if (hasAlarm) View.VISIBLE else View.INVISIBLE
        binding.calendarIndicator.visibility = if (hasCalendar) View.VISIBLE else View.INVISIBLE
        updateDynamicSpacing()
    }

    // ============================================================================
    // GESTURES - SIMPLIFIED ROUTING
    // ============================================================================

    /**
     * Wires the [HomeGestureLayout] callbacks. The wrapper detects all
     * six home-screen gestures (four directional swipes + double-tap
     * + long-press) anywhere on the home content via
     * `dispatchTouchEvent` and forwards each to its own per-gesture
     * callback.
     *
     * The four directional swipes plus the double-tap are gated on
     * wallpaper-edit mode via a separate observer (see [observeViewModel]
     * / [applyWallpaperEditModeToGestures]) — when the user enters
     * edit mode those callbacks are nulled so accidental swipes don't
     * leave the mode through a side gesture. The long-press callback
     * stays wired in both modes; its body branches internally to
     * either exit edit mode or open the customization-options dialog.
     *
     * Long-press priority on a favorite goes to the button itself,
     * not to this wrapper callback: the wrapper's tap detector is
     * suppressed for any DOWN that lands on an `isLongClickable`
     * descendant (the favorite button has `setOnLongClickListener`
     * set in `renderFavorites`), so the button's
     * app-context-menu wins and the customization-options dialog
     * does not double-fire.
     */
    private fun setupHomeGestures() {
        wireDirectionalGestureCallbacks()
        binding.homeGestureRoot.onLongPress = {
            if (viewModel.isWallpaperEditMode.value) {
                viewModel.onSetWallpaperEditMode(false)
            } else {
                viewModel.onLongPress()
            }
        }
    }

    /**
     * Sets the [HomeGestureLayout] callbacks that share the wallpaper-edit-mode
     * lifecycle (nulled while editing): the four directional swipes (up = app
     * drawer, down = recent apps, left/right = swipe actions) plus double-tap
     * (upcoming-events dialog). Called once during initial wiring and again each
     * time the user leaves wallpaper-edit mode (see
     * [applyWallpaperEditModeToGestures]). Long-press is wired separately in
     * [setupHomeGestures] because it must stay live in edit mode.
     */
    private fun wireDirectionalGestureCallbacks() {
        if (_binding == null) return
        val gestures = binding.homeGestureRoot
        gestures.onSwipeUp = { viewModel.onFlingUp() }
        gestures.onSwipeDown = { viewModel.onSwipeDown() }
        gestures.onSwipeLeft = { viewModel.onSwipeFromRightToLeft() }
        gestures.onSwipeRight = { viewModel.onSwipeFromLeftToRight() }
        gestures.onDoubleTap = { viewModel.onDoubleTap() }
    }

    /**
     * Toggles the wrapper's directional gesture callbacks based on
     * wallpaper-edit mode. In edit mode the swipe callbacks are nulled
     * out so the user can drag wallpaper layers around without
     * accidentally launching apps. The long-press callback is left
     * untouched — its body branches internally on `isWallpaperEditMode.value`,
     * so the same wired callback handles both "exit edit mode" and
     * "open customization-options dialog".
     */
    private fun applyWallpaperEditModeToGestures(isEditMode: Boolean) {
        if (_binding == null) return
        val gestures = binding.homeGestureRoot
        if (isEditMode) {
            gestures.onSwipeUp = null
            gestures.onSwipeDown = null
            gestures.onSwipeLeft = null
            gestures.onSwipeRight = null
            gestures.onDoubleTap = null
        } else {
            wireDirectionalGestureCallbacks()
        }
    }

    private fun setupDoubleTapActions() {
        // Three setOnClickListener registrations + per-listener
        // onDoubleClick bodies that wrap a single viewModel fire-and-forget
        // call. All six try/catch blocks (3 outer registrations + 3 inner
        // bodies) removed per Rule 11 — programmer-error swallows. Any
        // throw in onDoubleClick propagates to DoubleClickListener.onClick,
        // where the kept system-callback-boundary catch handles it.
        binding.timeText.setOnClickListener(object : DoubleClickListener() {
            override fun onDoubleClick() {
                viewModel.onTimeDoubleClick()
            }
        })

        binding.dateText.setOnClickListener(object : DoubleClickListener() {
            override fun onDoubleClick() {
                viewModel.onDateDoubleClick()
            }
        })

        binding.batteryText.setOnClickListener(object : DoubleClickListener() {
            override fun onDoubleClick() {
                viewModel.onBatteryDoubleClick()
            }
        })

        // The events indicator is a passive status symbol — deliberately NOT
        // clickable. The events dialog opens only via the home double-tap
        // (GestureDelegate.onDoubleTap); the bell just signals that upcoming
        // alarms/events exist. See updateEventsIndicator.
    }

    /**
     * View.OnClickListener-Wrapper für Double-Click-Erkennung.
     *
     * Die eigentliche Threshold-/Timing-Logik liegt in [DoubleClickDetector],
     * damit sie deterministisch unit-testbar ist (siehe DoubleClickDetectorTest).
     * Dieser Wrapper bleibt hier bestehen, da Call-Sites (setOnClickListener)
     * eine View.OnClickListener-Instanz erwarten.
     */
    abstract class DoubleClickListener(
        private val detector: DoubleClickDetector = DoubleClickDetector(),
    ) : View.OnClickListener {

        override fun onClick(v: View?) {
            // Outer catch preserved as the system-callback boundary
            // (View.OnClickListener.onClick is invoked by Android's
            // input dispatcher). Inner catch around onDoubleClick()
            // removed — throws from concrete subclasses funnel through
            // this one outer catch, same shape as the gesture-listener
            // tree in setupGestures.
            try {
                if (detector.registerClick()) {
                    onDoubleClick()
                }
            } catch (e: Throwable) {
                // Catch kept (HOME-Activity-resilience boundary, four-category frame):
                // onClick is a system input-dispatcher callback; a throw from a concrete
                // onDoubleClick() must not crash the launcher.
                // No suspension point in this block — synchronous body (AUDIT-12 whitelist review).
                TimberWrapper.silentError(e, "Error in onClick")
            }
        }

        abstract fun onDoubleClick()
    }

    // ============================================================================
    // BACK PRESS HANDLER
    // ============================================================================

    private fun setupBackPressHandler() {
        // try/catch removed per Rule 11. Body is a single addCallback
        // registration + a Timber.d in the callback. requireActivity()
        // throws only if Fragment is detached (programmer error in
        // onViewCreated context).
        requireActivity().onBackPressedDispatcher.addCallback(
            viewLifecycleOwner,
            object : OnBackPressedCallback(true) {
                override fun handleOnBackPressed() {
                    Timber.d("Back pressed - ignoring (we're the launcher)")
                }
            }
        )
    }

    // ============================================================================
    // CONTEXT MENU & FRAGMENT RESULT
    // ============================================================================

    private fun setupFragmentResultListener() {
        // No try/catch per Rule 11. Body is a single
        // setFragmentResultListener registration; the bundle-callback
        // body reads Bundle (cannot throw — getString returns null on
        // miss), parses a sealed result via pure Kotlin, and dispatches
        // to viewModel fire-and-forget calls plus internal helpers.
        // Outer registration runs in onViewCreated context; the
        // CancellationException-rethrow in the inner block was dead
        // code (synchronous fragment callback, no coroutine).
        childFragmentManager.setFragmentResultListener(
            AppContextMenuDialogFragment.REQUEST_KEY,
            viewLifecycleOwner
        ) { _, bundle ->
            val app = longClickedApp
            if (app == null) {
                Timber.w("Result received but longClickedApp is null")
                return@setFragmentResultListener
            }

            val action = bundle.getString(AppContextMenuDialogFragment.RESULT_KEY_ACTION)

            when (val result = ContextMenuResult.parse(action)) {
                ContextMenuResult.LaunchShortcut -> handleShortcutLaunch(
                    bundle,
                    viewModel,
                    launchShortcutUseCase
                )
                ContextMenuResult.AppInfo -> showAppInfo(app)
                ContextMenuResult.ToggleFavorite -> toggleFavorite(app)
                ContextMenuResult.HideApp -> viewModel.onHideApp(app)
                // Live branch: per the architecture rule (see
                // GetFavoriteAppsUseCase KDoc), a favorite that
                // is also hidden remains pinned to the home
                // screen and can be long-pressed there. This is
                // the path that lets the user un-hide such an
                // app — without it, the unhide action would
                // have no home-screen-side handler.
                ContextMenuResult.UnhideApp -> viewModel.onShowApp(app)
                // Only reachable from MenuContext.APP_DRAWER, the
                // dialog filters this action by context. Ignored
                // here because HOME_SCREEN never receives it in
                // practice — but the branch is required for
                // sealed-when exhaustiveness.
                ContextMenuResult.ResetUsage -> Unit
                is ContextMenuResult.Unknown ->
                    Timber.w("Unknown context menu action: ${result.action}")
            }
        }
    }

    private fun toggleFavorite(app: AppInfo) {
        viewModel.onToggleFavorite(app)
    }

    private fun showAppInfo(app: AppInfo) {
        // Narrowed Throwable→ActivityNotFoundException per Rule 11.
        // startActivity() can throw if no activity handles
        // ACTION_APPLICATION_DETAILS_SETTINGS (very rare on stock Android,
        // possible on stripped-down OEM ROMs). Anything else is programmer
        // error — let it propagate.
        val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
            data = Uri.fromParts("package", app.packageName, null)
            flags = Intent.FLAG_ACTIVITY_NEW_TASK
        }
        try {
            startActivity(intent)
        } catch (e: ActivityNotFoundException) {
            TimberWrapper.silentError(e, "Error showing app info")
            viewModel.onAppInfoError()
        }
    }

    private fun showAppContextMenu(app: AppInfo) {
        longClickedApp = app

        ContextMenuHelper.show(
            fragmentManager = childFragmentManager,
            app = app,
            menuContext = MenuContext.HOME_SCREEN,
            hasUsage = false // Oder true, falls du es hier auch lädst
        )
    }

    // ============================================================================
    // WINDOW INSETS
    // ============================================================================

    private fun setupHomeWindowInsets() {
        // No try/catch per Rule 11. Body is property reads (paddingLeft
        // etc.) and setOnApplyWindowInsetsListener registration. The
        // lambda body running later is also pure: getInsets, setPadding.
        // Programmer-error only.
        val initialRootPadding = android.graphics.Rect(
            binding.rootLayout.paddingLeft,
            binding.rootLayout.paddingTop,
            binding.rootLayout.paddingRight,
            binding.rootLayout.paddingBottom
        )

        // The status bar is hidden on home and shown only transiently by
        // swipe (BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE, see hideStatusBar()).
        // Transient bars are overlays — they must NOT consume layout space.
        // Feeding systemBars.top into the top margin/padding made the whole
        // content block (clock + favorites) jump up by the status-bar height
        // on every return to home: the first inset pass arrives while the bar
        // is still visible (top ≈ status-bar height), then hideStatusBar() in
        // onResume triggers a second pass with top = 0 after the first frame.
        // So the top inset is deliberately ignored here; the designed top
        // margin (timeContainer's spacing_xlarge) covers the top area, and a
        // transiently swiped-in status bar overlays it as intended instead of
        // reflowing the content. Left/right/bottom insets are still applied for
        // the navigation bar / gesture areas.
        ViewCompat.setOnApplyWindowInsetsListener(binding.rootLayout) { v, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(
                initialRootPadding.left + systemBars.left,
                initialRootPadding.top,
                initialRootPadding.right + systemBars.right,
                initialRootPadding.bottom + systemBars.bottom
            )
            insets
        }
    }


    // ============================================================================
    // WALLPAPER HANDLING
    // ============================================================================

    /**
     * Aktualisiert das Wallpaper basierend auf dem State.
     */
// ═════════════════════════════════════════════════════════════════════════════
// METHODE: updateWallpaper()
//
// Now a thin wrapper around WallpaperViewBinder. The reconciliation
// logic (diff, rebuild decisions, active-layer preservation) lives in
// WallpaperViewDiff, which is unit-tested in isolation — see
// WallpaperViewDiffTest for the full coverage, including the regression
// guard for the delete+add+cancel identity-mismatch bug.
// ═════════════════════════════════════════════════════════════════════════════

    private fun updateWallpaper(state: WallpaperState) {
        if (_binding == null) return
        val wallpaperView = binding.wallpaperView

        // Wallpaper removed / reset (AUDIT-20 F3): drop the cached ~10 MB composite
        // bitmap. With nothing on screen nothing queries the cache again, so the
        // entry would otherwise stay resident until a later fill or process death.
        // Covers both the user "remove wallpaper" path and a factory reset (which
        // re-emits NONE without restarting the process). There is no on-disk composite in v4.
        if (!state.hasWallpaper) {
            compositeCache.invalidate()
        }

        // Read-and-consume the one-shot focus hint (on Main, before the async
        // render): when a new layer was just added, the delegate sets this so the
        // view selects it automatically. Consuming here prevents the hint from
        // leaking into an unrelated next rebuild.
        val focusHint = viewModel.pendingFocusLayerId.value
        if (focusHint != null) {
            viewModel.consumePendingFocusLayerId()
        }

        // Staleness guard: a newer state cancels the in-flight render of the
        // previous one, so a slower decode can never land on top of a newer
        // wallpaper (latest wins). The scheduler owns that invariant. Tied to
        // viewLifecycleOwner, so onDestroyView cancels it too. No try/catch per
        // Rule 11: bind wraps its own throwy ops and the loader catches its own
        // I/O. The decode runs off the main thread inside the suspend
        // bitmapLoader; only plans that load bitmaps suspend, so a property-only
        // update stays synchronous/instant.
        wallpaperRenderScheduler.render(viewLifecycleOwner.lifecycleScope) {
            wallpaperViewBinder.bind(
                view = wallpaperView,
                target = displayTargetFor(state),
                preferredActiveLayerId = focusHint,
                onRebuildComplete = {
                    if (wallpaperView.isEditMode) {
                        wallpaperEditController?.applyLayerButtonsState()
                        wallpaperEditController?.updateLayerIndicator()
                    }
                }
            )
        }
    }

    /**
     * The state to actually render. In DISPLAY mode, if a flattened composite
     * exists (Option D §9.4), render it as a single image — one decode, one
     * texture — instead of re-decoding every layer on each drawer→home rebuild.
     * In EDIT mode (or with no composite) render the real multi-layer state so the
     * editor operates on its layers. The composite is decoded HARDWARE via the
     * normal single-image path (applySingleLayer).
     */
    private fun displayTargetFor(state: WallpaperState): WallpaperState {
        // v4 §3a: render the flattened composite as ONE texture by pointing the single-image
        // path at its synthetic composite:// cache key — but ONLY on a cache hit. A miss falls
        // through to the real multi-layer state (per-layer FullRebuild), which is correct at any
        // resolution and drives the async warm.
        val key = compositeCacheKeyIfHit(state) ?: return state
        return WallpaperState.single(key)
    }

    /**
     * The `composite://<key>` cache key for [state] IF a warmed composite is currently cached
     * for it, else null. Display-mode multi-layer only. Uses the pinned metric source (§3a:
     * `context.resources.displayMetrics`), identical to the delegate's warm-write side, so the
     * read key matches the write key exactly.
     */
    private fun compositeCacheKeyIfHit(state: WallpaperState): String? {
        if (viewModel.isWallpaperEditMode.value) return null
        if (state.layerCount < 2) return null
        val ctx = context ?: return null
        val m = ctx.resources.displayMetrics
        val key = WallpaperCompositeKey.of(state, m.widthPixels, m.heightPixels)
        return if (compositeCache.get(key) != null) key else null
    }

    private fun loadBitmapFromUri(uri: android.net.Uri): DecodedWallpaperBitmap? {
        // Catch kept per Rule 11: this is the I/O boundary for bitmap
        // loading. Real failure modes are FileNotFoundException +
        // SecurityException (revoked content-URI permission, missing
        // file) and OutOfMemoryError (large bitmap). Throwable umbrella
        // covers OOM intentionally — the caller (WallpaperViewBinder)
        // treats null as "skip this layer", which is the right user-
        // visible behavior for any of those cases.
        val key = uri.toString()
        // v4 §3a: a composite:// key is a SYNTHETIC key, not a file — resolve it from the
        // in-memory cache ONLY, never openInputStream it. The delegate's warm populates it; a
        // miss means "not warm yet" and the caller is already on the per-layer path.
        if (key.startsWith(WallpaperCompositeKey.SCHEME)) {
            return compositeCache.get(key)
        }
        // Single-layer / per-layer file:// image: reuse the cached decode across drawer->home.
        compositeCache.get(key)?.let { return it }

        return try {
            val ctx = context ?: return null
            // Bounded decode: downsample below the Canvas ~100 MB per-bitmap draw
            // limit so a huge camera photo (POCO 108 MP) can't crash the wallpaper
            // draw (#21). Pinned by an instrumented test — see BoundedBitmapDecoder.
            // Decode-to-DRAW only: caching is owned by the delegate's PROACTIVE refill
            // (WallpaperDelegate.refillCache — file:// decode for single, composite:// flatten
            // for multi, AUDIT-20 F15). This path just supplies the bitmap on a miss; it never
            // writes the cache, matching the multi-layer per-layer path. (Previously it also
            // cached + toasted single-layer fills; that double-filled once the proactive refill
            // landed, so the write moved wholesale to the delegate.)
            decodeBoundedWallpaperBitmap { ctx.contentResolver.openInputStream(uri) }
        } catch (e: Throwable) {
            // Catch kept (Expected error, four-category frame): bitmap I/O boundary —
            // FileNotFoundException / SecurityException + OOM (Throwable umbrella); the
            // caller treats null as "skip this layer".
            // No suspension point in this block — synchronous body (AUDIT-12 whitelist review).
            TimberWrapper.silentError(e, "Error loading bitmap from $uri")
            null
        }
    }


    // ============================================================================
    // LIFECYCLE
    // ============================================================================

    override fun onStart() {
        super.onStart()
        // No try/catch per Rule 11. viewModel call is fire-and-forget,
        // StateFlow.value reads cannot throw, TextView.setText accepts
        // null/CharSequence and never throws.
        viewModel.refreshTimeNow()

        // Inject the data immediately (pixel injection).
        // Bridges the milliseconds until the Flow starts emitting,
        // so the first frame the app draws itself is guaranteed correct.
        val state = viewModel.uiState.value
        binding.timeText.text = state.timeString
        binding.dateText.text = state.dateString
        binding.batteryText.text = state.batteryString
    }

    override fun onResume() {
        super.onResume()
        hideStatusBar()
    }

    override fun onPause() {
        super.onPause()
        showStatusBar()
    }

    private fun getInsetsController(): WindowInsetsControllerCompat? {
        val window = activity?.window ?: return null
        return WindowInsetsControllerCompat(window, window.decorView)
    }

    private fun hideStatusBar() {
        val controller = getInsetsController() ?: return
        controller.hide(WindowInsetsCompat.Type.statusBars())
        controller.systemBarsBehavior =
            WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
    }

    private fun showStatusBar() {
        getInsetsController()?.show(WindowInsetsCompat.Type.statusBars())
    }

    override fun onDestroyView() {
        // No try/catch per Rule 11. The body is teardown — null
        // assignments, View setListener(null), property writes — none
        // of which throw. ContextMenuHelper.dismiss is a fragment-
        // manager call but it self-protects against missing dialogs.
        // The two inner catches that wrapped setListener-null /
        // property-null sweeps were defensive Programmer-Error swallows.

        // 1. Close the dialog safely.
        ContextMenuHelper.dismiss(childFragmentManager)

        // 2. Clear our own references.
        longClickedApp = null

        // The wallpaper-edit overlay (touch interceptor + FAB cluster +
        // commands panel) now lives behind a ViewStub and may never have been
        // inflated. Its listeners are cleared by
        // WallpaperEditController.clearEditModeListeners on edit-mode exit, and
        // the controller (released just below) holds the only reference to the
        // inflated overlay binding — so no direct null-out is possible or
        // needed here.

        // Clear wallpaper callbacks.
        binding.wallpaperView.onTransformChanged = null
        binding.wallpaperView.onLayerTransformChanged = null
        binding.wallpaperView.onActiveLayerChanged = null
        binding.wallpaperView.onLayerTapped = null

        // Cancel any in-flight wallpaper render and release its handle. The
        // viewLifecycleOwner scope cancellation already stops the coroutine;
        // this drops the stale Job reference across view recreations.
        wallpaperRenderScheduler.cancel()

        // Detach the fragment-lifetime favoritesAdapter from the view being
        // destroyed. Without this, the discarded RecyclerView's data observer
        // stays registered on the long-lived adapter and leaks the RecyclerView
        // (and its themed context) on every Home<->AppDrawer round trip — the
        // same teardown every sibling RecyclerView host already does.
        binding.favoritesRecyclerView.adapter = null

        // 4. Null out the wallpaper-edit controller — before _binding is gone,
        // so the controller's binding references are still valid if it does
        // any tear-down work of its own. Currently it only holds closures on
        // the binding; this ordering is defensive, not functionally required.
        wallpaperEditController = null

        // 5. Binding nullen - Der "Golden Hammer"
        // Durchbricht den Fragment-View-Zyklus.
        _binding = null

        super.onDestroyView()
    }
}
