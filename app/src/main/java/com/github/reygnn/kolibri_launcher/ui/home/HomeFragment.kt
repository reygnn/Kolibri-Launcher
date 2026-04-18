package com.github.reygnn.kolibri_launcher.ui.home

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.content.pm.LauncherApps
import android.content.pm.ShortcutInfo
import android.content.res.ColorStateList
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
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
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
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
import com.github.reygnn.kolibri_launcher.ui.appcontextmenu.AppContextMenuAction
import com.github.reygnn.kolibri_launcher.ui.appcontextmenu.AppContextMenuDialogFragment
import com.github.reygnn.kolibri_launcher.ui.appcontextmenu.ContextMenuHelper
import com.github.reygnn.kolibri_launcher.ui.extensions.handleShortcutLaunch
import com.github.reygnn.kolibri_launcher.ui.base.UiState
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
    // UI CACHE - DRAWABLES
    // ===========================================

    /** Reused to avoid allocations on every border update */
    private var cachedBorderDrawable: GradientDrawable? = null

    // ===========================================
    // SPLIT MODE TRACKING
    // ===========================================

    private var wasInSplitMode = false
    private val showBorder = false  // TODO: Feature flag for future border implementation

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
    private val swipeAnalyzer = SwipeGestureAnalyzer()
    private val timeFormatter = TimeEventFormatter()
    private val orientationSynchronizer by lazy {
        OrientationSynchronizer { resources.configuration.orientation }
    }
    private var isToolbarDockedTop = false

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

        try {
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
            setupWallpaperEditButtonsInsets()

            registerLayerImagePicker()
            observeViewModel()
            observeLayoutChanges()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            TimberWrapper.silentError(e, "Error in onViewCreated")
        }
    }

    /**
     * Registriert den ActivityResultLauncher für die Layer-Bildauswahl.
     * Muss VOR onStart() aufgerufen werden (Fragment-Lifecycle Requirement).
     */
    private fun registerLayerImagePicker() {
        layerPickerLauncher = registerForActivityResult(
            androidx.activity.result.contract.ActivityResultContracts.GetContent()
        ) { uri ->
            if (uri != null) {
                try {
                    // Best-effort: Persistable Permission holen (funktioniert nicht immer bei GetContent)
                    try {
                        requireContext().contentResolver.takePersistableUriPermission(
                            uri, android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION
                        )
                    } catch (e: SecurityException) {
                        // Nicht kritisch – Bild wird im ViewModel in internen Speicher kopiert
                        TimberWrapper.silentError(e, "Could not persist URI permission for layer")
                    }
                    viewModel.onAddWallpaperLayer(uri)
                    Timber.d("Layer added from picker: $uri")
                } catch (e: Throwable) {
                    TimberWrapper.silentError(e, "Error adding layer from picker")
                }
            }
        }
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)

        try {
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
        } catch (e: Throwable) {
            TimberWrapper.silentError(e, "Error in onConfigurationChanged")
        }
    }

// ============================================================================
    // REACTIVE SCROLL STATE DETECTION (POWER USER UPDATED)
    // ============================================================================

    private fun checkAndEmitScrollState() {
        try {
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

        } catch (e: Throwable) {
            TimberWrapper.silentError(e, "Error checking scroll state")
            // Fail-Safe: Im Zweifel split mode aktivieren (sicherer)
            if (!_needsSplit.value) {
                Timber.w("Error checking scroll - enabling split as safety fallback")
                _needsSplit.value = true
            }
        }
    }

    // ============================================================================
    // OBSERVERS - PURE REACTIVE!
    // ============================================================================

    private fun observeViewModel() {
        // Observer 1: Favorites
        viewLifecycleOwner.lifecycleScope.launch(Dispatchers.Main + fragmentExceptionHandler) {
            try {
                repeatOnLifecycle(Lifecycle.State.STARTED) {
                    try {
                        viewModel.favoriteAppsState.collect { favState ->
                            if (_binding == null) return@collect

                            Timber.d("Favorites state: ${favState::class.simpleName}")

                            try {
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
                            } catch (e: CancellationException) {
                                throw e
                            } catch (e: Throwable) {
                                TimberWrapper.silentError(e, "Error handling favorites state")
                            }
                        }
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Throwable) {
                        TimberWrapper.silentError(e, "Error collecting favorites")
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                TimberWrapper.silentError(e, "Error in repeatOnLifecycle for favorites")
            }
        }

        // Observer 2: Scroll state → Layout adjustment
        viewLifecycleOwner.lifecycleScope.launch(Dispatchers.Main + fragmentExceptionHandler) {
            try {
                repeatOnLifecycle(Lifecycle.State.STARTED) {
                    try {
                        needsSplit.combine(orientationState) { split, orientation ->
                            // Ein Tupel (Pair) zurückgeben,
                            // das sowohl den Split-Status als auch die Ausrichtung enthält.
                            Pair(split, orientation)
                        }
                            // distinctUntilChanged() vergleicht nun BEIDE Werte im Pair
                            .distinctUntilChanged()
                            .collect { (split, orientation) -> // Destrukturierung des Pairs
                                if (_binding == null) return@collect

                                try {
                                    Timber.d("Adjusting layout: split=$split (Orientation=$orientation)")
                                    val colors = viewModel.uiColorsState.value

                                    // adjustScrollViewWidth(split, colors) wird aufgerufen,
                                    // wenn sich SPLIT ändert ODER wenn sich ORIENTATION ändert.
                                    adjustScrollViewWidth(split, colors)
                                } catch (e: Throwable) {
                                    TimberWrapper.silentError(e, "Error adjusting layout")
                                }
                            }
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Throwable) {
                        TimberWrapper.silentError(e, "Error collecting scroll state")
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                TimberWrapper.silentError(e, "Error in repeatOnLifecycle for scroll state")
            }
        }

        // Observer 3: Time, date, battery
        viewLifecycleOwner.lifecycleScope.launch(Dispatchers.Main + fragmentExceptionHandler) {
            try {
                repeatOnLifecycle(Lifecycle.State.STARTED) {
                    try {
                        viewModel.uiState.collect { state ->
                            if (_binding == null) return@collect

                            try {
                                binding.timeText.text = state.timeString
                            } catch (e: Throwable) {
                                TimberWrapper.silentError(e, "Error updating time")
                            }

                            try {
                                binding.dateText.text = state.dateString
                            } catch (e: Throwable) {
                                TimberWrapper.silentError(e, "Error updating date")
                            }

                            try {
                                binding.batteryText.text = state.batteryString
                            } catch (e: Throwable) {
                                TimberWrapper.silentError(e, "Error updating battery")
                            }
                        }
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Throwable) {
                        TimberWrapper.silentError(e, "Error collecting uiState")
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                TimberWrapper.silentError(e, "Error in repeatOnLifecycle for UI state")
            }
        }

        // Observer 4: TimeBasedEvents
        viewLifecycleOwner.lifecycleScope.launch(Dispatchers.Main + fragmentExceptionHandler) {
            try {
                repeatOnLifecycle(Lifecycle.State.STARTED) {
                    try {
                        viewModel.uiState
                            .map { it.timeBasedEvents }
                            .distinctUntilChanged()
                            .collect { events ->
                                if (_binding == null) return@collect

                                try {
                                    updateTimeBasedChips(events)
                                } catch (e: Throwable) {
                                    TimberWrapper.silentError(e, "Error updating chips")
                                }
                            }
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Throwable) {
                        TimberWrapper.silentError(e, "Error collecting events")
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                TimberWrapper.silentError(e, "Error in repeatOnLifecycle for events")
            }
        }

        // Observer 5: Colors
        viewLifecycleOwner.lifecycleScope.launch(Dispatchers.Main + fragmentExceptionHandler) {
            try {
                repeatOnLifecycle(Lifecycle.State.STARTED) {
                    try {
                        viewModel.uiColorsState.collect { colors ->
                            if (_binding == null) return@collect

                            try {
                                updateAllColors(colors)
                            } catch (e: Throwable) {
                                TimberWrapper.silentError(e, "Error updating colors")
                            }
                        }
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Throwable) {
                        TimberWrapper.silentError(e, "Error collecting colors")
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                TimberWrapper.silentError(e, "Error in repeatOnLifecycle for colors")
            }
        }

        // Observer 6: Split Mode Threshold Changes
        viewLifecycleOwner.lifecycleScope.launch(Dispatchers.Main + fragmentExceptionHandler) {
            try {
                repeatOnLifecycle(Lifecycle.State.STARTED) {
                    viewModel.splitModeThreshold.collect { threshold ->
                        Timber.d("Split threshold changed to: $threshold")

                        checkScrollStateAfterNextLayout("Threshold changed check")
                        safePost { scheduleScrollVerification() }
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                TimberWrapper.silentError(e, "Error observing threshold")
            }
        }

        // Observer 7: Wallpaper State
        viewLifecycleOwner.lifecycleScope.launch(Dispatchers.Main + fragmentExceptionHandler) {
            try {
                repeatOnLifecycle(Lifecycle.State.STARTED) {
                    try {
                        viewModel.wallpaperState.collect { wallpaperState ->
                            if (_binding == null) return@collect

                            try {
                                updateWallpaper(wallpaperState)
                            } catch (e: Throwable) {
                                TimberWrapper.silentError(e, "Error updating wallpaper")
                            }
                        }
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Throwable) {
                        TimberWrapper.silentError(e, "Error collecting wallpaper state")
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                TimberWrapper.silentError(e, "Error in repeatOnLifecycle for wallpaper")
            }
        }

        // Observer 8: Wallpaper Edit Mode
        viewLifecycleOwner.lifecycleScope.launch(Dispatchers.Main + fragmentExceptionHandler) {
            try {
                repeatOnLifecycle(Lifecycle.State.STARTED) {
                    try {
                        viewModel.isWallpaperEditMode.collect { isEditMode ->
                            if (_binding == null) return@collect

                            try {
                                updateWallpaperEditMode(isEditMode)
                            } catch (e: Throwable) {
                                TimberWrapper.silentError(e, "Error updating wallpaper edit mode")
                            }
                        }
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Throwable) {
                        TimberWrapper.silentError(e, "Error collecting wallpaper edit mode")
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                TimberWrapper.silentError(e, "Error in repeatOnLifecycle for wallpaper edit mode")
            }
        }

    }

    private fun observeLayoutChanges() {
        viewLifecycleOwner.lifecycleScope.launch(Dispatchers.Main + fragmentExceptionHandler) {
            try {
                repeatOnLifecycle(Lifecycle.State.STARTED) {
                    try {
                        // Wir kombinieren 4 Flows zu einem LayoutConfig Objekt
                        combine(
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

                        }.collect { config ->
                            if (_binding == null) return@collect

                            try {
                                // 1. Cache für Textgrösse/Padding neu berechnen
                                recalculateLayoutCache(
                                    config.scale,
                                    config.paddingFactor,
                                    config.isBold
                                )

                                // 2. Den neuen Abstand (Margin) anwenden
                                applyTopMargin(config.marginScale)

                                // 3. Alle existierenden Buttons aktualisieren (Textgrösse etc.)
                                applyLayoutToExistingViews()

                            } catch (e: CancellationException) {
                                throw e
                            } catch (e: Throwable) {
                                TimberWrapper.silentError(e, "Error applying layout config")
                            }
                        }
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Throwable) {
                        TimberWrapper.silentError(e, "Error collecting layout changes")
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                TimberWrapper.silentError(e, "Error in repeatOnLifecycle for layout")
            }
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
        try {
            if (_binding == null) return

            val baseMargin = try {
                resources.getDimensionPixelSize(R.dimen.spacing_medium)
            } catch (e: Exception) { AppConstants.FALLBACK_DIMEN_PX }

            val calculatedUserMargin = topMarginCalculator.calculate(
                scale = scale,
                baseMarginPx = baseMargin,
                screenHeightPx = resources.displayMetrics.heightPixels
            )

            if (currentUserPreferredMarginPx != calculatedUserMargin) {
                currentUserPreferredMarginPx = calculatedUserMargin
                updateDynamicSpacing()
            } else {
                // Fallback: Auch wenn sich der User-Wert nicht geändert hat,
                // wollen wir beim Initial-Start sicherstellen, dass updateDynamicSpacing einmal läuft
                // um den Margin am View tatsächlich zu setzen (falls er im XML anders ist).
                updateDynamicSpacing()
            }

        } catch (e: Throwable) {
            TimberWrapper.silentError(e, "Error applying top margin")
        }
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
     */
    private fun applyLayoutToExistingViews() {
        try {
            val horizPadding = try {
                resources.getDimensionPixelSize(R.dimen.touch_target_padding)
            } catch (e: Exception) { AppConstants.FALLBACK_DIMEN_PX }

            val targetTypeface = if (isCurrentFontBold) android.graphics.Typeface.DEFAULT_BOLD else android.graphics.Typeface.DEFAULT

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
        } catch (e: Throwable) {
            TimberWrapper.silentError(e, "Error applying layout to views")
        }
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

        try {
            Timber.d("Rendering ${apps.size} favorites")

            // Clear and populate
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

        } catch (e: Throwable) {
            TimberWrapper.silentError(e, "Error rendering favorites")
        }
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
                binding.favoritesScrollView.background = null
                binding.favoritesScrollView.setPadding(0, 0, 0, 0)

                if (wasInSplitMode) {
                    try {
                        binding.favoritesScrollView.scrollTo(0, 0)
                        Timber.d("Scroll position reset to top (split→full)")
                    } catch (e: Throwable) {
                        TimberWrapper.silentError(e, "Error resetting scroll position")
                    }
                }
                wasInSplitMode = false

                // applyScrollViewBorder()   // TODO: Später reaktivieren – Code intakt, bitte behalten.

                // ScrollView MUSS das Abfangen von Touches verhindern
                customScrollView.allowIntercept = false
                customScrollView.isScrollContainer = false
                customScrollView.isClickable = false
                customScrollView.isFocusable = false
                customScrollView.isFocusableInTouchMode = false

                // Listener auf NULL setzen
                customScrollView.setOnTouchListener(null)
                binding.gestureZone.setOnTouchListener(null)

                // removeScrollViewBorder()   // TODO: Später reaktivieren – Code intakt, bitte behalten.

                Timber.d("Full mode: 100%% (ScrollView touch-transparent)")
            }

            binding.favoritesScrollView.layoutParams = scrollParams
            binding.gestureZone.layoutParams = gestureParams

        } catch (e: Throwable) {
            TimberWrapper.silentError(e, "Error adjusting ScrollView width")
        }
    }

    // HINWEIS FÜR ENTWICKLER UND KI-REFACTORING:
    // Dieser auskommentierte Code ist KEIN toter Code (Dead Code).
    // Er ist Teil einer geplanter Funktionalität (Future Implementation).
    // Das Löschen führt zu Mehraufwand bei der Wiederherstellung.
    // -> applyScrollViewBorder() behalten!
    /*
        private fun applyScrollViewBorder(textColor: Int) {   // DO NOT DELETE !!!
            try {
                // 1. Farbe berechnen (das ist billig, primitives int)
                val frameColor = Color.argb(
                    AppConstants.BORDER_ALPHA,
                    Color.red(textColor),
                    Color.green(textColor),
                    Color.blue(textColor)
                )

                // 2. MONK-OPTIMIERUNG: Instanz wiederverwenden!
                // Wir erstellen das Objekt nur, wenn es noch null ist.
                if (cachedBorderDrawable == null) {
                    cachedBorderDrawable = GradientDrawable().apply {
                        setColor(Color.TRANSPARENT) // Muss nur einmal gesetzt werden
                    }
                }

                // 3. Eigenschaften auf der *existierenden* Instanz aktualisieren
                cachedBorderDrawable?.apply {
                    val strokeWidth = try {
                        resources.getDimensionPixelSize(R.dimen.split_screen_border_width)
                    } catch (e: Throwable) {
                        AppConstants.FALLBACK_BORDER_WIDTH_PX
                    }
                    // Hier ändern wir nur den State des existierenden Objekts -> 0 Allocation
                    setStroke(strokeWidth, frameColor)

                    val cornerRadius = try {
                        resources.getDimension(R.dimen.split_screen_corner_radius)
                    } catch (e: Throwable) {
                        AppConstants.FALLBACK_CORNER_RADIUS_PX
                    }
                    setCornerRadius(cornerRadius)
                }

                // 4. Nur neu zuweisen, wenn es nicht schon der Hintergrund ist
                // (Vermeidet unnötige Invalidation-Calls im View)
                if (binding.favoritesScrollView.background !== cachedBorderDrawable) {
                    binding.favoritesScrollView.background = cachedBorderDrawable
                }

                val borderPadding = try {
                    resources.getDimensionPixelSize(R.dimen.split_screen_border_inset)
                } catch (e: Throwable) {
                    AppConstants.FALLBACK_DIMEN_PX
                }

                binding.favoritesScrollView.setPadding(
                    0,
                    borderPadding,
                    borderPadding,
                    borderPadding
                )

                binding.favoritesScrollView.clipToPadding =
                    true // Behalten, um den Inhalt im Rahmen zu halten

                val params = binding.favoritesScrollView.layoutParams as LinearLayout.LayoutParams

                params.setMargins(0, 0, 0, 0)

                binding.favoritesScrollView.layoutParams = params

            } catch (e: Throwable) {
                TimberWrapper.silentError(e, "Error applying border")
            }
        }

        // HINWEIS FÜR ENTWICKLER UND KI-REFACTORING:
        // Dieser auskommentierte Code ist KEIN toter Code (Dead Code).
        // Er ist Teil einer geplanter Funktionalität (Future Implementation).
        // Das Löschen führt zu Mehraufwand bei der Wiederherstellung.
        // -> removeScrollViewBorder() behalten!
        private fun removeScrollViewBorder() {
            try {
                if (_binding == null) return

                // 1. Hintergrund (Rahmen) entfernen
                binding.favoritesScrollView.background = null

                // 2. Padding zurücksetzen (falls es für den Rahmen gesetzt wurde)
                binding.favoritesScrollView.setPadding(0, 0, 0, 0)

                // 3. Negative Margins entfernen und auf 0 setzen
                val params = binding.favoritesScrollView.layoutParams as LinearLayout.LayoutParams
                params.setMargins(0, 0, 0, 0)
                binding.favoritesScrollView.layoutParams = params

            } catch (e: Throwable) {
                TimberWrapper.silentError(e, "Error removing border")
            }
        }
    */

    private fun clearAllViews() {
        try {
            if (_binding != null && isAdded && !isDetached) {
                binding.appList.removeAllViews()
            }
        } catch (e: Throwable) {
            TimberWrapper.silentError(e, "Error clearing views")
        }
    }

    // ============================================================================
    // COLOR UPDATES
    // ============================================================================

    private fun updateAllColors(colors: UiColorsState) {
        if (_binding == null) return

        val textColor = colors.textColor
        val shadowColor = colors.shadowColor

        try {
            binding.timeText.setTextColor(textColor)
            binding.timeText.setShadowLayer(
                AppConstants.SHADOW_RADIUS_TIME,
                AppConstants.SHADOW_DX,
                AppConstants.SHADOW_DY,
                shadowColor
            )
        } catch (e: Throwable) {
            TimberWrapper.silentError(e, "Error updating time color")
        }

        try {
            binding.dateText.setTextColor(textColor)
            binding.dateText.setShadowLayer(
                AppConstants.SHADOW_RADIUS_DATE,
                AppConstants.SHADOW_DX_SMALL,
                AppConstants.SHADOW_DY_SMALL,
                shadowColor
            )
        } catch (e: Throwable) {
            TimberWrapper.silentError(e, "Error updating date color")
        }

        try {
            binding.batteryText.setTextColor(textColor)
            binding.batteryText.setShadowLayer(
                AppConstants.SHADOW_RADIUS_BATTERY,
                AppConstants.SHADOW_DX_SMALL,
                AppConstants.SHADOW_DY_SMALL,
                shadowColor
            )
        } catch (e: Throwable) {
            TimberWrapper.silentError(e, "Error updating battery color")
        }

        updateCalendarChipsColors(colors)
        updateFavoriteButtonColors(textColor, shadowColor)

        /*        if (_needsSplit.value) {   // TODO: Später reaktivieren – Code intakt, bitte behalten.
                    try {
                        applyScrollViewBorder(textColor)
                    } catch (e: Throwable) {
                        TimberWrapper.silentError(e, "Error updating border color")
                    }
                }*/
    }

    private fun updateCalendarChipsColors(colors: UiColorsState) {
        if (_binding == null) return

        try {
            for (i in 0 until binding.calendarChipsContainer.childCount) {
                try {
                    val view = binding.calendarChipsContainer.getChildAt(i)
                    if (view is Chip) {
                        configureChipColorOnly(view, colors)
                    }
                } catch (e: Throwable) {
                    TimberWrapper.silentError(e, "Error updating chip $i")
                }
            }
        } catch (e: Throwable) {
            TimberWrapper.silentError(e, "Error updating chips colors")
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

        try {
            for (i in 0 until binding.appList.childCount) {
                try {
                    // Wir müssen erst den Wrapper (LinearLayout) holen
                    val wrapper = binding.appList.getChildAt(i) as? LinearLayout
                    // Dann den Button aus dem Wrapper (Index 0)
                    val button = wrapper?.getChildAt(0) as? Button

                    if (button != null) {
                        button.setTextColor(createSubtlePressColor(textColor))
                        button.setShadowLayer(
                            AppConstants.SHADOW_RADIUS_APPS,
                            AppConstants.SHADOW_DX,
                            AppConstants.SHADOW_DY,
                            shadowColor
                        )
                    }
                } catch (e: Throwable) {
                    TimberWrapper.silentError(e, "Error updating button $i")
                }
            }
        } catch (e: Throwable) {
            TimberWrapper.silentError(e, "Error updating button colors")
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
        // 1. Button-Instanz erstellen
        val button: Button = try {
            Button(context).apply {
                // --- UI Konfiguration ---
                try {
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
                        AppConstants.FALLBACK_DIMEN_PX // Fallback
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
                        shadowColor
                    )

                    layoutParams = LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT
                    )
                } catch (e: Throwable) {
                    TimberWrapper.silentError(
                        e,
                        "Error configuring button UI for ${app.packageName}"
                    )
                }

                // --- Click Handler ---
                setOnClickListener {
                    try {
                        viewModel.onAppClicked(app)
                    } catch (e: Throwable) {
                        TimberWrapper.silentError(e, "Error in click")
                    }
                }

                setOnLongClickListener {
                    try {
                        showAppContextMenu(app)
                        true
                    } catch (e: Throwable) {
                        TimberWrapper.silentError(e, "Error in long click")
                        false
                    }
                }
            }
        } catch (e: Throwable) {
            TimberWrapper.silentError(e, "Error creating button instance for ${app.packageName}")
            return null
        }

        // 2. Wrapper AUCH absichern!
        return try {
            LinearLayout(context).apply {
                try {
                    layoutParams = LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.WRAP_CONTENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT
                    ).apply {
                        setMargins(0, 0, 0, 0)
                    }

                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.START

                    /*
                                        // === DEBUG VISUALISIERUNG START ===
                                        // Erstellt einen roten Rahmen um den gesamten Wrapper (inkl. Margin!)
                                        val debugBorder = android.graphics.drawable.GradientDrawable().apply {
                                            setColor(android.graphics.Color.TRANSPARENT) // Innen durchsichtig
                                            setStroke(2, android.graphics.Color.RED) // 2px roter Rand
                                        }
                                        background = debugBorder
                                        // === DEBUG VISUALISIERUNG ENDE ===
                    */

                    addView(button)
                } catch (e: Throwable) {
                    TimberWrapper.silentError(e, "Error configuring wrapper for ${app.packageName}")
                }
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

        try {
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

        } catch (e: Throwable) {
            TimberWrapper.silentError(e, "Error updating chips")
        }
    }

    private fun configureChip(chip: Chip, colors: UiColorsState, chipMaxWidth: Int) {
        try {
            chip.ellipsize = TextUtils.TruncateAt.END
            chip.maxWidth = chipMaxWidth
            chip.isSingleLine = true

            val finalChipBgColor = chipBackgroundCalculator.calculate(
                chipBackgroundColor = colors.chipBackgroundColor,
                textColorInt = colors.textColor
            )
            chip.chipBackgroundColor = ColorStateList.valueOf(finalChipBgColor)

            chip.setTextColor(colors.textColor)
            chip.isCloseIconVisible = false
            chip.isCheckable = false
            chip.chipStrokeWidth = AppConstants.CHIP_STROKE_WIDTH
            chip.chipStrokeColor = ColorStateList.valueOf(colors.textColor)
            chip.setTextSize(TypedValue.COMPLEX_UNIT_SP, AppConstants.CHIP_TEXT_SIZE_SP)
            chip.chipMinHeight = chip.resources.getDimension(R.dimen.chip_min_height)

        } catch (e: Throwable) {
            TimberWrapper.silentError(e, "Error configuring chip")
        }
    }

    private fun configureChipColorOnly(chip: Chip, colors: UiColorsState) {
        try {
            val finalChipBgColor = chipBackgroundCalculator.calculate(
                chipBackgroundColor = colors.chipBackgroundColor,
                textColorInt = colors.textColor
            )
            chip.chipBackgroundColor = ColorStateList.valueOf(finalChipBgColor)
            chip.setTextColor(colors.textColor)
            chip.chipStrokeColor = ColorStateList.valueOf(colors.textColor)

        } catch (e: Throwable) {
            TimberWrapper.silentError(e, "Error updating chip color")
        }
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

    abstract class DoubleClickListener : View.OnClickListener {
        private var lastClickTime: Long = 0

        override fun onClick(v: View?) {
            try {
                val clickTime = System.currentTimeMillis()
                if (clickTime - lastClickTime < AppConstants.DOUBLE_CLICK_THRESHOLD) {
                    try {
                        onDoubleClick()
                    } catch (e: Throwable) {
                        TimberWrapper.silentError(e, "Error in onDoubleClick")
                    }
                }
                lastClickTime = clickTime
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

                    when (action) {
                        "launch_shortcut" -> handleShortcutLaunch(
                            bundle,
                            viewModel,
                            launchShortcutUseCase
                        )
                        AppContextMenuAction.ACTION_ID_APP_INFO -> showAppInfo(app)
                        AppContextMenuAction.ACTION_ID_TOGGLE_FAVORITE -> toggleFavorite(app)
                        AppContextMenuAction.ACTION_ID_HIDE_APP -> viewModel.onHideApp(app)
                        AppContextMenuAction.ACTION_ID_UNHIDE_APP -> viewModel.onShowApp(app)
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


    /**
     * Wendet WindowInsets auf die Wallpaper Edit-Buttons an.
     * Stellt sicher, dass die Buttons über der Navigation Bar sichtbar bleiben.
     */
    private fun setupWallpaperEditButtonsInsets() {
        try {
            ViewCompat.setOnApplyWindowInsetsListener(binding.wallpaperEditButtons) { view, insets ->
                val navBarInsets = insets.getInsets(WindowInsetsCompat.Type.navigationBars())
                val basePadding = try {
                    resources.getDimensionPixelSize(R.dimen.layout_padding)
                } catch (e: Throwable) {
                    16 // Fallback 16dp in pixels (ungefähr)
                }

                view.setPadding(
                    view.paddingLeft,
                    view.paddingTop,
                    view.paddingRight,
                    basePadding + navBarInsets.bottom
                )
                insets
            }
        } catch (e: Throwable) {
            TimberWrapper.silentError(e, "Error setting up wallpaper edit buttons insets")
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
// METHODE: updateWallpaper() – ERSETZT die bestehende
// Identisch zur vorherigen Multi-Layer Version, aber ruft am Ende
// die Layer-Toolbar-Updates auf wenn wir im Edit-Mode sind.
// ═════════════════════════════════════════════════════════════════════════════

    private fun updateWallpaper(state: WallpaperState) {
        if (_binding == null) return

        try {
            val wallpaperView = binding.wallpaperView

            if (state.isMultiLayer) {
                // ═══════════════════════════════════
                // MULTI-LAYER MODUS (Folien)
                // ═══════════════════════════════════

                if (wallpaperView.layerCount != state.layers.size || !wallpaperView.isMultiLayerMode) {
                    wallpaperView.clearLayers()

                    for ((index, layerState) in state.layers.withIndex()) {
                        if (layerState.imageUri == null) continue

                        try {
                            val bitmap = loadBitmapFromUri(layerState.imageUri) ?: continue

                            wallpaperView.addLayer(
                                bitmap = bitmap,
                                label = layerState.label,
                                centerCrop = !layerState.isTransformed,
                                alpha = layerState.alpha,
                                blendMode = layerState.blendMode,
                                sourceUri = layerState.imageUri
                            )
                        } catch (e: Throwable) {
                            TimberWrapper.silentError(e, "Error loading layer $index")
                        }
                    }

                    wallpaperView.visibility = if (wallpaperView.layerCount > 0) View.VISIBLE else View.GONE

                    // Neues Layer automatisch aktiv setzen (letztes = soeben hinzugefügtes)
                    if (wallpaperView.layerCount > 0) {
                        wallpaperView.activeLayerIndex = wallpaperView.layerCount - 1
                    }
                }

                // Transforms anwenden (nach Layout)
                wallpaperView.post {
                    try {
                        for ((index, layerState) in state.layers.withIndex()) {
                            if (index >= wallpaperView.layerCount) break

                            if (layerState.isTransformed) {
                                wallpaperView.applyTransform(
                                    index,
                                    layerState.scale,
                                    layerState.translateX,
                                    layerState.translateY
                                )
                            } else {
                                wallpaperView.centerCropLayer(index)
                            }

                            wallpaperView.getLayer(index)?.let { layer ->
                                layer.alpha = layerState.alpha
                                layer.blendMode = layerState.blendMode
                                layer.isVisible = layerState.isVisible
                            }
                        }
                        wallpaperView.invalidate()

                        // Layer-Toolbar aktualisieren wenn im Edit-Mode
                        if (wallpaperView.isEditMode) {
                            updateLayerButtonsVisibility()
                            updateLayerButtonStates()
                            updateLayerIndicator()
                        }
                    } catch (e: Throwable) {
                        TimberWrapper.silentError(e, "Error applying multi-layer transforms")
                    }
                }

            } else if (state.hasWallpaper && state.imageUri != null) {
                // ═══════════════════════════════════
                // SINGLE-LAYER MODUS (Original)
                // ═══════════════════════════════════

                if (wallpaperView.isMultiLayerMode) {
                    wallpaperView.clearLayers()
                }

                try {
                    wallpaperView.setImageURI(state.imageUri)
                    wallpaperView.visibility = View.VISIBLE

                    wallpaperView.post {
                        try {
                            if (state.isTransformed) {
                                wallpaperView.applyTransform(
                                    state.scale,
                                    state.translateX,
                                    state.translateY
                                )
                            } else {
                                wallpaperView.centerCrop()
                            }

                            // Layer-Toolbar aktualisieren (Buttons ausblenden)
                            if (wallpaperView.isEditMode) {
                                updateLayerButtonsVisibility()
                                updateLayerIndicator()
                            }
                        } catch (e: Throwable) {
                            TimberWrapper.silentError(e, "Error applying wallpaper transform")
                        }
                    }
                } catch (e: Throwable) {
                    TimberWrapper.silentError(e, "Error loading wallpaper image")
                    wallpaperView.visibility = View.GONE
                }

            } else {
                // ═══════════════════════════════════
                // KEIN WALLPAPER
                // ═══════════════════════════════════

                if (wallpaperView.isMultiLayerMode) {
                    wallpaperView.clearLayers()
                }
                wallpaperView.setImageDrawable(null)
                wallpaperView.visibility = View.GONE
            }

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

// ═════════════════════════════════════════════════════════════════════════════
// METHODE: updateWallpaperEditMode() – ERSETZT die bestehende komplett
// Enthält: Layer-Buttons, Toolbar-Dimming, Image Picker, Layer Indicator
// ═════════════════════════════════════════════════════════════════════════════

    private fun updateWallpaperEditMode(isEditMode: Boolean) {
        if (_binding == null) return

        try {
            val wallpaperView = binding.wallpaperView
            val editOverlay = binding.wallpaperEditOverlay
            val touchInterceptor = binding.wallpaperTouchInterceptor

            wallpaperView.isEditMode = isEditMode

            if (isEditMode) {
                // Snapshot (für Cancel) wird jetzt im WallpaperDelegate gehalten —
                // er wurde bereits bei onEnterWallpaperEditMode() aufgenommen.

                // Snap per Default aus im Edit-Mode
                wallpaperView.isSnapEnabled = false
                wallpaperView.isHorizontalSnapEnabled = false
                wallpaperView.isVerticalSnapEnabled = false

                editOverlay.visibility = View.VISIBLE
                binding.rootLayout.alpha = 0.7f

                // ── TOOLBAR DIMMING bei Gesten ──
                // Toolbar wird gedimmt sobald der User draggt oder zoomt,
                // und blendet wieder auf wenn losgelassen wird.
                touchInterceptor.setOnTouchListener { _, event ->
                    when (event.actionMasked) {
                        MotionEvent.ACTION_DOWN -> {
                            // Noch nicht dimmen – erst bei echtem Drag/Zoom
                        }
                        MotionEvent.ACTION_MOVE -> {
                            // Toolbar sanft ausblenden
                            dimToolbar(true)
                        }
                        MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                            // Toolbar wieder einblenden
                            dimToolbar(false)
                        }
                    }
                    wallpaperView.onTouchEvent(event)
                }

                // ── SAVE BUTTON ──
                binding.btnWallpaperSave.setOnClickListener {
                    try {
                        val currentWallpaperState = viewModel.wallpaperState.value

                        if (currentWallpaperState.isMultiLayer) {
                            val transforms = (0 until wallpaperView.layerCount).map { i ->
                                val layer = wallpaperView.getLayer(i)
                                Triple(
                                    layer?.scale ?: 1f,
                                    layer?.translateX ?: 0f,
                                    layer?.translateY ?: 0f
                                )
                            }
                            viewModel.onSaveAllLayerTransforms(transforms)
                        } else if (currentWallpaperState.hasWallpaper) {
                            viewModel.onSaveWallpaperTransform(
                                wallpaperView.currentScale,
                                wallpaperView.currentTranslateX,
                                wallpaperView.currentTranslateY
                            )
                        }

                        viewModel.onCommitWallpaperEditMode()
                    } catch (e: Throwable) {
                        TimberWrapper.silentError(e, "Error saving wallpaper")
                    }
                }

                // ── CANCEL BUTTON ──
                // Rolls back the entire edit session via the delegate:
                // - state is restored to the snapshot taken on enter
                //   (in-memory synchronously, persistence async)
                // - files of removed layers are kept; files of added
                //   layers are cleaned up
                // After rolling back the state we re-run updateWallpaper()
                // so pure transform drags (which never touched the state)
                // also get reset on the view.
                binding.btnWallpaperCancel.setOnClickListener {
                    try {
                        viewModel.onCancelWallpaperEditMode()
                        updateWallpaper(viewModel.wallpaperState.value)
                    } catch (e: Throwable) {
                        TimberWrapper.silentError(e, "Error canceling wallpaper edit")
                    }
                }

                // ── SNAP BUTTONS (unverändert) ──

                updateSnapButtonIcon(wallpaperView.isSnapEnabled)
                binding.btnWallpaperSnap.setOnClickListener {
                    try {
                        wallpaperView.isSnapEnabled = !wallpaperView.isSnapEnabled
                        updateSnapButtonIcon(wallpaperView.isSnapEnabled)
                    } catch (e: Throwable) {
                        TimberWrapper.silentError(e, "Error toggling snap")
                    }
                }

                updateSnapModeButtonIcon(wallpaperView.snapMode)
                binding.btnWallpaperSnapMode.setOnClickListener {
                    try {
                        wallpaperView.snapMode = when (wallpaperView.snapMode) {
                            ZoomableImageView.SnapMode.EDGE -> ZoomableImageView.SnapMode.CENTER
                            ZoomableImageView.SnapMode.CENTER -> ZoomableImageView.SnapMode.EDGE
                        }
                        updateSnapModeButtonIcon(wallpaperView.snapMode)
                        updateHorizontalSnapButtonIcon(wallpaperView.isHorizontalSnapEnabled, wallpaperView.snapMode)
                        updateVerticalSnapButtonIcon(wallpaperView.isVerticalSnapEnabled, wallpaperView.snapMode)
                    } catch (e: Throwable) {
                        TimberWrapper.silentError(e, "Error toggling snap mode")
                    }
                }

                updateHorizontalSnapButtonIcon(wallpaperView.isHorizontalSnapEnabled, wallpaperView.snapMode)
                binding.btnWallpaperHSnap.setOnClickListener {
                    try {
                        wallpaperView.isHorizontalSnapEnabled = !wallpaperView.isHorizontalSnapEnabled
                        updateHorizontalSnapButtonIcon(wallpaperView.isHorizontalSnapEnabled, wallpaperView.snapMode)
                    } catch (e: Throwable) {
                        TimberWrapper.silentError(e, "Error toggling horizontal snap")
                    }
                }

                updateVerticalSnapButtonIcon(wallpaperView.isVerticalSnapEnabled, wallpaperView.snapMode)
                binding.btnWallpaperVSnap.setOnClickListener {
                    try {
                        wallpaperView.isVerticalSnapEnabled = !wallpaperView.isVerticalSnapEnabled
                        updateVerticalSnapButtonIcon(wallpaperView.isVerticalSnapEnabled, wallpaperView.snapMode)
                    } catch (e: Throwable) {
                        TimberWrapper.silentError(e, "Error toggling vertical snap")
                    }
                }

                // 5. Original Size (1:1)
                binding.btnWallpaperOneToOne.setOnClickListener {
                    try {
                        wallpaperView.showOriginalSize()
                    } catch (e: Throwable) {
                        TimberWrapper.silentError(e, "Error showing original size")
                    }
                }

                // 6. Fit to Width
                binding.btnWallpaperFitWidth.setOnClickListener {
                    try {
                        wallpaperView.fitToWidth()
                    } catch (e: Throwable) {
                        TimberWrapper.silentError(e, "Error fitting to width")
                    }
                }

                // ── Dock Toggle (oben ↔ unten) ──
                binding.btnToolbarDock.setOnClickListener {
                    try {
                        isToolbarDockedTop = !isToolbarDockedTop
                        dockToolbar(isToolbarDockedTop)
                    } catch (e: Throwable) {
                        TimberWrapper.silentError(e, "Error toggling toolbar dock")
                    }
                }

                // ══════════════════════════════════════
                // LAYER MANAGEMENT BUTTONS
                // ══════════════════════════════════════

                updateLayerButtonsVisibility()
                updateLayerIndicator()

                // ── ADD LAYER (+) ──
                binding.btnLayerAdd.setOnClickListener {
                    try {
                        // Transforms sichern BEVOR neuer Layer hinzugefügt wird
                        saveCurrentViewTransforms()
                        layerPickerLauncher?.launch("image/*")
                    } catch (e: Throwable) {
                        TimberWrapper.silentError(e, "Error launching layer picker")
                    }
                }

                // ── DELETE LAYER (Trash) ──
                binding.btnLayerDelete.setOnClickListener {
                    try {
                        val activeIndex = wallpaperView.activeLayerIndex
                        if (activeIndex >= 0 && wallpaperView.layerCount > 0) {
                            // Transforms der anderen Layer sichern
                            saveCurrentViewTransforms()
                            viewModel.onRemoveWallpaperLayer(activeIndex)
                        }
                    } catch (e: Throwable) {
                        TimberWrapper.silentError(e, "Error deleting layer")
                    }
                }

                // ── LAYER UP (Z-Order hoch) ──
                binding.btnLayerUp.setOnClickListener {
                    try {
                        val activeIndex = wallpaperView.activeLayerIndex
                        if (activeIndex < wallpaperView.layerCount - 1) {
                            // Transforms sichern BEVOR getauscht wird
                            saveCurrentViewTransforms()
                            wallpaperView.moveLayerUp(activeIndex)
                            viewModel.onSwapWallpaperLayers(activeIndex, activeIndex + 1)
                            updateLayerIndicator()
                            updateLayerButtonStates()
                        }
                    } catch (e: Throwable) {
                        TimberWrapper.silentError(e, "Error moving layer up")
                    }
                }

                // ── LAYER DOWN (Z-Order runter) ──
                binding.btnLayerDown.setOnClickListener {
                    try {
                        val activeIndex = wallpaperView.activeLayerIndex
                        if (activeIndex > 0) {
                            // Transforms sichern BEVOR getauscht wird
                            saveCurrentViewTransforms()
                            wallpaperView.moveLayerDown(activeIndex)
                            viewModel.onSwapWallpaperLayers(activeIndex, activeIndex - 1)
                            updateLayerIndicator()
                            updateLayerButtonStates()
                        }
                    } catch (e: Throwable) {
                        TimberWrapper.silentError(e, "Error moving layer down")
                    }
                }

                // ── Layer-Tap Callback: Aktualisiert Indikator ──
                wallpaperView.onLayerTapped = { index, layer ->
                    updateLayerIndicator()
                    updateLayerButtonStates()
                }

                Timber.d("Wallpaper edit mode: ON (multiLayer=${viewModel.wallpaperState.value.isMultiLayer}, layers=${wallpaperView.layerCount})")

            } else {
                // ══════════════════════════════════════
                // EDIT MODE BEENDET
                // ══════════════════════════════════════

                editOverlay.visibility = View.GONE
                touchInterceptor.setOnTouchListener(null)
                binding.rootLayout.alpha = 1.0f

                // Toolbar Alpha zurücksetzen
                binding.wallpaperEditButtons.alpha = 1.0f

                // Alle Listener aufräumen
                binding.btnWallpaperSave.setOnClickListener(null)
                binding.btnWallpaperCancel.setOnClickListener(null)
                binding.btnWallpaperSnap.setOnClickListener(null)
                binding.btnWallpaperSnapMode.setOnClickListener(null)
                binding.btnWallpaperHSnap.setOnClickListener(null)
                binding.btnWallpaperVSnap.setOnClickListener(null)
                binding.btnWallpaperOneToOne.setOnClickListener(null)
                binding.btnWallpaperFitWidth.setOnClickListener(null)
                binding.btnToolbarDock.setOnClickListener(null)

                // Layer-Buttons aufräumen
                binding.btnLayerAdd.setOnClickListener(null)
                binding.btnLayerDelete.setOnClickListener(null)
                binding.btnLayerUp.setOnClickListener(null)
                binding.btnLayerDown.setOnClickListener(null)

                // Layer-Callbacks aufräumen
                wallpaperView.onLayerTapped = null

                // Snap-State auf Default zurücksetzen
                wallpaperView.isSnapEnabled = true
                wallpaperView.snapMode = ZoomableImageView.SnapMode.EDGE
                wallpaperView.isHorizontalSnapEnabled = true
                wallpaperView.isVerticalSnapEnabled = true

                isToolbarDockedTop = false

                Timber.d("Wallpaper edit mode: OFF")
            }

        } catch (e: Throwable) {
            TimberWrapper.silentError(e, "Error updating wallpaper edit mode")
        }
    }


// ═════════════════════════════════════════════════════════════════════════════
// HELPER: Toolbar Dimming
// ═════════════════════════════════════════════════════════════════════════════

    /** Animationsdauer für Toolbar-Dimming */
    private companion object {
        const val TOOLBAR_SHOW_DURATION_MS = 200L
    }

    /**
     * Dimmt die Toolbar-Buttons während Drag/Zoom-Gesten.
     * Damit der User das Bild besser sehen kann ohne dass die Buttons stören.
     */
    private fun dimToolbar(dim: Boolean) {
        if (_binding == null) return

        try {
            if (dim) {
                // Komplett unsichtbar – blockiert keine Touches mehr
                binding.wallpaperEditButtons.visibility = View.INVISIBLE
            } else {
                // Wieder sichtbar mit kurzem Fade-In
                binding.wallpaperEditButtons.alpha = 0f
                binding.wallpaperEditButtons.visibility = View.VISIBLE
                binding.wallpaperEditButtons.animate()
                    .alpha(1.0f)
                    .setDuration(TOOLBAR_SHOW_DURATION_MS)
                    .start()
            }
        } catch (e: Throwable) {
            try {
                binding.wallpaperEditButtons.visibility = if (dim) View.INVISIBLE else View.VISIBLE
                binding.wallpaperEditButtons.alpha = 1.0f
            } catch (_: Throwable) {}
        }
    }

    private fun dockToolbar(top: Boolean) {
        if (_binding == null) return

        try {
            val toolbar = binding.wallpaperEditButtons
            val params = toolbar.layoutParams as? android.widget.FrameLayout.LayoutParams ?: return

            if (top) {
                params.gravity = android.view.Gravity.TOP
                // Padding umdrehen: oben braucht Platz für Status Bar
                toolbar.setPadding(
                    toolbar.paddingLeft,
                    toolbar.paddingBottom,  // Was unten war, kommt nach oben
                    toolbar.paddingRight,
                    12  // Minimales Padding unten
                )
            } else {
                params.gravity = android.view.Gravity.BOTTOM
                // Original-Padding wiederherstellen (Insets werden von setupWallpaperEditButtonsInsets gehandhabt)
                toolbar.setPadding(
                    toolbar.paddingLeft,
                    12,  // Minimales Padding oben
                    toolbar.paddingRight,
                    toolbar.paddingTop  // Was oben war, kommt nach unten
                )
            }

            toolbar.layoutParams = params

            // Icon wechseln: Zeigt immer die ANDERE Richtung
            binding.btnToolbarDock.setIconResource(
                if (top) R.drawable.ic_dock_bottom else R.drawable.ic_dock_top
            )
        } catch (e: Throwable) {
            TimberWrapper.silentError(e, "Error docking toolbar")
        }
    }

    /**
     * Speichert die aktuellen View-Transforms in den State.
     * MUSS vor jeder Layer-Operation aufgerufen werden (Add, Delete, Swap),
     * damit die Live-Transforms nicht verloren gehen wenn updateWallpaper()
     * die Layer neu aufbaut.
     */
    private fun saveCurrentViewTransforms() {
        if (_binding == null) return

        try {
            val wallpaperView = binding.wallpaperView
            val currentState = viewModel.wallpaperState.value

            if (currentState.isMultiLayer && wallpaperView.isMultiLayerMode) {
                // Multi-Layer: Alle Layer-Transforms speichern
                val transforms = (0 until wallpaperView.layerCount).map { i ->
                    val layer = wallpaperView.getLayer(i)
                    Triple(
                        layer?.scale ?: 1f,
                        layer?.translateX ?: 0f,
                        layer?.translateY ?: 0f
                    )
                }
                viewModel.onSaveAllLayerTransforms(transforms)
            } else if (currentState.hasWallpaper) {
                // Single-Layer: Transform speichern
                viewModel.onSaveWallpaperTransform(
                    wallpaperView.currentScale,
                    wallpaperView.currentTranslateX,
                    wallpaperView.currentTranslateY
                )
            }
        } catch (e: Throwable) {
            TimberWrapper.silentError(e, "Error saving current view transforms")
        }
    }


// ═════════════════════════════════════════════════════════════════════════════
// HELPER: Layer UI Updates
// ═════════════════════════════════════════════════════════════════════════════

    /**
     * Aktualisiert den Layer-Indikator Text: "Layer 2/3"
     */
    private fun updateLayerIndicator() {
        if (_binding == null) return

        try {
            val wallpaperView = binding.wallpaperView
            val count = wallpaperView.layerCount
            val active = wallpaperView.activeLayerIndex

            if (count > 0) {
                binding.txtLayerIndicator.text = "Layer ${active + 1}/$count"
                binding.txtLayerIndicator.visibility = View.VISIBLE
            } else {
                binding.txtLayerIndicator.visibility = View.GONE
            }
        } catch (e: Throwable) {
            TimberWrapper.silentError(e, "Error updating layer indicator")
        }
    }

    /**
     * Steuert die Sichtbarkeit der Layer-Buttons.
     * Add-Button: Immer sichtbar (auch ohne Layer, um den ersten hinzuzufügen)
     * Delete/Up/Down: Nur sichtbar wenn Layer vorhanden
     */
    private fun updateLayerButtonsVisibility() {
        if (_binding == null) return

        try {
            val wallpaperView = binding.wallpaperView
            val hasLayers = wallpaperView.isMultiLayerMode && wallpaperView.layerCount > 0

            // Add: Immer sichtbar im Edit-Mode
            binding.btnLayerAdd.visibility = View.VISIBLE

            // Delete, Up, Down: Nur wenn Layer existieren
            binding.btnLayerDelete.visibility = if (hasLayers) View.VISIBLE else View.GONE
            binding.btnLayerUp.visibility = if (hasLayers) View.VISIBLE else View.GONE
            binding.btnLayerDown.visibility = if (hasLayers) View.VISIBLE else View.GONE
            binding.txtLayerIndicator.visibility = if (hasLayers) View.VISIBLE else View.GONE
        } catch (e: Throwable) {
            TimberWrapper.silentError(e, "Error updating layer buttons visibility")
        }
    }

    /**
     * Aktiviert/Deaktiviert Layer-Buttons basierend auf Position.
     * - Up deaktiviert wenn Layer ganz oben
     * - Down deaktiviert wenn Layer ganz unten
     * - Delete deaktiviert wenn kein Layer selektiert
     */
    private fun updateLayerButtonStates() {
        if (_binding == null) return

        try {
            val wallpaperView = binding.wallpaperView
            val active = wallpaperView.activeLayerIndex
            val count = wallpaperView.layerCount

            binding.btnLayerUp.isEnabled = active < count - 1
            binding.btnLayerDown.isEnabled = active > 0
            binding.btnLayerDelete.isEnabled = active >= 0 && count > 0

            // Visuelle Rückmeldung: Deaktivierte Buttons halbtransparent
            binding.btnLayerUp.alpha = if (binding.btnLayerUp.isEnabled) 1.0f else 0.3f
            binding.btnLayerDown.alpha = if (binding.btnLayerDown.isEnabled) 1.0f else 0.3f
        } catch (e: Throwable) {
            TimberWrapper.silentError(e, "Error updating layer button states")
        }
    }

    private fun updateSnapButtonIcon(isEnabled: Boolean) {
        if (_binding == null) return
        binding.btnWallpaperSnap.setIconResource(
            if (isEnabled) R.drawable.ic_magnet_on else R.drawable.ic_magnet_off
        )
    }

    private fun updateSnapModeButtonIcon(mode: ZoomableImageView.SnapMode) {
        if (_binding == null) return
        binding.btnWallpaperSnapMode.setIconResource(
            when (mode) {
                ZoomableImageView.SnapMode.EDGE -> R.drawable.ic_rectangle_on
                ZoomableImageView.SnapMode.CENTER -> R.drawable.ic_center_on
            }
        )
    }

    private fun updateHorizontalSnapButtonIcon(isEnabled: Boolean, mode: ZoomableImageView.SnapMode) {
        if (_binding == null) return
        binding.btnWallpaperHSnap.setIconResource(
            when (mode) {
                ZoomableImageView.SnapMode.EDGE ->
                    if (isEnabled) R.drawable.ic_horizontal_edge_on else R.drawable.ic_horizontal_edge_off
                ZoomableImageView.SnapMode.CENTER ->
                    if (isEnabled) R.drawable.ic_horizontal_center_on else R.drawable.ic_horizontal_center_off
            }
        )
    }

    private fun updateVerticalSnapButtonIcon(isEnabled: Boolean, mode: ZoomableImageView.SnapMode) {
        if (_binding == null) return
        binding.btnWallpaperVSnap.setIconResource(
            when (mode) {
                ZoomableImageView.SnapMode.EDGE ->
                    if (isEnabled) R.drawable.ic_vertical_edge_on else R.drawable.ic_vertical_edge_off
                ZoomableImageView.SnapMode.CENTER ->
                    if (isEnabled) R.drawable.ic_vertical_center_on else R.drawable.ic_vertical_center_off
            }
        )
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
            cachedBorderDrawable = null

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

            // 4. Binding nullen - Der "Golden Hammer"
            // Durchbricht den Fragment-View-Zyklus.
            _binding = null

        } catch (e: Throwable) {
            TimberWrapper.silentError(e, "Error in onDestroyView")
        } finally {
            super.onDestroyView()
        }
    }
}