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
import androidx.fragment.app.DialogFragment
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
import com.github.reygnn.kolibri_launcher.domain.model.EventType
import com.github.reygnn.kolibri_launcher.domain.model.MenuContext
import com.github.reygnn.kolibri_launcher.domain.model.TimeBasedEvent
import com.github.reygnn.kolibri_launcher.domain.model.UiColorsState
import com.github.reygnn.kolibri_launcher.ui.appcontextmenu.AppContextMenuAction
import com.github.reygnn.kolibri_launcher.ui.appcontextmenu.AppContextMenuDialogFragment
import com.github.reygnn.kolibri_launcher.ui.base.UiState
import com.github.reygnn.kolibri_launcher.ui.main.LauncherViewModel
import com.google.android.material.chip.Chip
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import timber.log.Timber
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import kotlin.math.abs

// ============================================================================
// REACTIVE SPLIT MODE FLOW - HOW IT WORKS
// ============================================================================
/**
 * REVOLUTIONARY REACTIVE APPROACH - NO CAPACITY MEASUREMENTS NEEDED!
 *
 * The Problem We Solved:
 * - Old approach: Try to calculate "how many buttons fit" (unreliable, timing issues)
 * - Race conditions between rendering and measurement
 * - Complex dummy button logic prone to crashes
 *
 * Our Solution: Let Android Decide!
 *
 * Flow Architecture:
 * 1. User adds favorites / rotates device / chips appear
 *    → renderFavorites() / onConfigurationChanged() / updateTimeBasedChips()
 *
 * 2. After rendering completes (OnGlobalLayoutListener ensures timing)
 *    → checkScrollStateAfterNextLayout() is called
 *
 * 3. System determines scroll capability
 *    → checkAndEmitScrollState() asks: canScrollVertically(1)?
 *    → TRUE = content overflows, scrolling needed
 *    → FALSE = content fits, no scrolling needed
 *
 * 4. Emit new state to Flow
 *    → _needsSplit.value = canScroll
 *    → Flow only emits if value actually changed (prevents redundant updates)
 *
 * 5. Observer reacts automatically
 *    → needsSplit.collect { split -> ... }
 *    → adjustScrollViewWidth(split) gets called
 *
 * 6. UI adapts based on split mode:
 *    - split=false: ScrollView takes 100% width, fully transparent to touches
 *                   → All gestures work across entire screen
 *    - split=true:  ScrollView takes 55% (portrait) or 30% (landscape)
 *                   → Border appears, scrolling enabled
 *                   → Gesture zone takes remaining space for swipe gestures
 *
 * Why This Works:
 * - System (canScrollVertically) is the single source of truth
 * - Pure reactive: State change → Flow emission → UI reaction
 * - No manual calculations = no race conditions
 * - Self-correcting: Every content change re-evaluates automatically
 *
 * IT SIMPLY WORKS!
 */

@AndroidEntryPoint
class HomeFragment : Fragment() {

    private val viewModel: LauncherViewModel by activityViewModels()

    private var _binding: FragmentHomeBinding? = null
    private val binding get() = _binding!!
    private val favoritesScrollView get() = binding.favoritesScrollView as NonInterceptingScrollView

    private var gestureDetector: GestureDetector? = null
    private var longClickedApp: AppInfo? = null
    private var currentDialog: DialogFragment? = null

    // REACTIVE: Scroll state determines split mode
    private val _needsSplit = MutableStateFlow(false)
    private val needsSplit: StateFlow<Boolean> = _needsSplit.asStateFlow()

    private lateinit var _orientationState: MutableStateFlow<Int>
    val orientationState: StateFlow<Int> get() = _orientationState.asStateFlow()


    private val fragmentExceptionHandler = CoroutineExceptionHandler { _, throwable ->
        try {
            TimberWrapper.silentError(throwable, "Uncaught exception in HomeFragment")
        } catch (e: Throwable) {
            // Even logging can fail
        }
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
            hideStatusBar()
            setupBackPressHandler()
            setupGestures()
            setupDoubleTapActions()
            setupFragmentResultListener()
            setupHomeWindowInsets()

            observeViewModel()
        } catch (e: Throwable) {
            TimberWrapper.silentError(e, "Error in onViewCreated")
        }
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)

        try {
            Timber.d("⟳ Configuration changed - orientation=${newConfig.orientation}")

            // Den Orientation-State sofort aktualisieren
            _orientationState.value = newConfig.orientation

            // Warte bis Layout wirklich fertig ist!
            checkScrollStateAfterNextLayout("Scroll state checked after rotation")
        } catch (e: Throwable) {
            TimberWrapper.silentError(e, "Error in onConfigurationChanged")
        }
    }

    // ============================================================================
    // REACTIVE SCROLL STATE DETECTION
    // ============================================================================

    /**
     * THE MAGIC: Check if ScrollView can scroll
     * System decides → we react!
     */
    private fun checkAndEmitScrollState() {
        try {
            if (_binding == null || !isAdded) return

            val canScroll = binding.favoritesScrollView.canScrollVertically(1)

            if (_needsSplit.value != canScroll) {
                Timber.d("Scroll capability changed: canScroll=$canScroll")
                _needsSplit.value = canScroll
            }
        } catch (e: Throwable) {
            TimberWrapper.silentError(e, "Error checking scroll state")
        }
    }

    // ============================================================================
    // OBSERVERS - PURE REACTIVE!
    // ============================================================================

    private fun observeViewModel() {
        // Observer 1: Favorites (no capacity needed!)
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

                                    // Re-check scroll state after chips change
                                    binding.favoritesScrollView.post {
                                        checkAndEmitScrollState()
                                    }
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

        } catch (e: Throwable) {
            TimberWrapper.silentError(e, "Error rendering favorites")
        }
    }

    /**
     * Adjust ScrollView width based on split mode
     */
// In HomeFragment.kt
    private fun adjustScrollViewWidth(enableSplit: Boolean, colors: UiColorsState) {
        try {
            val scrollParams = binding.favoritesScrollView.layoutParams as LinearLayout.LayoutParams
            val gestureParams = binding.gestureZone.layoutParams as LinearLayout.LayoutParams

            // 1. Zugriff auf das Custom ScrollView
            val customScrollView = binding.favoritesScrollView as NonInterceptingScrollView

            if (enableSplit) {
                // Orientation-abhängige Gewichtung
                val isLandscape =
                    resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE

                if (isLandscape) {
                    // LANDSCAPE:
                    scrollParams.weight = AppConstants.LANDSCAPE_SPLIT_SCROLL_WEIGHT
                    gestureParams.weight = AppConstants.LANDSCAPE_SPLIT_GESTURE_WEIGHT
                    Timber.d("  → Split mode: (landscape)")
                } else {
                    // PORTRAIT:
                    scrollParams.weight = AppConstants.PORTRAIT_SPLIT_SCROLL_WEIGHT
                    gestureParams.weight = AppConstants.PORTRAIT_SPLIT_GESTURE_WEIGHT
                    Timber.d("  → Split mode: (portrait)")
                }

                Timber.d("Konfiguration: isLandscape=${isLandscape}, ScrollWeight=${scrollParams.weight}, GestureWeight=${gestureParams.weight}")

                binding.gestureZone.visibility = View.VISIBLE
                applyScrollViewBorder(colors.textColor)

                // ScrollView darf Touches abfangen (zum Scrollen)
                customScrollView.allowIntercept = true

                // ScrollView ist touchbar
                customScrollView.isScrollContainer = true
                customScrollView.isClickable = true
                customScrollView.isFocusable = true
                customScrollView.isFocusableInTouchMode = true

                // Touch Listener für Split Mode: Gesture Zone verarbeitet Gesten auf der rechten Hälfte
                binding.gestureZone.setOnTouchListener { _, event ->
                    gestureDetector?.onTouchEvent(event) ?: false
                }
            } else {
                // FULL MODE: 100% / 0%. ScrollView muss Event-Abfangen verhindern.
                scrollParams.weight = 1f
                gestureParams.weight = 0f
                binding.gestureZone.visibility = View.GONE
                binding.favoritesScrollView.background = null
                binding.favoritesScrollView.setPadding(0, 0, 0, 0)

                // ScrollView MUSS das Abfangen von Touches verhindern (Vertikales Scrollen)
                customScrollView.allowIntercept = false

                // ScrollView "unsichtbar" für Touch-Events machen (zusätzliche Sicherheit)
                customScrollView.isScrollContainer = false
                customScrollView.isClickable = false
                customScrollView.isFocusable = false
                customScrollView.isFocusableInTouchMode = false

                // Listener auf NULL setzen
                customScrollView.setOnTouchListener(null)
                binding.gestureZone.setOnTouchListener(null)

                Timber.d("Full mode: 100% (ScrollView touch-transparent)")
            }

            binding.favoritesScrollView.layoutParams = scrollParams
            binding.gestureZone.layoutParams = gestureParams

        } catch (e: Throwable) {
            TimberWrapper.silentError(e, "Error adjusting ScrollView width")
        }
    }

    private fun applyScrollViewBorder(textColor: Int) {
        try {
            // ... (Berechnung von frameColor und Drawable bleibt unverändert) ...
            val frameColor = Color.argb(
                51,
                Color.red(textColor),
                Color.green(textColor),
                Color.blue(textColor)
            )

            val drawable = GradientDrawable().apply {
                setColor(Color.TRANSPARENT)

                val strokeWidth = try {
                    resources.getDimensionPixelSize(R.dimen.split_screen_border_width)
                } catch (e: Throwable) {
                    4
                }
                setStroke(strokeWidth, frameColor)

                val cornerRadius = try {
                    resources.getDimension(R.dimen.split_screen_corner_radius)
                } catch (e: Throwable) {
                    16f
                }
                setCornerRadius(cornerRadius)
            }

            binding.favoritesScrollView.background = drawable

            val borderPadding = try {
                resources.getDimensionPixelSize(R.dimen.split_screen_border_inset)
            } catch (e: Throwable) {
                16
            }

            // Padding setzen: Der Rahmen ist außerhalb dieses Paddings (wenn background gesetzt).
            binding.favoritesScrollView.setPadding(
                0,
                borderPadding,
                borderPadding,
                borderPadding
            )

            binding.favoritesScrollView.clipToPadding =
                true // Behalten, um den Inhalt im Rahmen zu halten

            // KRITISCHE ÄNDERUNG: MARGINS LÖSCHEN/VEREINFACHEN
            val params = binding.favoritesScrollView.layoutParams as LinearLayout.LayoutParams

            // Margins zurücksetzen, um zu verhindern, dass die View nach links verschoben wird
            // Setzen Sie alle Margins auf 0
            params.setMargins(0, 0, 0, 0)

            // OPTIONAL: Wenn Sie den Rahmen rechts bündig halten möchten,
            // setzen Sie das rechte Margin auf den Border-Inset-Wert (oder lassen es bei 0)
            // params.rightMargin = borderPadding

            binding.favoritesScrollView.layoutParams = params

        } catch (e: Throwable) {
            TimberWrapper.silentError(e, "Error applying border")
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

        if (_needsSplit.value) {
            try {
                applyScrollViewBorder(textColor)
            } catch (e: Throwable) {
                TimberWrapper.silentError(e, "Error updating border color")
            }
        }
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
                    val view = binding.appList.getChildAt(i)
                    if (view is Button) {
                        view.setTextColor(textColor)
                        view.setShadowLayer(
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

                    val paddingPx = try {
                        resources.getDimensionPixelSize(R.dimen.touch_target_padding)
                    } catch (e: Throwable) {
                        TimberWrapper.silentError(e, "Error getting padding dimension")
                        16 // Fallback
                    }
                    setPadding(paddingPx, paddingPx, paddingPx, paddingPx)

                    gravity = Gravity.START or Gravity.CENTER_VERTICAL
                    setTextColor(textColor)

                    val buttonTextSizeInPx = try {
                        resources.getDimension(R.dimen.text_size_app_button)
                    } catch (e: Throwable) {
                        TimberWrapper.silentError(e, "Error getting text size dimension")
                        48f // Fallback
                    }
                    setTextSize(TypedValue.COMPLEX_UNIT_PX, buttonTextSizeInPx)

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
                        setMargins(0, 8, 0, 8)
                    }

                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.START

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
            val chipMaxWidth = (availableWidth * 0.80).toInt()

            for (event in events) {
                try {
                    val chip = when (event.type) {
                        EventType.ALARM -> createAlarmChip(ctx, event, colors, chipMaxWidth)
                        EventType.CALENDAR -> createCalendarChip(ctx, event, colors, chipMaxWidth)
                    }

                    if (chip != null) {
                        binding.calendarChipsContainer.addView(chip)
                    }
                } catch (e: Throwable) {
                    TimberWrapper.silentError(e, "Error creating chip")
                }
            }

            binding.calendarEventsScroll.visibility = View.VISIBLE

            // Warte bis Layout wirklich fertig ist!
            checkScrollStateAfterNextLayout("Scroll state checked after chips updated")

        } catch (e: Throwable) {
            TimberWrapper.silentError(e, "Error updating chips")
        }
    }

    private fun configureChip(chip: Chip, colors: UiColorsState, chipMaxWidth: Int) {
        try {
            chip.ellipsize = TextUtils.TruncateAt.END
            chip.maxWidth = chipMaxWidth
            chip.isSingleLine = true

            val finalChipBgColor = if (colors.chipBackgroundColor == 0) {
                Color.argb(
                    40,
                    Color.red(colors.textColor),
                    Color.green(colors.textColor),
                    Color.blue(colors.textColor)
                )
            } else {
                colors.chipBackgroundColor
            }
            chip.chipBackgroundColor = ColorStateList.valueOf(finalChipBgColor)

            chip.setTextColor(colors.textColor)
            chip.isCloseIconVisible = false
            chip.isCheckable = false
            chip.chipStrokeWidth = 1f
            chip.chipStrokeColor = ColorStateList.valueOf(colors.textColor)
            chip.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            chip.chipMinHeight = chip.resources.getDimension(R.dimen.chip_min_height)

        } catch (e: Throwable) {
            TimberWrapper.silentError(e, "Error configuring chip")
        }
    }

    private fun configureChipColorOnly(chip: Chip, colors: UiColorsState) {
        try {
            val finalChipBgColor = if (colors.chipBackgroundColor == 0) {
                Color.argb(
                    40,
                    Color.red(colors.textColor),
                    Color.green(colors.textColor),
                    Color.blue(colors.textColor)
                )
            } else {
                colors.chipBackgroundColor
            }
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
                    val is24Hour = DateFormat.is24HourFormat(context)
                    val timePattern = if (is24Hour) "HH:mm" else "h:mm a"
                    val timeFormat = SimpleDateFormat(timePattern, Locale.getDefault())

                    val calendar = Calendar.getInstance()
                    calendar.timeInMillis = event.triggerTimeMillis

                    if (calendar.get(Calendar.SECOND) > 0 || calendar.get(Calendar.MILLISECOND) > 0) {
                        calendar.add(Calendar.MINUTE, 1)
                    }
                    calendar.set(Calendar.SECOND, 0)
                    calendar.set(Calendar.MILLISECOND, 0)

                    val displayTime = calendar.timeInMillis
                    val alarmTime = timeFormat.format(Date(displayTime))

                    text = "$alarmTime ${event.title}"
                } catch (e: Throwable) {
                    TimberWrapper.silentError(e, "Error formatting alarm")
                    text = event.title
                }

                configureChip(this, colors, chipMaxWidth)
            }
        } catch (e: Throwable) {
            TimberWrapper.silentError(e, "Error creating alarm chip")
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
                    val timePattern = if (is24Hour) "HH:mm" else "h:mm a"
                    val timeFormat = SimpleDateFormat(timePattern, Locale.getDefault())
                    val eventTime = timeFormat.format(Date(event.triggerTimeMillis))

                    text = "$eventTime ${event.title}"
                } catch (e: Throwable) {
                    TimberWrapper.silentError(e, "Error formatting calendar")
                    text = event.title
                }

                configureChip(this, colors, chipMaxWidth)
            }
        } catch (e: Throwable) {
            TimberWrapper.silentError(e, "Error creating calendar chip")
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

        override fun onFling(
            e1: MotionEvent?,
            e2: MotionEvent,
            vX: Float,
            vY: Float
        ): Boolean {
            if (e1 == null) return false

            return try {
                val diffY = e2.y - e1.y
                val diffX = e2.x - e1.x

                if (abs(diffX) > abs(diffY)) {
                    if (abs(diffX) > AppConstants.SWIPE_THRESHOLD && abs(vX) > AppConstants.SWIPE_VELOCITY_THRESHOLD) {
                        if (diffX > 0) {
                            viewModel.onFlingLeft()
                            true
                        } else {
                            viewModel.onFlingRight()
                            true
                        }
                    } else {
                        false
                    }
                } else {
                    if (abs(diffY) > AppConstants.SWIPE_THRESHOLD &&
                        abs(vY) > AppConstants.SWIPE_VELOCITY_THRESHOLD
                    ) {
                        if (diffY < 0) {
                            viewModel.onFlingUp()
                            true
                        } else if (diffY > 0) {
                            viewModel.onFlingDown()
                            true
                        } else {
                            false
                        }
                    } else {
                        false
                    }
                }
            } catch (e: Throwable) {
                TimberWrapper.silentError(e, "Error in fling")
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
        try {
            currentDialog?.dismissAllowingStateLoss()
            currentDialog = null

            longClickedApp = app
            val dialog = AppContextMenuDialogFragment.newInstance(
                app,
                MenuContext.HOME_SCREEN,
                false
            )
            currentDialog = dialog
            dialog.show(childFragmentManager, AppContextMenuDialogFragment.TAG)
        } catch (e: Throwable) {
            TimberWrapper.silentError(e, "Error showing context menu")
        }
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

            binding.favoritesScrollView.viewTreeObserver.addOnGlobalLayoutListener(
                object : ViewTreeObserver.OnGlobalLayoutListener {
                    override fun onGlobalLayout() {
                        try {
                            if (_binding == null || !isAdded) return

                            // ONE-SHOT: Listener sofort entfernen
                            binding.favoritesScrollView.viewTreeObserver.removeOnGlobalLayoutListener(
                                this
                            )

                            checkAndEmitScrollState()

                            if (debugMessage.isNotEmpty()) {
                                Timber.d(debugMessage)
                            }
                        } catch (e: Throwable) {
                            TimberWrapper.silentError(e, "Error in one-shot layout listener")
                        }
                    }
                }
            )
        } catch (e: Throwable) {
            TimberWrapper.silentError(e, "Error registering one-shot layout listener")
        }
    }

    // ============================================================================
    // LIFECYCLE
    // ============================================================================

    override fun onResume() {
        super.onResume()
        try {
            hideStatusBar()
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
            currentDialog?.dismissAllowingStateLoss()
            currentDialog = null

            gestureDetector = null
            longClickedApp = null

            _binding = null
        } catch (e: Throwable) {
            TimberWrapper.silentError(e, "Error in onDestroyView")
        } finally {
            super.onDestroyView()
        }
    }
}