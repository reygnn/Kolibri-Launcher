package com.github.reygnn.kolibri_launcher.ui.home

import android.annotation.SuppressLint
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.pm.LauncherApps
import android.content.pm.ShortcutInfo
import android.content.res.ColorStateList
import android.content.res.Configuration
import android.content.res.Resources
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.text.TextUtils
import android.text.format.DateFormat
import android.util.TypedValue
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.ViewTreeObserver
import androidx.activity.OnBackPressedCallback
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
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
 * Status: Post-Brocken-A (2026-05-03). 1972 lines, 19 try/catch blocks
 * (11 Throwable). Down from 2657 / 59 / 51 across two sweeps. The file
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
 *     (renderFavorites, updateTimeBasedChips, createAppButton outer
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
        FavoritesAdapter(
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
    private var currentUserPreferredMarginPx: Int = 0
    private var lastSpacingInput: SpacingInput? = null

    // ===========================================
    // CONTEXT MENU STATE
    // ===========================================

    private var longClickedApp: AppInfo? = null

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

    private val layoutCalculator = LayoutCalculator()
    private val topMarginCalculator = TopMarginCalculator()
    private val chipBackgroundCalculator = ChipBackgroundCalculator()
    private val contentSpacingCalculator = ContentSpacingCalculator()
    private val timeFormatter = TimeEventFormatter()
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
        Timber.d("⟳ Configuration changed - orientation=${newConfig.orientation}")
        // Invalidate the spacing cache so the next layout pass recomputes
        // margin / padding for the new configuration.
        lastSpacingInput = null
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
        if (_binding == null) return
        favoritesAdapter.setStyling(
            buildFavoritesStyling(viewModel.uiColorsState.value)
        )
    }

    // ============================================================================
    // RENDERING - ULTRA SIMPLIFIED!
    // ============================================================================

    /**
     * Hands the favorites list and the current styling snapshot to
     * the [FavoritesAdapter]. The adapter handles all item creation /
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
     * Builds the styling snapshot for [FavoritesAdapter] from the
     * fragment's currently-cached layout values and the given color
     * state. Called from [renderFavorites], [updateFavoriteButtonColors]
     * (after a theme change), and [applyLayoutToExistingViews] (after
     * a layout/scale/font change).
     */
    private fun buildFavoritesStyling(colors: UiColorsState): FavoritesAdapter.Styling {
        val horizPaddingPx = try {
            resources.getDimensionPixelSize(R.dimen.touch_target_padding)
        } catch (e: Resources.NotFoundException) {
            AppConstants.FALLBACK_DIMEN_PX
        }
        return FavoritesAdapter.Styling(
            textSizePx = currentTextSizePx,
            verticalPaddingPx = currentVerticalPaddingPx,
            horizPaddingPx = horizPaddingPx,
            isBold = isCurrentFontBold,
            textColor = colors.textColor,
            shadowColor = colors.shadowColor,
        )
    }

    private fun setupFavoritesRecyclerView() {
        binding.favoritesRecyclerView.apply {
            layoutManager = LinearLayoutManager(context)
            adapter = favoritesAdapter
            // Items are programmatically constructed Buttons whose width
            // changes per text — fixed-size optimization would not apply.
            setHasFixedSize(false)
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
        updateFavoriteButtonColors(colors)
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
     * Pushes the current color state through to the favorites adapter.
     * Called from `updateAllColors` whenever the theme flow emits.
     * The adapter rebinds visible items so the new color is applied.
     */
    private fun updateFavoriteButtonColors(colors: UiColorsState) {
        if (_binding == null) return
        favoritesAdapter.setStyling(buildFavoritesStyling(colors))
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
        } catch (e: Resources.NotFoundException) {
            // Narrowed from Throwable — same tight-around-getDimensionPixelSize
            // pattern used in applyTopMargin / applyLayoutToExistingViews /
            // createAppButton above. Silent fallback to 0 means chips render
            // with extra horizontal slack, no user-visible breakage.
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
        // Inner try/catch around timeFormatter removed per Rule 11 —
        // formatAlarmTime is pure JVM Kotlin (covered by TimeEventFormatter
        // tests), DateFormat.is24HourFormat is a system-API getter that
        // does not throw. The previous "fallback to title-only on
        // formatter crash" was a programmer-error swallow that papered
        // over potential bugs while the silentError-DEBUG-throw made
        // the fallback unreachable in DEBUG anyway. Outer catch around
        // Chip(...).apply remains as per-item recovery (Chip construction
        // can throw on resource lookup or themed-context issues).
        return try {
            Chip(context).apply {
                val is24Hour = DateFormat.is24HourFormat(context)

                // PURE LOGIC DELEGATION:
                // Die Berechnung passiert jetzt isoliert und getestet im Formatter.
                // Wir übergeben nur Rohdaten.
                val timeString = timeFormatter.formatAlarmTime(
                    triggerTimeMillis = event.triggerTimeMillis,
                    is24Hour = is24Hour
                    // locale nutzen wir default vom Device, optional hier übergeben
                )

                text = "$timeString ${event.title}"

                // Visuelles Styling (existierende Methode)
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
        // Inner try/catch around timeFormatter removed per Rule 11 —
        // same reasoning as createAlarmChip above. Outer per-item
        // recovery catch retained.
        return try {
            Chip(context).apply {
                val is24Hour = DateFormat.is24HourFormat(context)

                // PURE LOGIC DELEGATION:
                val timeString = timeFormatter.formatCalendarTime(
                    triggerTimeMillis = event.triggerTimeMillis,
                    is24Hour = is24Hour
                )

                text = "$timeString ${event.title}"

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

    /**
     * Wires the [HomeGestureLayout] callbacks. The wrapper detects all
     * five home-screen gestures (four directional swipes + double-tap
     * + long-press) anywhere on the home content via
     * `dispatchTouchEvent` and forwards each to its own per-gesture
     * callback. The locking-in-progress short-circuit lives in
     * [com.github.reygnn.kolibri_launcher.ui.main.delegate.GestureDelegate]
     * — each `onFling*` and `onSwipeFrom*` method early-returns while
     * a lock animation is playing.
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
     * Sets the four swipe callbacks plus the double-tap callback on
     * [HomeGestureLayout]. Called once during initial wiring and again
     * each time the user leaves wallpaper-edit mode (see
     * [applyWallpaperEditModeToGestures]).
     */
    private fun wireDirectionalGestureCallbacks() {
        if (_binding == null) return
        val gestures = binding.homeGestureRoot
        gestures.onSwipeUp = { viewModel.onFlingUp() }
        gestures.onSwipeDown = { viewModel.onFlingDown() }
        gestures.onSwipeLeft = { viewModel.onSwipeFromRightToLeft() }
        gestures.onSwipeRight = { viewModel.onSwipeFromLeftToRight() }
        gestures.onDoubleTap = { viewModel.onDoubleTapToLock() }
    }

    /**
     * Toggles the wrapper's directional gesture callbacks based on
     * wallpaper-edit mode. In edit mode the four swipes plus
     * double-tap are nulled out so the user can drag wallpaper
     * layers around without accidentally launching apps or locking
     * the screen. The long-press callback is left untouched — its
     * body branches internally on `isWallpaperEditMode.value`, so
     * the same wired callback handles both "exit edit mode" and
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
        // etc.), a safe `as?` cast (returns null on miss), and
        // setOnApplyWindowInsetsListener registration. The lambda body
        // running later is also pure: getInsets, setPadding, property
        // writes. Programmer-error only.
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

        // No try/catch per Rule 11. wallpaperViewBinder.bind() has its
        // own internal safety wrappers around the throwy operations
        // (view.setImageURI, bitmap loading, layer mutations); the
        // bitmap loader passed in (loadBitmapFromUri) catches its own
        // I/O errors and returns null. StateFlow.value reads + the
        // edit-mode property check + controller fire-and-forget calls
        // are programmer-error only.
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
                // the controller's methods touch only View properties.
                if (wallpaperView.isEditMode) {
                    wallpaperEditController?.applyLayerButtonsState()
                    wallpaperEditController?.updateLayerIndicator()
                }
            }
        )
    }

    private fun loadBitmapFromUri(uri: android.net.Uri): android.graphics.Bitmap? {
        // Catch kept per Rule 11: this is the I/O boundary for bitmap
        // loading. Real failure modes are FileNotFoundException +
        // SecurityException (revoked content-URI permission, missing
        // file) and OutOfMemoryError (large bitmap). Throwable umbrella
        // covers OOM intentionally — the caller (WallpaperViewBinder)
        // treats null as "skip this layer", which is the right user-
        // visible behavior for any of those cases.
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
        // No try/catch per Rule 11. viewModel call is fire-and-forget,
        // StateFlow.value reads cannot throw, TextView.setText accepts
        // null/CharSequence and never throws.
        viewModel.refreshTimeNow()

        // Daten sofort schreiben (Pixel Injection)
        // Das überbrückt die Millisekunden, bis der Flow anläuft.
        // Damit ist der erste Frame, den die App selbst zeichnet, garantiert korrekt.
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

        // 1. Dialog sicher schliessen
        ContextMenuHelper.dismiss(childFragmentManager)

        // 2. Eigene Referenzen aufräumen
        longClickedApp = null
        lastSpacingInput = null

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

        // Wallpaper Callback aufräumen
        binding.wallpaperView.onTransformChanged = null
        binding.wallpaperView.onLayerTransformChanged = null
        binding.wallpaperView.onActiveLayerChanged = null
        binding.wallpaperView.onLayerTapped = null

        // 4. Wallpaper-Edit-Controller nullen — bevor _binding weg ist,
        // damit die Controller-Referenzen auf das Binding noch gültig
        // sind falls der Controller im Tear-Down noch etwas aufräumen
        // möchte. Aktuell hält er nur Closures auf das Binding;
        // Reihenfolge ist defensiv, nicht funktional erzwungen.
        wallpaperEditController = null

        // 5. Binding nullen - Der "Golden Hammer"
        // Durchbricht den Fragment-View-Zyklus.
        _binding = null

        super.onDestroyView()
    }
}