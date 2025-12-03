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
import com.github.reygnn.kolibri_launcher.ui.appcontextmenu.AppContextMenuAction
import com.github.reygnn.kolibri_launcher.ui.appcontextmenu.AppContextMenuDialogFragment
import com.github.reygnn.kolibri_launcher.ui.appcontextmenu.ContextMenuHelper
import com.github.reygnn.kolibri_launcher.ui.base.UiState
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

@AndroidEntryPoint
class HomeFragment : Fragment() {

    private val viewModel: LauncherViewModel by activityViewModels()

    private var _binding: FragmentHomeBinding? = null
    private val binding get() = _binding!!
    private val favoritesScrollView get() = binding.favoritesScrollView

    private val splitModeCalculator = SplitModeCalculator()
    private val scrollStateVerifier = ScrollStateVerifier()
    private val layoutCalculator = LayoutCalculator()
    private val topMarginCalculator = TopMarginCalculator()
    private val splitWeightCalculator = SplitWeightCalculator()
    private val chipBackgroundCalculator = ChipBackgroundCalculator()
    private val contentSpacingCalculator = ContentSpacingCalculator()
    private var lastSpacingInput: SpacingInput? = null
    private val swipeAnalyzer = SwipeGestureAnalyzer()
    private val timeFormatter = TimeEventFormatter()



    private var gestureDetector: GestureDetector? = null
    private var longClickedApp: AppInfo? = null

    // REACTIVE: Scroll state determines split mode
    private val _needsSplit = MutableStateFlow(false)
    private val needsSplit: StateFlow<Boolean> = _needsSplit.asStateFlow()

    private lateinit var _orientationState: MutableStateFlow<Int>
    val orientationState: StateFlow<Int> get() = _orientationState.asStateFlow()

    private var verifyJob: Job? = null
    private val showBorder = false
    private var wasInSplitMode = false

    private var currentTextSizePx: Float = 0f
    private var currentVerticalPaddingPx: Int = 0
    private var isCurrentFontBold: Boolean = AppConstants.DEFAULT_FONT_BOLD

    private var currentUserPreferredMarginPx: Int = 0


    private val fragmentExceptionHandler = CoroutineExceptionHandler { _, throwable ->
        try {
            TimberWrapper.silentError(throwable, "Uncaught exception in HomeFragment")
        } catch (e: Throwable) {
            // Even logging can fail
        }
    }

    private val orientationSynchronizer by lazy {
        OrientationSynchronizer { resources.configuration.orientation }
    }

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

            hideStatusBar()
            setupBackPressHandler()
            setupGestures()
            setupDoubleTapActions()
            setupFragmentResultListener()
            setupHomeWindowInsets()

            observeViewModel()
            observeLayoutChanges()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            TimberWrapper.silentError(e, "Error in onViewCreated")
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

    // Margin berechnen und anwenden
    private fun applyTopMargin(scale: Float) {
        try {
            if (_binding == null) return

            val baseMargin = try {
                resources.getDimensionPixelSize(R.dimen.spacing_medium)
            } catch (e: Exception) { AppConstants.FALLBACK_DIMEN_PX }

            // 1. Berechne, was der User (basierend auf Settings/Scale) eigentlich will
            val calculatedUserMargin = topMarginCalculator.calculate(
                scale = scale,
                baseMarginPx = baseMargin,
                screenHeightPx = resources.displayMetrics.heightPixels
            )

            // 2. Speichere das für später (falls Chips an/aus gehen)
            if (currentUserPreferredMarginPx != calculatedUserMargin) {
                currentUserPreferredMarginPx = calculatedUserMargin
                // 3. Trigger die dynamische Berechnung
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
    private fun updateDynamicSpacing() {
        if (_binding == null) return

        val chipsContainer = binding.calendarEventsScroll
        val favoritesContainer = binding.favoritesContainer
        val areChipsVisible = chipsContainer.isVisible

        favoritesContainer.post {
            if (_binding == null) return@post

            val currentChipsHeight = if (areChipsVisible) chipsContainer.height else 0

            // 1. Input-Objekt bauen
            val input = SpacingInput(
                userPreferredMarginPx = currentUserPreferredMarginPx,
                chipsHeightPx = currentChipsHeight,
                areChipsVisible = areChipsVisible
            )

            // 2. Cache-Check (data class equals macht den Vergleich)
            if (input == lastSpacingInput) return@post

            // 3. Berechnung (pure function)
            val newMargin = contentSpacingCalculator.calculate(
                input.userPreferredMarginPx,
                input.chipsHeightPx,
                input.areChipsVisible
            )

            // 4. Cache aktualisieren
            lastSpacingInput = input

            // 5. Anwenden
            val params = favoritesContainer.layoutParams as? ViewGroup.MarginLayoutParams
            if (params != null) {
                Timber.d("📏 Spacing: ${params.topMargin} → $newMargin (chips=${input.chipsHeightPx}px)")
                params.topMargin = newMargin
                favoritesContainer.layoutParams = params

                checkScrollStateAfterNextLayout("Dynamic spacing applied: $newMargin")
                safePost { scheduleScrollVerification() }
            }
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

                // applyScrollViewBorder()

                // ScrollView MUSS das Abfangen von Touches verhindern
                customScrollView.allowIntercept = false
                customScrollView.isScrollContainer = false
                customScrollView.isClickable = false
                customScrollView.isFocusable = false
                customScrollView.isFocusableInTouchMode = false

                // Listener auf NULL setzen
                customScrollView.setOnTouchListener(null)
                binding.gestureZone.setOnTouchListener(null)

                removeScrollViewBorder()

                Timber.d("Full mode: 100%% (ScrollView touch-transparent)")
            }

            binding.favoritesScrollView.layoutParams = scrollParams
            binding.gestureZone.layoutParams = gestureParams

        } catch (e: Throwable) {
            TimberWrapper.silentError(e, "Error adjusting ScrollView width")
        }
    }

    private fun applyScrollViewBorder(textColor: Int) {
        try {
            val frameColor = Color.argb(
                AppConstants.BORDER_ALPHA,
                Color.red(textColor),
                Color.green(textColor),
                Color.blue(textColor)
            )

            val drawable = GradientDrawable().apply {
                setColor(Color.TRANSPARENT)

                val strokeWidth = try {
                    resources.getDimensionPixelSize(R.dimen.split_screen_border_width)
                } catch (e: Throwable) {
                    AppConstants.FALLBACK_BORDER_WIDTH_PX
                }
                setStroke(strokeWidth, frameColor)

                val cornerRadius = try {
                    resources.getDimension(R.dimen.split_screen_corner_radius)
                } catch (e: Throwable) {
                    AppConstants.FALLBACK_CORNER_RADIUS_PX
                }
                setCornerRadius(cornerRadius)
            }

            binding.favoritesScrollView.background = drawable

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

/*        if (_needsSplit.value) {
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
                        button.setTextColor(textColor)
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
                    setTextColor(textColor)

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

        override fun onLongPress(e: MotionEvent) {
            try {
                viewModel.onLongPress()
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
                    SwipeGestureAnalyzer.SwipeResult.TOWARDS_LEFT -> { viewModel.onSwipeTowardsLeft(); true }
                    SwipeGestureAnalyzer.SwipeResult.TOWARDS_RIGHT -> { viewModel.onSwipeTowardsRight(); true }
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
                        "launch_shortcut" -> handleShortcutLaunch(bundle)
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

    private fun handleShortcutLaunch(bundle: Bundle) {
        try {
            val shortcut = try {
                bundle.getParcelable(
                    AppContextMenuDialogFragment.RESULT_KEY_SHORTCUT,
                    ShortcutInfo::class.java
                )
            } catch (e: Throwable) {
                TimberWrapper.silentError(e, "Error getting shortcut")
                null
            }

            if (shortcut == null) {
                Timber.w("Shortcut is null")
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
    // LIFECYCLE
    // ============================================================================

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