package com.github.reygnn.kolibri_launcher.ui.home

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.content.pm.LauncherApps
import android.content.pm.ShortcutInfo
import android.content.res.ColorStateList
import android.content.res.Configuration
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.text.TextUtils
import android.text.format.DateFormat
import android.util.TypedValue
import android.view.GestureDetector
import android.view.Gravity
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.ViewTreeObserver
import android.widget.Button
import android.widget.LinearLayout
import androidx.activity.OnBackPressedCallback
import androidx.core.graphics.ColorUtils
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.lifecycle.lifecycleScope
import com.github.reygnn.kolibri_launcher.R
import com.github.reygnn.kolibri_launcher.core.AppConstants
import com.github.reygnn.kolibri_launcher.core.TimberWrapper
import com.github.reygnn.kolibri_launcher.databinding.FragmentHomeBinding
import com.github.reygnn.kolibri_launcher.domain.model.AppInfo
import com.github.reygnn.kolibri_launcher.domain.model.MenuContext
import com.github.reygnn.kolibri_launcher.domain.model.TimeBasedEvent
import com.github.reygnn.kolibri_launcher.domain.model.TimeBasedEventType
import com.github.reygnn.kolibri_launcher.domain.model.UiColorsState
import com.github.reygnn.kolibri_launcher.domain.model.WallpaperState
import com.github.reygnn.kolibri_launcher.ui.home.wallpaper.WallpaperViewBinder
import com.github.reygnn.kolibri_launcher.ui.util.WallpaperImagePicker
import com.github.reygnn.kolibri_launcher.ui.appcontextmenu.AppContextMenuDialogFragment
import com.github.reygnn.kolibri_launcher.ui.appcontextmenu.ContextMenuHelper
import com.github.reygnn.kolibri_launcher.ui.appcontextmenu.ContextMenuResult
import com.github.reygnn.kolibri_launcher.ui.flow.collectOnStarted
import com.github.reygnn.kolibri_launcher.ui.extensions.handleShortcutLaunch
import com.github.reygnn.kolibri_launcher.domain.model.UiState
import com.github.reygnn.kolibri_launcher.domain.usecase.LaunchShortcutUseCase
import com.github.reygnn.kolibri_launcher.ui.main.LauncherViewModel
import com.google.android.material.chip.Chip
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import timber.log.Timber
import javax.inject.Inject

/*
 * =============================================================================
 *                    HomeFragment — Size & Refactoring Notes
 * =============================================================================
 *
 * This file is large — well over 2,000 lines. That size is a deliberate
 * trade-off, not an accident, and it is being reduced in planned stages.
 * If you are here to split it up, please read this first — the split is
 * NOT the next step.
 *
 *
 * Why the file is large
 * ---------------------
 * The home screen carries the full UI responsibility of the launcher:
 * wallpaper rendering and live edit mode (enter / commit / cancel, save
 * button, layer add / delete / up / down, snap controls, toolbar dimming,
 * touch interception), multi-mode favorites rendering with per-button
 * colors, long-press menus and scroll-width adjustment, time-based chips
 * (alarm, calendar) with contrast-aware styling, clock display, status-bar
 * and navigation-bar insets, back-press handling across edit states,
 * orientation lock coordination, and lifecycle-scoped observation of
 * every relevant ViewModel flow.
 *
 * All of it touches View, binding, or LifecycleOwner. None of it can live
 * outside a Fragment on Android. The floor for this file, given the scope
 * of what the screen actually does, is not small — but it is well above
 * the current size.
 *
 *
 * Pure-logic extractions: what's done
 * -----------------------------------
 * Decision logic most prone to silent regression has been pulled into
 * pure Kotlin classes covered by fast JVM unit tests, independent of the
 * Android framework:
 *
 *   LayerButtonsState       — layer-edit button visibility / enabled / alpha
 *   SnapIconResolver        — drawable selection for the four snap buttons
 *   DoubleClickDetector     — threshold detection with an injected clock
 *   BackupFilenameBuilder   — export filename with injected clock / locale
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
 *                             transform saver. Surfaced during walkthrough
 *                             after the original three; intentional
 *                             asymmetry between the two callers preserved
 *                             and now documented inline at the call site.
 *
 * Together they catch the classes of bugs that actually appear in
 * practice: new BackupPreview field added but the dialog forgets it; new
 * SnapMode value added but no icon mapping; off-by-one in layer button
 * enable / disable; locale-dependent date format slipping into a filename.
 *
 * Boilerplate reduction in the same direction:
 *
 *   Fragment.collectOnStarted (ui.flow) — replaced nine structurally
 *     identical repeatOnLifecycle(STARTED) blocks (eight in HomeFragment,
 *     one in AppDrawerFragment) with a single extension. Same behavior,
 *     ~100 lines smaller in HomeFragment, single point for the audit
 *     below to redesign the catch hierarchy. One Observer (split-mode
 *     threshold) was lifted from a single-catch to the resilient
 *     two-catch shape during migration; treated as fixing a copy-paste
 *     oversight, not a behavior change in spirit.
 *
 *
 * Pure-logic extractions: phase complete
 * --------------------------------------
 * The three planned pure-logic extractions — Fragment.collectOnStarted
 * (collapsed eight repeatOnLifecycle blocks), ContextMenuResult (sealed
 * interface for setupFragmentResultListener), and WallpaperEditTransition
 * (state machine for updateWallpaperEditMode) — are all done and listed
 * under "what's done" above. The next priority is the try/catch audit
 * below, then the Fragment-delegate split.
 *
 *
 * Next priority: try/catch(Throwable) audit
 * -----------------------------------------
 * With the pure-logic extractions done, the next item on the list is an
 * audit of the defensive try/catch(Throwable) pattern that wraps most
 * non-trivial calls in this file. This is roughly 30–40% of the current
 * line count — the single biggest lever, larger than the Fragment split
 * would be.
 *
 * The goal behind this pattern is correct: the launcher must not crash
 * in the user's face. A black screen on a home-screen launcher is worse
 * than on almost any other kind of app, because the user has no way to
 * navigate away. Stability matters.
 *
 * But try/catch(Throwable) + silentError is the wrong tool for that
 * goal. It doesn't achieve stability — it achieves *continuation*, which
 * is a different thing. When a render call throws and the wrapper
 * swallows it, the app keeps running, but in a half-broken state: a
 * layer didn't render, a color didn't apply, a binding is null further
 * down. The user sees a quietly broken screen and doesn't know anything
 * is wrong. Failures become invisible, and invisible failures don't
 * get fixed.
 *
 * The replacement is an escalation hierarchy matched to the failure
 * class, not a single catch(Throwable) for everything:
 *
 *   Expected errors (I/O, parse, missing package) — caught with specific
 *     exception types, surfaced to the user where they matter, otherwise
 *     logged at the appropriate level. Never silently swallowed.
 *
 *   Teardown races (fragment gone, coroutine still delivering) —
 *     prevented structurally via viewLifecycleOwner.lifecycleScope and
 *     `_binding?.let { }` checks, not masked with try/catch after the
 *     fact.
 *
 *   Programmer errors (NPE, IllegalState, IndexOutOfBounds) — these are
 *     bugs, not conditions. Crash loudly in debug, report via whatever
 *     crash-reporting channel exists in release, fix in source. Never
 *     swallow.
 *
 *   Unrecoverable failures (inflate failure → black screen, OOM on
 *     bitmap load) — controlled process termination with a brief
 *     user-facing notice, so the system restarts cleanly. This matches
 *     the direction the author already identified — for failures this
 *     severe, a controlled exit is better than continued execution in
 *     a broken state.
 *
 * Expected outcome of the audit: ~30–40% line reduction in HomeFragment,
 * a sharply smaller surface of latent bugs, and failures that are
 * actually observable when they happen. The work is design-level, not
 * mechanical — each existing wrapper needs to be classified and the
 * legitimate subset (teardown races, OOM) separated from the rest.
 *
 *
 * Fragment-delegate split: mostly cosmetic, deferred indefinitely
 * ----------------------------------------------------------------
 * After the try/catch audit lands, this file will be substantially
 * smaller and most of the bug-prone logic will already live behind
 * tests. At that point a Fragment-delegate split (mirroring the
 * ViewModel's `delegate/` siblings — WallpaperEditController,
 * FavoritesRenderer, TimeChipsRenderer) is largely cosmetic:
 *
 *   - Fragment-side delegates still depend on View, binding, and
 *     LifecycleOwner. They cannot be unit-tested with JUnit + MockK
 *     the way ViewModel delegates can — testing them needs Robolectric
 *     or instrumented tests, both of which this project deliberately
 *     avoids. A split improves readability and review surface, but
 *     does not directly improve unit-test coverage.
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
 * Priority order (operational summary)
 * ------------------------------------
 *   1. try/catch(Throwable) audit and replacement with the escalation
 *      hierarchy above. Largest single lever in the file — ~30–40%
 *      line reduction and a sharp drop in latent-bug surface.
 *      Design-level work, not mechanical.
 *
 *   2. (no second item — Fragment-delegate split is deferred per
 *      "mostly cosmetic" above. Pure-logic extractions per Rule 10
 *      remain welcome whenever a new island surfaces.)
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

    // ===========================================
    // VIEWMODEL
    // ===========================================

    private val viewModel: LauncherViewModel by activityViewModels()

    // ===========================================
    // VIEW BINDING
    // ===========================================

    private var _binding: FragmentHomeBinding? = null
    private val binding get() = _binding!!
    private val favoritesScrollView get() = binding.favoritesScrollView

    // ===========================================
    // REACTIVE STATE - SCROLL & ORIENTATION
    // ===========================================

    private val _needsSplit = MutableStateFlow(false)
    private val needsSplit: StateFlow<Boolean> = _needsSplit.asStateFlow()

    private lateinit var _orientationState: MutableStateFlow<Int>
    val orientationState: StateFlow<Int> get() = _orientationState.asStateFlow()

    // ===========================================
    // LAYOUT CACHE - COMPUTED VALUES
    // ===========================================

    private var currentTextSizePx: Float = 0f
    private var currentVerticalPaddingPx: Int = 0
    private var isCurrentFontBold: Boolean = AppConstants.DEFAULT_FONT_BOLD
    private var currentUserPreferredMarginPx: Int = 0
    private var lastSpacingInput: SpacingInput? = null

    // ===========================================
    // SPLIT MODE TRACKING
    // ===========================================

    private var wasInSplitMode = false

    // ===========================================
    // GESTURE HANDLING
    // ===========================================

    private var gestureDetector: GestureDetector? = null

    // ===========================================
    // CONTEXT MENU STATE
    // ===========================================

    private var longClickedApp: AppInfo? = null

    // ===========================================
    // COROUTINE MANAGEMENT
    // ===========================================

    private var verifyJob: Job? = null

    private val fragmentExceptionHandler = CoroutineExceptionHandler { _, throwable ->
        try {
            TimberWrapper.silentError(throwable, "Uncaught exception in HomeFragment")
        } catch (e: Throwable) {
            // Even logging can fail
        }
    }

    // ===========================================
    // HELPER / CALCULATOR CLASSES
    // ===========================================

    private val splitModeCalculator = SplitModeCalculator()
    private val scrollStateVerifier = ScrollStateVerifier()
    private val layoutCalculator = LayoutCalculator()
    private val topMarginCalculator = TopMarginCalculator()
    private val splitWeightCalculator = SplitWeightCalculator()
    private val chipBackgroundCalculator = ChipBackgroundCalculator()
    private val contentSpacingCalculator = ContentSpacingCalculator()
    private val borderDecorator = ScrollViewBorderDecorator()
    private val swipeAnalyzer = SwipeGestureAnalyzer()
    private val timeFormatter = TimeEventFormatter()
    private val orientationSynchronizer by lazy {
        OrientationSynchronizer { resources.configuration.orientation }
    }
    private val wallpaperViewBinder = WallpaperViewBinder(
        bitmapLoader = { uri -> loadBitmapFromUri(uri) }
    )

    /**
     * Owns the wallpaper-edit-mode click listeners, layer-buttons state,
     * snap controls, toolbar dim/dock, and view-transform persistence
     * during edit mode. Lifetime is tied to [_binding] — created in
     * [onViewCreated], nulled in [onDestroyView]. The controller's
     * methods don't repeat `_binding == null` guards because the
     * controller cannot outlive the binding.
     */
    private var wallpaperEditController: WallpaperEditController? = null

    private var layerPickerLauncher: androidx.activity.result.ActivityResultLauncher<String>? = null


    // ===========================================
    // LIFECYCLE
    // ===========================================

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Dieser Code greift bei der Instanziierung auf Ressourcen zu!
        _orientationState = MutableStateFlow(resources.configuration.orientation)
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
        checkAndSyncOrientation()

        recalculateLayoutCache(
            viewModel.layoutScaleState.value,
            viewModel.verticalPaddingState.value,
            viewModel.isFontBoldState.value
        )

        applyTopMargin(viewModel.contentTopMarginState.value)
        applyLayoutToExistingViews()

        hideStatusBar()
        setupBackPressHandler()
        setupGestures()
        setupDoubleTapActions()
        setupFragmentResultListener()
        setupHomeWindowInsets()

        wallpaperEditController = WallpaperEditController(
            binding = binding,
            viewModel = viewModel,
            resources = resources,
            launchLayerPicker = {
                layerPickerLauncher?.let { WallpaperImagePicker.launch(it) }
            },
            rerenderWallpaper = { updateWallpaper(viewModel.wallpaperState.value) },
        )
        wallpaperEditController?.setupInsets()

        registerLayerImagePicker()
        observeViewModel()
        observeLayoutChanges()
    }

    /**
     * Registriert den ActivityResultLauncher für die Layer-Bildauswahl.
     * Muss VOR onStart() aufgerufen werden (Fragment-Lifecycle Requirement).
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

        // No try/catch: cache reset, StateFlow update, and safePost-
        // scheduled work do not throw. checkScrollStateAfterNextLayout
        // posts via ViewTreeObserver — a setter, not a runner. Programmer
        // errors propagate to the FragmentManager, where they belong.
        Timber.d("⟳ Configuration changed - orientation=${newConfig.orientation}")

        // 1. SICHERHEIT: Cache leeren.
        // Damit garantieren wir, dass beim nächsten Layout-Pass
        // auf jeden Fall neu gerechnet und der Margin neu gesetzt wird.
        lastSpacingInput = null

        // Den Orientation-State sofort aktualisieren
        _orientationState.value = newConfig.orientation

        // Warte bis Layout wirklich fertig ist!
        checkScrollStateAfterNextLayout("Scroll state checked after rotation")

        safePost { scheduleScrollVerification() }
    }

// ============================================================================
    // REACTIVE SCROLL STATE DETECTION (POWER USER UPDATED)
    // ============================================================================

    private fun checkAndEmitScrollState() {
        // The structural teardown-race guard is the early-return below.
        // After it, every operation reads/writes a non-null view or a
        // pure StateFlow value — none can throw. The previous
        // catch(Throwable) had a "force split mode on error" fallback
        // which only helped hide invisible failures (no observable user
        // signal) — removed in favour of letting real bugs surface.
        if (_binding == null || !isAdded) return

        val scrollView = binding.favoritesScrollView
        val threshold = viewModel.splitModeThreshold.value
        val childView = scrollView.getChildAt(0)

        val shouldSplit = splitModeCalculator.shouldSplit(
            threshold = threshold,
            canScrollDown = scrollView.canScrollVertically(1),
            canScrollUp = scrollView.canScrollVertically(-1),
            contentHeight = childView?.height ?: 0,
            containerHeight = scrollView.height
        )

        // Debug Log nur bei Änderung
        if (_needsSplit.value != shouldSplit) {
            if (threshold == 0) {
                Timber.d("Scroll check (Auto): split=$shouldSplit")
            } else {
                val scrollablePixels = splitModeCalculator.calculateScrollablePixels(
                    childView?.height ?: 0,
                    scrollView.height
                )
                Timber.d("Scroll check (PowerUser): pixels=$scrollablePixels, threshold=$threshold -> split=$shouldSplit")
            }

            _needsSplit.value = shouldSplit

            // Reset scroll position wenn kein Split Mode
            if (!shouldSplit) {
                scrollView.scrollTo(0, 0)
            }
        }
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

        // Observer 2: Scroll state → Layout adjustment
        collectOnStarted(
            flow = needsSplit.combine(orientationState) { split, orientation ->
                // Ein Tupel (Pair) zurückgeben,
                // das sowohl den Split-Status als auch die Ausrichtung enthält.
                Pair(split, orientation)
            }
                // distinctUntilChanged() vergleicht nun BEIDE Werte im Pair
                .distinctUntilChanged(),
            errorTag = "scroll state",
            coroutineContext = Dispatchers.Main + fragmentExceptionHandler,
        ) { (split, orientation) -> // Destrukturierung des Pairs
            if (_binding == null) return@collectOnStarted

            Timber.d("Adjusting layout: split=$split (Orientation=$orientation)")
            val colors = viewModel.uiColorsState.value
            // adjustScrollViewWidth(split, colors) wird aufgerufen,
            // wenn sich SPLIT ändert ODER wenn sich ORIENTATION ändert.
            adjustScrollViewWidth(split, colors)
        }

        // Observer 3: Time, date, battery
        collectOnStarted(
            flow = viewModel.uiState,
            errorTag = "uiState",
            coroutineContext = Dispatchers.Main + fragmentExceptionHandler,
        ) { state ->
            if (_binding == null) return@collectOnStarted

            binding.timeText.text = state.timeString
            binding.dateText.text = state.dateString
            binding.batteryText.text = state.batteryString
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
            updateTimeBasedChips(events)
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

        // Observer 6: Split Mode Threshold Changes
        collectOnStarted(
            flow = viewModel.splitModeThreshold,
            errorTag = "threshold",
            coroutineContext = Dispatchers.Main + fragmentExceptionHandler,
        ) { threshold ->
            Timber.d("Split threshold changed to: $threshold")
            checkScrollStateAfterNextLayout("Threshold changed check")
            safePost { scheduleScrollVerification() }
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

        } catch (e: Throwable) {
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
        } catch (e: Exception) {
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
     * Berechnet den finalen Margin basierend auf User-Wunsch UND Chips-Status.
     * Sollte aufgerufen werden, wenn sich:
     * 1. Der User-Margin ändert (Settings)
     * 2. Die Chips ändern (Events geladen)
     */
    /**
     * ELEGANT FIXED: Kein unnötiges 'post', wenn wir noch gar nicht sichtbar waren.
     */
    private fun updateDynamicSpacing() {
        if (_binding == null) return

        val chipsContainer = binding.calendarEventsScroll
        val favoritesContainer = binding.favoritesContainer

        // Logik in eine lokale Funktion kapseln, damit wir sie direkt oder via post rufen können
        fun applySpacing() {
            if (_binding == null) return

            val areChipsVisible = chipsContainer.isVisible
            val currentChipsHeight = if (areChipsVisible) chipsContainer.height else 0

            val input = SpacingInput(
                userPreferredMarginPx = currentUserPreferredMarginPx,
                chipsHeightPx = currentChipsHeight,
                areChipsVisible = areChipsVisible
            )

            // Cache-Check
            if (input == lastSpacingInput) return

            val newMargin = contentSpacingCalculator.calculate(
                input.userPreferredMarginPx,
                input.chipsHeightPx,
                input.areChipsVisible
            )

            lastSpacingInput = input

            val params = favoritesContainer.layoutParams as? ViewGroup.MarginLayoutParams
            if (params != null) {
                if (params.topMargin != newMargin) {
                    Timber.d("📏 Spacing applied: ${params.topMargin} → $newMargin")
                    params.topMargin = newMargin
                    favoritesContainer.layoutParams = params

                    // Nur Layout-Checks triggern, wenn wir wirklich etwas geändert haben
                    checkScrollStateAfterNextLayout("Dynamic spacing applied: $newMargin")
                    safePost { scheduleScrollVerification() }
                }
            }
        }

        // DER CRITICAL FIX:
        // Wenn der View noch nicht "laid out" ist (z.B. beim Starten des Fragments),
        // setzen wir die Params SOFORT. Das Layout-System nutzt diese Werte dann für den allerersten Pass.
        // Kein Post = Kein Frame Delay = Kein Flackern.
        if (!favoritesContainer.isLaidOut || favoritesContainer.isInLayout) {
            applySpacing()
        } else {
            // Wenn der View schon steht und wir z.B. auf eine Höhenänderung der Chips warten müssen,
            // ist post() weiterhin sicherer, um die neuen Masse abzugreifen.
            favoritesContainer.post { applySpacing() }
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
        val horizPadding = try {
            resources.getDimensionPixelSize(R.dimen.touch_target_padding)
        } catch (e: Exception) {
            AppConstants.FALLBACK_DIMEN_PX
        }

        val targetTypeface = if (isCurrentFontBold) {
            android.graphics.Typeface.DEFAULT_BOLD
        } else {
            android.graphics.Typeface.DEFAULT
        }

        for (i in 0 until binding.appList.childCount) {
            val wrapper = binding.appList.getChildAt(i) as? LinearLayout
            val button = wrapper?.getChildAt(0) as? Button

            if (button != null) {
                button.setTextSize(TypedValue.COMPLEX_UNIT_PX, currentTextSizePx)

                button.minHeight = 0
                button.minimumHeight = 0
                button.includeFontPadding = false

                button.setPadding(horizPadding, currentVerticalPaddingPx, horizPadding, currentVerticalPaddingPx)

                button.typeface = targetTypeface
            }
        }

        checkScrollStateAfterNextLayout("Layout resized")
        safePost { scheduleScrollVerification() }
    }

    // ============================================================================
    // RENDERING - ULTRA SIMPLIFIED!
    // ============================================================================

    /**
     * SIMPLIFIED: No capacity calculation!
     * Just render, then check scroll capability
     */
    private fun renderFavorites(
        apps: List<AppInfo>,
        colors: UiColorsState
    ) {
        if (_binding == null) return
        val ctx = context ?: return

        // Outer try/catch removed per Rule 11 — body is removeAllViews +
        // a per-item createAppButton loop with its own catch + scroll-
        // state callbacks (themselves with internal handling). Per-item
        // recovery is preserved.
        Timber.d("Rendering ${apps.size} favorites")
        binding.appList.removeAllViews()

        for (app in apps) {
            try {
                val button = createAppButton(ctx, app, colors.textColor, colors.shadowColor)
                if (button != null) {
                    binding.appList.addView(button)
                }
            } catch (e: Throwable) {
                TimberWrapper.silentError(e, "Error creating button for ${app.packageName}")
            }
        }

        // Warte bis Layout wirklich fertig ist!
        checkScrollStateAfterNextLayout("Scroll state checked after rendering")
        safePost { scheduleScrollVerification() }
    }

    /**
     * Adjust ScrollView width based on split mode
     */
    private fun adjustScrollViewWidth(enableSplit: Boolean, colors: UiColorsState) {
        try {
            val scrollParams = binding.favoritesScrollView.layoutParams as LinearLayout.LayoutParams
            val gestureParams = binding.gestureZone.layoutParams as LinearLayout.LayoutParams
            val customScrollView = binding.favoritesScrollView

            val weights = splitWeightCalculator.calculate(
                enableSplit = enableSplit,
                orientation = resources.configuration.orientation,
                portraitScrollWeight = AppConstants.PORTRAIT_SPLIT_SCROLL_WEIGHT,
                portraitGestureWeight = AppConstants.PORTRAIT_SPLIT_GESTURE_WEIGHT,
                landscapeScrollWeight = AppConstants.LANDSCAPE_SPLIT_SCROLL_WEIGHT,
                landscapeGestureWeight = AppConstants.LANDSCAPE_SPLIT_GESTURE_WEIGHT
            )

            scrollParams.weight = weights.scrollViewWeight
            gestureParams.weight = weights.gestureZoneWeight

            if (enableSplit) {
                wasInSplitMode = true

                Timber.d("Split mode: ${if (resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE) "landscape" else "portrait"} (${weights.scrollViewWeight}/${weights.gestureZoneWeight})")

                binding.gestureZone.isVisible = true

                // ScrollView darf Touches abfangen (zum Scrollen)
                customScrollView.allowIntercept = true
                customScrollView.isScrollContainer = true
                customScrollView.isClickable = true
                customScrollView.isFocusable = true
                customScrollView.isFocusableInTouchMode = true

                // Touch Listener für Split Mode
                binding.gestureZone.setOnTouchListener { view, event ->
                    if (event.action == MotionEvent.ACTION_UP) {
                        view.performClick()
                    }
                    gestureDetector?.onTouchEvent(event) ?: false
                }

            } else {
                // FULL MODE
                binding.gestureZone.isVisible = false
                borderDecorator.remove(binding.favoritesScrollView)

                if (wasInSplitMode) {
                    try {
                        binding.favoritesScrollView.scrollTo(0, 0)
                        Timber.d("Scroll position reset to top (split→full)")
                    } catch (e: Throwable) {
                        TimberWrapper.silentError(e, "Error resetting scroll position")
                    }
                }
                wasInSplitMode = false

                // ScrollView MUSS das Abfangen von Touches verhindern
                customScrollView.allowIntercept = false
                customScrollView.isScrollContainer = false
                customScrollView.isClickable = false
                customScrollView.isFocusable = false
                customScrollView.isFocusableInTouchMode = false

                // Listener auf NULL setzen
                customScrollView.setOnTouchListener(null)
                binding.gestureZone.setOnTouchListener(null)

                Timber.d("Full mode: 100%% (ScrollView touch-transparent)")
            }

            binding.favoritesScrollView.layoutParams = scrollParams
            binding.gestureZone.layoutParams = gestureParams

        } catch (e: Throwable) {
            TimberWrapper.silentError(e, "Error adjusting ScrollView width")
        }
    }

    private fun clearAllViews() {
        // try/catch removed per Rule 11 — removeAllViews is a pure View
        // method and the lifecycle guards (_binding != null && isAdded
        // && !isDetached) already preclude the only realistic failure
        // mode (call after teardown).
        if (_binding != null && isAdded && !isDetached) {
            binding.appList.removeAllViews()
        }
    }

    // ============================================================================
    // COLOR UPDATES
    // ============================================================================

    private fun updateAllColors(colors: UiColorsState) {
        if (_binding == null) return

        val textColor = colors.textColor
        val shadowColor = colors.shadowColor

        // Per-line try/catches removed per Rule 11 — setTextColor /
        // setShadowLayer are pure property writes on never-null Views
        // from the binding.
        binding.timeText.setTextColor(textColor)
        binding.timeText.setShadowLayer(
            AppConstants.SHADOW_RADIUS_TIME,
            AppConstants.SHADOW_DX,
            AppConstants.SHADOW_DY,
            shadowColor,
        )

        binding.dateText.setTextColor(textColor)
        binding.dateText.setShadowLayer(
            AppConstants.SHADOW_RADIUS_DATE,
            AppConstants.SHADOW_DX_SMALL,
            AppConstants.SHADOW_DY_SMALL,
            shadowColor,
        )

        binding.batteryText.setTextColor(textColor)
        binding.batteryText.setShadowLayer(
            AppConstants.SHADOW_RADIUS_BATTERY,
            AppConstants.SHADOW_DX_SMALL,
            AppConstants.SHADOW_DY_SMALL,
            shadowColor,
        )

        updateCalendarChipsColors(colors)
        updateFavoriteButtonColors(textColor, shadowColor)

        if (_needsSplit.value) {
            borderDecorator.apply(binding.favoritesScrollView, textColor)
        }
    }

    private fun updateCalendarChipsColors(colors: UiColorsState) {
        if (_binding == null) return

        // Both inner and outer try/catch blocks removed per Rule 11 —
        // childCount + getChildAt + safe-cast + property writes are
        // pure code paths.
        for (i in 0 until binding.calendarChipsContainer.childCount) {
            val view = binding.calendarChipsContainer.getChildAt(i)
            if (view is Chip) {
                configureChipColorOnly(view, colors)
            }
        }
    }

    /**
     * Aktualisiert die Farben aller existierenden Buttons.
     * Wird aufgerufen, wenn sich das Theme ändert ODER wenn das Fragment
     * neu sichtbar wird (z.B. Rückkehr vom App Drawer), da der Flow neu emittiert.
     * WICHTIG: Muss createSubtlePressColor() nutzen, um den Klick-Effekt nicht zu zerstören!
     */
    private fun updateFavoriteButtonColors(textColor: Int, shadowColor: Int) {
        if (_binding == null) return

        // Both inner and outer try/catch blocks removed per Rule 11 —
        // childCount + getChildAt + safe-casts + property writes are
        // pure code paths.
        for (i in 0 until binding.appList.childCount) {
            // Wrapper (LinearLayout) holen, dann Button (Index 0).
            val wrapper = binding.appList.getChildAt(i) as? LinearLayout
            val button = wrapper?.getChildAt(0) as? Button

            if (button != null) {
                button.setTextColor(createSubtlePressColor(textColor))
                button.setShadowLayer(
                    AppConstants.SHADOW_RADIUS_APPS,
                    AppConstants.SHADOW_DX,
                    AppConstants.SHADOW_DY,
                    shadowColor,
                )
            }
        }
    }

    // ============================================================================
    // BUTTON CREATION
    // ============================================================================

    private fun createAppButton(
        context: Context,
        app: AppInfo,
        textColor: Int,
        shadowColor: Int
    ): View? {
        // The two outer try/catches are per-item recovery boundaries
        // (the audit's preserved pattern for per-item subscription
        // resilience): if a single button or its wrapper fails to
        // construct, the rest of the favorites list still renders.
        //
        // The inner try/catches that used to wrap pure View property
        // writes / single-method click handlers / wrapper config were
        // removed per Rule 11 — those bodies are programmer-error-only
        // paths and are covered by the outer per-item catch.

        // 1. Button-Instanz erstellen
        val button: Button = try {
            Button(context).apply {
                // --- UI Konfiguration ---
                text = app.displayName
                background = null

                // WICHTIG: Mindestgrössen entfernen, damit Padding < 48dp funktioniert
                minHeight = 0
                minimumHeight = 0
                minWidth = 0
                minimumWidth = 0

                // WICHTIG: Font Padding entfernen für exakte Abstände
                includeFontPadding = false

                setTextSize(TypedValue.COMPLEX_UNIT_PX, currentTextSizePx)

                val horizPaddingPx = try {
                    resources.getDimensionPixelSize(R.dimen.touch_target_padding)
                } catch (e: Throwable) {
                    TimberWrapper.silentError(e, "Error getting padding dimension")
                    AppConstants.FALLBACK_DIMEN_PX
                }
                setPadding(horizPaddingPx, currentVerticalPaddingPx, horizPaddingPx, currentVerticalPaddingPx)

                typeface = if (isCurrentFontBold) android.graphics.Typeface.DEFAULT_BOLD else android.graphics.Typeface.DEFAULT

                gravity = Gravity.START or Gravity.CENTER_VERTICAL

                setTextColor(createSubtlePressColor(textColor))

                maxLines = 1
                ellipsize = TextUtils.TruncateAt.END

                setShadowLayer(
                    AppConstants.SHADOW_RADIUS_APPS,
                    AppConstants.SHADOW_DX,
                    AppConstants.SHADOW_DY,
                    shadowColor,
                )

                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                )

                // --- Click Handler ---
                setOnClickListener { viewModel.onAppClicked(app) }
                setOnLongClickListener {
                    showAppContextMenu(app)
                    true
                }
            }
        } catch (e: Throwable) {
            TimberWrapper.silentError(e, "Error creating button instance for ${app.packageName}")
            return null
        }

        // 2. Wrapper AUCH absichern (per-item recovery)
        return try {
            LinearLayout(context).apply {
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                ).apply {
                    setMargins(0, 0, 0, 0)
                }

                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.START

                addView(button)
            }
        } catch (e: Throwable) {
            TimberWrapper.silentError(e, "Error creating wrapper for ${app.packageName}")
            null
        }
    }

    /**
     * Generates a ColorStateList that slightly reduces text opacity when pressed.
     * This provides immediate visual feedback to the user without the visual clutter
     * of a traditional ripple effect or background change.
     */
    private fun createSubtlePressColor(normalColor: Int): ColorStateList {
        // 255 = Komplett sichtbar
        // 180 = Leicht transparent (ca. 70%) -> Wirkt sehr hochwertig und ruhig
        val pressedColor = ColorUtils.setAlphaComponent(normalColor, AppConstants.PRESSED_STATE_ALPHA)

        return ColorStateList(
            arrayOf(
                intArrayOf(android.R.attr.state_pressed), // Wenn gedrückt...
                intArrayOf()                              // Sonst...
            ),
            intArrayOf(
                pressedColor, // ... nimm die leicht transparente Farbe
                normalColor   // ... nimm die normale Farbe
            )
        )
    }

    // ============================================================================
    // TIME-BASED CHIPS
    // ============================================================================

    private fun updateTimeBasedChips(events: List<TimeBasedEvent>) {
        if (_binding == null) return

        // Outer try/catch removed per Rule 11 — body is View property
        // writes + a per-item creation loop with its own catch. Inner
        // getDimensionPixelSize and per-item chip-creation catches stay
        // (legit Resources fallback + per-item recovery).
        binding.calendarEventsScroll.visibility = View.GONE
        binding.calendarChipsContainer.removeAllViews()

        if (events.isEmpty()) {
            updateDynamicSpacing()
            return
        }

        val ctx = context ?: return
        val colors = viewModel.uiColorsState.value

        val layoutPadding = try {
            resources.getDimensionPixelSize(R.dimen.layout_padding) * 2
        } catch (e: Throwable) {
            0
        }

        val availableWidth = resources.displayMetrics.widthPixels - layoutPadding
        val chipMaxWidth = (availableWidth * AppConstants.CHIP_MAX_WIDTH_FACTOR).toInt()

        for (event in events) {
            try {
                val chip = when (event.type) {
                    TimeBasedEventType.ALARM -> createAlarmChip(ctx, event, colors, chipMaxWidth)
                    TimeBasedEventType.CALENDAR -> createCalendarChip(ctx, event, colors, chipMaxWidth)
                }

                if (chip != null) {
                    binding.calendarChipsContainer.addView(chip)
                }
            } catch (e: Throwable) {
                TimberWrapper.silentError(e, "Error creating chip")
            }
        }

        binding.calendarEventsScroll.visibility = View.VISIBLE
        updateDynamicSpacing()

        // Warte bis Layout wirklich fertig ist!
        checkScrollStateAfterNextLayout("Scroll state checked after chips updated")

        safePost { scheduleScrollVerification() }
    }

    // Both methods below: try/catch removed per Rule 11 — body is
    // pure View property writes + a pure calculator call. Chip is
    // never null at the call sites (filtered by `is Chip` checks).

    private fun configureChip(chip: Chip, colors: UiColorsState, chipMaxWidth: Int) {
        chip.ellipsize = TextUtils.TruncateAt.END
        chip.maxWidth = chipMaxWidth
        chip.isSingleLine = true

        val finalChipBgColor = chipBackgroundCalculator.calculate(
            chipBackgroundColor = colors.chipBackgroundColor,
            textColorInt = colors.textColor,
        )
        chip.chipBackgroundColor = ColorStateList.valueOf(finalChipBgColor)

        chip.setTextColor(colors.textColor)
        chip.isCloseIconVisible = false
        chip.isCheckable = false
        chip.chipStrokeWidth = AppConstants.CHIP_STROKE_WIDTH
        chip.chipStrokeColor = ColorStateList.valueOf(colors.textColor)
        chip.setTextSize(TypedValue.COMPLEX_UNIT_SP, AppConstants.CHIP_TEXT_SIZE_SP)
        chip.chipMinHeight = chip.resources.getDimension(R.dimen.chip_min_height)
    }

    private fun configureChipColorOnly(chip: Chip, colors: UiColorsState) {
        val finalChipBgColor = chipBackgroundCalculator.calculate(
            chipBackgroundColor = colors.chipBackgroundColor,
            textColorInt = colors.textColor,
        )
        chip.chipBackgroundColor = ColorStateList.valueOf(finalChipBgColor)
        chip.setTextColor(colors.textColor)
        chip.chipStrokeColor = ColorStateList.valueOf(colors.textColor)
    }

    private fun createAlarmChip(
        context: Context,
        event: TimeBasedEvent,
        colors: UiColorsState,
        chipMaxWidth: Int
    ): Chip? {
        return try {
            Chip(context).apply {
                try {
                    // 1. Kontext-abhängige Info holen
                    val is24Hour = DateFormat.is24HourFormat(context)

                    // 2. PURE LOGIC DELEGATION:
                    // Die Berechnung passiert jetzt isoliert und getestet im Formatter.
                    // Wir übergeben nur Rohdaten.
                    val timeString = timeFormatter.formatAlarmTime(
                        triggerTimeMillis = event.triggerTimeMillis,
                        is24Hour = is24Hour
                        // locale nutzen wir default vom Device, optional hier übergeben
                    )

                    // 3. UI Zusammensetzung
                    text = "$timeString ${event.title}"

                } catch (e: Throwable) {
                    // Fallback, falls Formatierung wider Erwarten crasht
                    TimberWrapper.silentError(e, "Error formatting alarm time string")
                    text = event.title
                }

                // 4. Visuelles Styling (existierende Methode)
                configureChip(this, colors, chipMaxWidth)
            }
        } catch (e: Throwable) {
            TimberWrapper.silentError(e, "Error creating alarm chip instance")
            null
        }
    }

    private fun createCalendarChip(
        context: Context,
        event: TimeBasedEvent,
        colors: UiColorsState,
        chipMaxWidth: Int
    ): Chip? {
        return try {
            Chip(context).apply {
                try {
                    val is24Hour = DateFormat.is24HourFormat(context)

                    // PURE LOGIC DELEGATION:
                    val timeString = timeFormatter.formatCalendarTime(
                        triggerTimeMillis = event.triggerTimeMillis,
                        is24Hour = is24Hour
                    )

                    text = "$timeString ${event.title}"
                } catch (e: Throwable) {
                    TimberWrapper.silentError(e, "Error formatting calendar time string")
                    text = event.title
                }

                configureChip(this, colors, chipMaxWidth)
            }
        } catch (e: Throwable) {
            TimberWrapper.silentError(e, "Error creating calendar chip instance")
            null
        }
    }

    // ============================================================================
    // GESTURES - SIMPLIFIED ROUTING
    // ============================================================================

    @SuppressLint("ClickableViewAccessibility")
    private fun setupGestures() {
        try {
            gestureDetector = GestureDetector(requireContext(), createGestureListener())

            // Root Layout: Only active when NOT in split mode
            // Dient als Fallback-Ebene für Gesten im Full Mode.
            binding.rootLayout.setOnTouchListener { _, event ->
                try {
                    if (_needsSplit.value) {
                        return@setOnTouchListener false
                    }
                    gestureDetector?.onTouchEvent(event) ?: false
                } catch (e: Throwable) {
                    TimberWrapper.silentError(e, "Error in root touch")
                    false
                }
            }
        } catch (e: Throwable) {
            TimberWrapper.silentError(e, "Error setting up gestures")
        }
    }

    private fun createGestureListener() = object : GestureDetector.SimpleOnGestureListener() {
        override fun onDown(e: MotionEvent): Boolean = true

        /*        override fun onLongPress(e: MotionEvent) {
                    try {
                        viewModel.onLongPress()
                    } catch (ex: Throwable) {
                        TimberWrapper.silentError(ex, "Error in long press")
                    }
                }*/

        override fun onLongPress(e: MotionEvent) {
            try {
                if (viewModel.isWallpaperEditMode.value) {
                    // Edit-Mode beenden
                    viewModel.onSetWallpaperEditMode(false)
                } else {
                    viewModel.onLongPress()
                }
            } catch (ex: Throwable) {
                TimberWrapper.silentError(ex, "Error in long press")
            }
        }

        override fun onDoubleTap(e: MotionEvent): Boolean {
            return try {
                viewModel.onDoubleTapToLock()
                true
            } catch (ex: Throwable) {
                TimberWrapper.silentError(ex, "Error in double tap")
                false
            }
        }

        override fun onFling(e1: MotionEvent?, e2: MotionEvent, vX: Float, vY: Float): Boolean {
            if (e1 == null) return false

            if (viewModel.isLockingInProgress.value) {
                Timber.d("🚫 Ignoring swipe during lock animation")
                return true // Konsumiert = ignoriert
            }

            return try {
                val result = swipeAnalyzer.analyze(
                    diffX = e2.x - e1.x,
                    diffY = e2.y - e1.y,
                    velocityX = vX,
                    velocityY = vY
                )

                when (result) {
                    SwipeGestureAnalyzer.SwipeResult.TOWARDS_LEFT -> { viewModel.onSwipeFromRightToLeft(); true }
                    SwipeGestureAnalyzer.SwipeResult.TOWARDS_RIGHT -> { viewModel.onSwipeFromLeftToRight(); true }
                    SwipeGestureAnalyzer.SwipeResult.UP -> { viewModel.onFlingUp(); true }
                    SwipeGestureAnalyzer.SwipeResult.DOWN -> { viewModel.onFlingDown(); true }
                    SwipeGestureAnalyzer.SwipeResult.IGNORED -> false
                }
            } catch (e: Throwable) {
                false
            }
        }
    }

    private fun setupDoubleTapActions() {
        try {
            binding.timeText.setOnClickListener(object : DoubleClickListener() {
                override fun onDoubleClick() {
                    try {
                        viewModel.onTimeDoubleClick()
                    } catch (e: Throwable) {
                        TimberWrapper.silentError(e, "Error in time double click")
                    }
                }
            })
        } catch (e: Throwable) {
            TimberWrapper.silentError(e, "Error setting time click")
        }

        try {
            binding.dateText.setOnClickListener(object : DoubleClickListener() {
                override fun onDoubleClick() {
                    try {
                        viewModel.onDateDoubleClick()
                    } catch (e: Throwable) {
                        TimberWrapper.silentError(e, "Error in date double click")
                    }
                }
            })
        } catch (e: Throwable) {
            TimberWrapper.silentError(e, "Error setting date click")
        }

        try {
            binding.batteryText.setOnClickListener(object : DoubleClickListener() {
                override fun onDoubleClick() {
                    try {
                        viewModel.onBatteryDoubleClick()
                    } catch (e: Throwable) {
                        TimberWrapper.silentError(e, "Error in battery double click")
                    }
                }
            })
        } catch (e: Throwable) {
            TimberWrapper.silentError(e, "Error setting battery click")
        }
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
            try {
                if (detector.registerClick()) {
                    try {
                        onDoubleClick()
                    } catch (e: Throwable) {
                        TimberWrapper.silentError(e, "Error in onDoubleClick")
                    }
                }
            } catch (e: Throwable) {
                TimberWrapper.silentError(e, "Error in onClick")
            }
        }

        abstract fun onDoubleClick()
    }

    // ============================================================================
    // BACK PRESS HANDLER
    // ============================================================================

    private fun setupBackPressHandler() {
        try {
            requireActivity().onBackPressedDispatcher.addCallback(
                viewLifecycleOwner,
                object : OnBackPressedCallback(true) {
                    override fun handleOnBackPressed() {
                        Timber.d("Back pressed - ignoring (we're the launcher)")
                    }
                }
            )
        } catch (e: Throwable) {
            TimberWrapper.silentError(e, "Error setting up back press handler")
        }
    }

    // ============================================================================
    // CONTEXT MENU & FRAGMENT RESULT
    // ============================================================================

    private fun setupFragmentResultListener() {
        try {
            childFragmentManager.setFragmentResultListener(
                AppContextMenuDialogFragment.REQUEST_KEY,
                viewLifecycleOwner
            ) { _, bundle ->
                try {
                    val app = longClickedApp
                    if (app == null) {
                        Timber.w("Result received but longClickedApp is null")
                        return@setFragmentResultListener
                    }

                    val action = try {
                        bundle.getString(AppContextMenuDialogFragment.RESULT_KEY_ACTION)
                    } catch (e: Throwable) {
                        TimberWrapper.silentError(e, "Error getting action")
                        null
                    }

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
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Throwable) {
                    TimberWrapper.silentError(e, "Error in result listener")
                }
            }
        } catch (e: Throwable) {
            TimberWrapper.silentError(e, "Error setting up result listener")
        }
    }

    private fun toggleFavorite(app: AppInfo) {
        try {
            viewModel.onToggleFavorite(app)
        } catch (e: Throwable) {
            TimberWrapper.silentError(e, "Error toggling favorite")
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
        try {
            val initialRootPadding = android.graphics.Rect(
                binding.rootLayout.paddingLeft,
                binding.rootLayout.paddingTop,
                binding.rootLayout.paddingRight,
                binding.rootLayout.paddingBottom
            )

            val timeContainerParams =
                binding.timeContainer.layoutParams as? ViewGroup.MarginLayoutParams
            if (timeContainerParams == null) {
                TimberWrapper.silentError("TimeContainer params not MarginLayoutParams")
                ViewCompat.setOnApplyWindowInsetsListener(binding.rootLayout) { v, insets ->
                    val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
                    v.setPadding(
                        initialRootPadding.left + systemBars.left,
                        initialRootPadding.top + systemBars.top,
                        initialRootPadding.right + systemBars.right,
                        initialRootPadding.bottom + systemBars.bottom
                    )
                    insets
                }
                return
            }

            val initialTimeMarginTop = timeContainerParams.topMargin

            ViewCompat.setOnApplyWindowInsetsListener(binding.rootLayout) { v, insets ->
                val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())

                v.setPadding(
                    initialRootPadding.left + systemBars.left,
                    initialRootPadding.top,
                    initialRootPadding.right + systemBars.right,
                    initialRootPadding.bottom + systemBars.bottom
                )

                timeContainerParams.topMargin = initialTimeMarginTop + systemBars.top
                binding.timeContainer.layoutParams = timeContainerParams

                insets
            }
        } catch (e: Throwable) {
            TimberWrapper.silentError(e, "Error applying window insets")
        }
    }


    // ============================================================================
    // HELPER
    // ==========================================================================

    /**
     * Registriert einen ONE-SHOT OnGlobalLayoutListener.
     * Wird nach dem nächsten Layout-Pass automatisch entfernt.
     * Ruft dann checkAndEmitScrollState() auf.
     */
    private fun checkScrollStateAfterNextLayout(debugMessage: String = "") {
        try {
            if (_binding == null || !isAdded) return

            val listener = object : ViewTreeObserver.OnGlobalLayoutListener {
                override fun onGlobalLayout() {
                    try {
                        if (_binding == null || !isAdded) {
                            try {
                                binding?.favoritesScrollView?.viewTreeObserver?.removeOnGlobalLayoutListener(this)
                            } catch (e: Throwable) {
                                // Ignore - View könnte schon weg sein
                            }
                            return
                        }

                        binding.favoritesScrollView.viewTreeObserver.removeOnGlobalLayoutListener(this)
                        checkAndEmitScrollState()

                        if (debugMessage.isNotEmpty()) {
                            Timber.d(debugMessage)
                        }
                    } catch (e: Throwable) {
                        TimberWrapper.silentError(e, "Error in one-shot layout listener")
                    }
                }
            }

            binding.favoritesScrollView.viewTreeObserver.addOnGlobalLayoutListener(listener)
        } catch (e: Throwable) {
            TimberWrapper.silentError(e, "Error registering one-shot layout listener")
        }
    }

    private fun scheduleScrollVerification() {
        verifyJob?.cancel()
        verifyJob = viewLifecycleOwner.lifecycleScope.launch {
            delay(AppConstants.SCROLL_VERIFICATION_DELAY_MS) // debounce
            try {
                verifyAndFixScrollState()
            } catch (e: Throwable) {
                TimberWrapper.silentError(e, "Error in scheduled verification")
            }
        }
    }

    private fun safePost(action: () -> Unit) {
        try {
            if (_binding == null || !isAdded) return

            binding.favoritesScrollView.post {
                try {
                    if (_binding != null && isAdded) {
                        action()
                    }
                } catch (e: Throwable) {
                    TimberWrapper.silentError(e, "Error in safe post action")
                }
            }
        } catch (e: Throwable) {
            TimberWrapper.silentError(e, "Error in safePost")
        }
    }

    private fun verifyAndFixScrollState() {
        try {
            if (_binding == null || !isAdded) return

            val customScrollView = binding.favoritesScrollView

            val result = scrollStateVerifier.verify(
                currentSplitState = _needsSplit.value,
                allowIntercept = customScrollView.allowIntercept,
                canScrollDown = customScrollView.canScrollVertically(1),
                canScrollUp = customScrollView.canScrollVertically(-1)
            )

            when (result) {
                VerifyResult.Consistent -> {
                    // Alles gut, nichts tun
                }
                VerifyResult.FixFullMode -> {
                    Timber.w("Scroll state mismatch detected - fixing...")
                    customScrollView.allowIntercept = false
                    customScrollView.scrollTo(0, 0)
                }
                VerifyResult.FixSplitMode -> {
                    Timber.w("Split mode but intercept disabled - fixing...")
                    customScrollView.allowIntercept = true
                }
                VerifyResult.ReEvaluateNeeded -> {
                    Timber.w("Split mode active but no scroll capability - re-evaluating...")
                    checkAndEmitScrollState()
                }
            }
        } catch (e: Throwable) {
            TimberWrapper.silentError(e, "Error verifying scroll state")
        }
    }

    /**
     * Prüft, ob der gespeicherte Orientierungs-State mit der System-Wirklichkeit übereinstimmt.
     * Falls nicht, wird der State aktualisiert und die Caches geleert.
     */
    private fun checkAndSyncOrientation() {
        val result = orientationSynchronizer.check(_orientationState.value)

        when (result) {
            is SyncResult.CorrectionNeeded -> {
                Timber.d("⟳ Orientation mismatch detected: ${result.oldOrientation} -> ${result.newOrientation}. Syncing...")

                // 1. State korrigieren (löst Flow-Updates aus)
                _orientationState.value = result.newOrientation

                // 2. Caches leeren (damit Layout-Berechnungen frisch starten)
                lastSpacingInput = null
            }

            is SyncResult.UpToDate -> {
                // Optional: Verbose log
                // Timber.v("Orientation check passed. State is consistent.")
            }
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

        try {
            val wallpaperView = binding.wallpaperView

            // Read-and-consume the one-shot focus hint: when a new layer
            // was just added, the delegate sets this so the view selects
            // the new layer automatically. Consuming here prevents the
            // hint from leaking into an unrelated next rebuild.
            val focusHint = viewModel.pendingFocusLayerId.value
            if (focusHint != null) {
                viewModel.consumePendingFocusLayerId()
            }

            wallpaperViewBinder.bind(
                view = wallpaperView,
                target = state,
                preferredActiveLayerId = focusHint,
                onRebuildComplete = {
                    // Refresh the layer-toolbar after a rebuild while in
                    // edit mode. Inner try/catch removed per Rule 11 —
                    // the controller's methods touch only View
                    // properties, and the outer catch in updateWallpaper
                    // covers any escape.
                    if (wallpaperView.isEditMode) {
                        wallpaperEditController?.applyLayerButtonsState()
                        wallpaperEditController?.updateLayerIndicator()
                    }
                }
            )
        } catch (e: Throwable) {
            TimberWrapper.silentError(e, "Error in updateWallpaper")
        }
    }

    private fun loadBitmapFromUri(uri: android.net.Uri): android.graphics.Bitmap? {
        return try {
            val ctx = context ?: return null
            ctx.contentResolver.openInputStream(uri)?.use { inputStream ->
                android.graphics.BitmapFactory.decodeStream(inputStream)
            }
        } catch (e: Throwable) {
            TimberWrapper.silentError(e, "Error loading bitmap from $uri")
            null
        }
    }


    // ============================================================================
    // LIFECYCLE
    // ============================================================================

    override fun onStart() {
        super.onStart()
        try {
            // 1. UPDATE: Daten sofort aktualisieren
            viewModel.refreshTimeNow()

            // 2. Daten sofort schreiben (Pixel Injection)
            // Das überbrückt die Millisekunden, bis der Flow anläuft.
            // Damit ist der erste Frame, den die App selbst zeichnet, garantiert korrekt.
            val state = viewModel.uiState.value
            binding.timeText.text = state.timeString
            binding.dateText.text = state.dateString
            binding.batteryText.text = state.batteryString
        } catch (e: Throwable) {
            TimberWrapper.silentError(e, "Error in onStart")
        }
    }

    override fun onResume() {
        super.onResume()
        try {
            checkAndSyncOrientation()
            hideStatusBar()
            verifyAndFixScrollState()
        } catch (e: Throwable) {
            TimberWrapper.silentError(e, "Error in onResume")
        }
    }

    override fun onPause() {
        super.onPause()
        try {
            showStatusBar()
        } catch (e: Throwable) {
            TimberWrapper.silentError(e, "Error in onPause")
        }
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
        try {
            // 1. Laufende Jobs stoppen (WICHTIG!)
            // Verhindert Abstürze durch nachträgliche UI-Updates
            verifyJob?.cancel()
            verifyJob = null

            // 2. Dialog sicher schliessen
            ContextMenuHelper.dismiss(childFragmentManager)

            // 3. Eigene Referenzen aufräumen
            // Das ist wichtig, weil 'gestureDetector' eine Variable in der HomeFragment Klasse ist
            gestureDetector = null
            longClickedApp = null
            lastSpacingInput = null
            borderDecorator.clear()

            try {
                binding.wallpaperTouchInterceptor.setOnTouchListener(null)
                binding.btnWallpaperSave.setOnClickListener(null)
                binding.btnWallpaperCancel.setOnClickListener(null)
                binding.btnWallpaperSnap.setOnClickListener(null)
                binding.btnWallpaperSnapMode.setOnClickListener(null)
                binding.btnWallpaperHSnap.setOnClickListener(null)
                binding.btnWallpaperVSnap.setOnClickListener(null)
                binding.btnWallpaperOneToOne.setOnClickListener(null)
                binding.btnWallpaperFitWidth.setOnClickListener(null)
                binding.btnLayerAdd.setOnClickListener(null)
                binding.btnLayerDelete.setOnClickListener(null)
                binding.btnLayerUp.setOnClickListener(null)
                binding.btnLayerDown.setOnClickListener(null)
                binding.btnToolbarDock.setOnClickListener(null)
            } catch (e: Throwable) {
                // Ignore
            }

            // Wallpaper Callback aufräumen
            try {
                binding.wallpaperView.onTransformChanged = null
                binding.wallpaperView.onLayerTransformChanged = null
                binding.wallpaperView.onActiveLayerChanged = null
                binding.wallpaperView.onLayerTapped = null
            } catch (e: Throwable) {
                // Ignore
            }

            // 4. Wallpaper-Edit-Controller nullen — bevor _binding weg ist,
            // damit die Controller-Referenzen auf das Binding noch gültig
            // sind falls der Controller im Tear-Down noch etwas aufräumen
            // möchte. Aktuell hält er nur Closures auf das Binding;
            // Reihenfolge ist defensiv, nicht funktional erzwungen.
            wallpaperEditController = null

            // 5. Binding nullen - Der "Golden Hammer"
            // Durchbricht den Fragment-View-Zyklus.
            _binding = null

        } catch (e: Throwable) {
            TimberWrapper.silentError(e, "Error in onDestroyView")
        } finally {
            super.onDestroyView()
        }
    }
}