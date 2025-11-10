package com.github.reygnn.kolibri_launcher.ui

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.content.pm.LauncherApps
import android.content.pm.ShortcutInfo
import android.content.res.ColorStateList
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.text.TextUtils
import android.util.TypedValue
import android.view.GestureDetector
import android.view.Gravity
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import androidx.fragment.app.DialogFragment
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.github.reygnn.kolibri_launcher.R
import com.github.reygnn.kolibri_launcher.core.AppConstants
import com.github.reygnn.kolibri_launcher.core.TimberWrapper
import com.github.reygnn.kolibri_launcher.data.AppInfo
import com.github.reygnn.kolibri_launcher.data.CalendarEvent
import com.github.reygnn.kolibri_launcher.data.FavoritesRepository
import com.github.reygnn.kolibri_launcher.data.HiddenAppsRepository
import com.github.reygnn.kolibri_launcher.data.MenuContext
import com.github.reygnn.kolibri_launcher.databinding.FragmentHomeBinding
import com.google.android.material.chip.Chip
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import timber.log.Timber
import javax.inject.Inject
import kotlin.math.abs
import android.text.format.DateFormat
import androidx.core.net.toUri
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * ULTRA CRASH-SAFE HomeFragment
 *
 * Multi-layer exception handling:
 * - All operations catch Throwable (Exception + Error)
 * - CoroutineExceptionHandler for all coroutines
 * - Safe button creation with complete fallback chain
 * - Protected GestureDetector with error recovery
 * - Safe color updates with individual try-catch
 * - Triple-layer observer protection
 *
 * Critical for launcher - this is the home screen users see constantly!
 */
@AndroidEntryPoint
class HomeFragment : Fragment() {

    private val viewModel: HomeViewModel by activityViewModels()

    // CRASH-SAFE: Nullable binding
    private var _binding: FragmentHomeBinding? = null
    private val binding get() = _binding!!

    // CRASH-SAFE: Nullable GestureDetector
    private var gestureDetector: GestureDetector? = null
    private var longClickedApp: AppInfo? = null
    private var currentDialog: DialogFragment? = null

    @Inject
    lateinit var favoritesManager: FavoritesRepository
    @Inject
    lateinit var visibilityManager: HiddenAppsRepository

    // Ultra Paranoia: Coroutine exception handler
    private val fragmentExceptionHandler = CoroutineExceptionHandler { _, throwable ->
        try {
            TimberWrapper.silentError(throwable, "Uncaught exception in HomeFragment")
        } catch (e: Throwable) {
            // Even logging can fail
        }
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
            setupGestures()
            setupDoubleTapActions()
            observeViewModel()
            setupFragmentResultListener()
        } catch (e: Throwable) {
            TimberWrapper.silentError(e, "Error in onViewCreated")
        }
    }

    private fun observeViewModel() {
        // Observer 1: Favorite apps list - Critical for home screen
        viewLifecycleOwner.lifecycleScope.launch(Dispatchers.Main + fragmentExceptionHandler) {
            try {
                repeatOnLifecycle(Lifecycle.State.STARTED) {
                    try {
                        viewModel.favoriteAppsState.collect { state ->
                            if (_binding == null) return@collect

                            Timber.Forest.d("HomeFragment received FAV state: ${state::class.simpleName}")

                            try {
                                when (state) {
                                    is UiState.Loading -> {
                                        safelyRemoveAllViews()
                                    }
                                    is UiState.Success -> {
                                        val colors = viewModel.uiColorsState.value
                                        updateFavoriteAppsUI(
                                            state.data.apps,
                                            colors.textColor,
                                            colors.shadowColor
                                        )
                                    }
                                    is UiState.Error -> {
                                        viewModel.onFavoriteAppsError(state.message)
                                        safelyRemoveAllViews()
                                    }
                                }
                            } catch (e: CancellationException) {
                                throw e
                            } catch (e: Throwable) {
                                TimberWrapper.silentError(e, "Error handling favorite apps state")
                                // Keep showing old favorites
                            }
                        }
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Throwable) {
                        TimberWrapper.silentError(e, "Error collecting favoriteAppsState")
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                TimberWrapper.silentError(e, "Error in repeatOnLifecycle for favorites")
            }
        }

        // Observer 2: Time, date, battery
        viewLifecycleOwner.lifecycleScope.launch(Dispatchers.Main + fragmentExceptionHandler) {
            try {
                repeatOnLifecycle(Lifecycle.State.STARTED) {
                    try {
                        viewModel.uiState.collect { state ->
                            if (_binding == null) return@collect

                            try {
                                binding.timeText.text = state.timeString
                            } catch (e: Throwable) {
                                TimberWrapper.silentError(e, "Error updating time text")
                            }

                            try {
                                binding.dateText.text = state.dateString
                            } catch (e: Throwable) {
                                TimberWrapper.silentError(e, "Error updating date text")
                            }

                            try {
                                binding.batteryText.text = state.batteryString
                            } catch (e: Throwable) {
                                TimberWrapper.silentError(e, "Error updating battery text")
                            }

                            try {
                                updateCalendarChips(state.calendarEvents)
                            } catch (e: Throwable) {
                                TimberWrapper.silentError(e, "Error updating calendar chips")
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

        // Observer 3: UI colors
        viewLifecycleOwner.lifecycleScope.launch(Dispatchers.Main + fragmentExceptionHandler) {
            try {
                repeatOnLifecycle(Lifecycle.State.STARTED) {
                    try {
                        viewModel.uiColorsState.collect { colors ->
                            if (_binding == null) return@collect

                            try {
                                // --- ÄNDERUNG (1/5): Aufruf der umbenannten Funktion ---
                                updateAllColors(colors)
                            } catch (e: Throwable) {
                                TimberWrapper.silentError(e, "Error updating colors")
                                // Keep old colors
                            }
                        }
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Throwable) {
                        TimberWrapper.silentError(e, "Error collecting uiColorsState")
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                TimberWrapper.silentError(e, "Error in repeatOnLifecycle for colors")
            }
        }
    }

    private fun updateCalendarChips(events: List<CalendarEvent>) {
        if (_binding == null) return

        try {
            // Leere Container
            binding.calendarChipsContainer.removeAllViews()

            if (events.isEmpty()) {
                binding.calendarEventsScroll.visibility = View.GONE
                return
            }

            binding.calendarEventsScroll.visibility = View.VISIBLE

            val ctx = context
            if (ctx == null) {
                Timber.w("Context is null, cannot create calendar chips")
                return
            }

            val colors = viewModel.uiColorsState.value // Holt den _gesamten_ UiColorsState
            val is24Hour = DateFormat.is24HourFormat(ctx)
            val timePattern = if (is24Hour) "HH:mm" else "h:mm a"
            val timeFormat = SimpleDateFormat(timePattern, Locale.getDefault())

            val layoutPadding = try {
                resources.getDimensionPixelSize(R.dimen.layout_padding) * 2
            } catch (e: Throwable) {
                TimberWrapper.silentError(e, "Error getting layout_padding")
                0 // Fallback
            }

            val availableWidth = resources.displayMetrics.widthPixels - layoutPadding
            val chipMaxWidth = (availableWidth * 0.80).toInt()

            for (event in events) {
                try {
                    // Übergebe das ganze colors-Objekt
                    val chip = createCalendarChip(ctx, event, timeFormat, colors, chipMaxWidth)
                    if (chip != null) {
                        binding.calendarChipsContainer.addView(chip)
                    }
                } catch (e: Throwable) {
                    TimberWrapper.silentError(e, "Error creating chip for ${event.title}")
                    // Weiter mit nächstem Event
                }
            }
        } catch (e: Throwable) {
            TimberWrapper.silentError(e, "Error updating calendar chips")
        }
    }

    private fun createCalendarChip(
        context: Context,
        event: CalendarEvent,
        timeFormat: SimpleDateFormat,
        colors: UiColorsState, // Nimmt jetzt das ganze Objekt entgegen
        calculatedMaxWidth: Int
    ): Chip? {
        return try {
            Chip(context).apply {
                try {
                    val eventTime = timeFormat.format(Date(event.startTimeMillis))
                    text = "$eventTime ${event.title}"
                } catch (e: Throwable) {
                    TimberWrapper.silentError(e, "Error formatting chip text")
                    text = event.title
                }

                // Text-Ellipsize konfigurieren
                try {
                    ellipsize = TextUtils.TruncateAt.END
                    maxWidth = calculatedMaxWidth
                    isSingleLine = true
                } catch (e: Throwable) {
                    TimberWrapper.silentError(e, "Error setting ellipsize")
                }

                // Styling
                try {
                    // --- ÄNDERUNG (2/5): Neue Logik für Hintergrundfarbe ---
                    val finalChipBgColor = if (colors.chipBackgroundColor == 0) {
                        // "Auto"-Modus: Leite Farbe von Textfarbe ab (alter Standard)
                        Color.argb(40, Color.red(colors.textColor),
                            Color.green(colors.textColor),
                            Color.blue(colors.textColor))
                    } else {
                        // Vom Nutzer gewählte Farbe
                        colors.chipBackgroundColor
                    }
                    chipBackgroundColor = ColorStateList.valueOf(finalChipBgColor)
                    // --- ENDE ---

                    // Text-Farbe
                    setTextColor(colors.textColor)

                    // Kein Close-Icon
                    isCloseIconVisible = false

                    // Kein Checkable
                    isCheckable = false

                    // Thin border (optional) - bleibt an Textfarbe gekoppelt
                    chipStrokeWidth = 1f
                    chipStrokeColor = ColorStateList.valueOf(colors.textColor)

                    // Text-Größe
                    setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)

                    // Padding
                    chipMinHeight = resources.getDimension(R.dimen.chip_min_height)

                } catch (e: Throwable) {
                    TimberWrapper.silentError(e, "Error styling chip")
                }

                // Click-Handler: Öffne Kalender-App
//                setOnClickListener {
//                    try {
//                        openCalendarAtTime(event.startTimeMillis)
//                    } catch (e: Throwable) {
//                        TimberWrapper.silentError(e, "Error opening calendar")
//                        // Fallback: Öffne nur Kalender-App
//                        try {
//                            viewModel.onDateDoubleClick()
//                        } catch (fallbackError: Throwable) {
//                            TimberWrapper.silentError(fallbackError, "Fallback calendar open failed")
//                        }
//                    }
//                }
            }
        } catch (e: Throwable) {
            TimberWrapper.silentError(e, "CRITICAL: Error creating calendar chip")
            null
        }
    }

//    private fun openCalendarAtTime(startTimeMillis: Long) {
//        try {
//            val intent = Intent(Intent.ACTION_VIEW).apply {
//                data = "content://com.android.calendar/time/$startTimeMillis".toUri()
//                flags = Intent.FLAG_ACTIVITY_NEW_TASK
//            }
//            startActivity(intent)
//        } catch (e: Throwable) {
//            // Fallback: Öffne einfach Kalender-App
//            TimberWrapper.silentError(e, "Could not open calendar at specific time")
//            viewModel.onDateDoubleClick()
//        }
//    }

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
                        bundle.getString(AppContextMenuDialogFragment.RESULT_KEY_ACTION)
                    } catch (e: Throwable) {
                        TimberWrapper.silentError(e, "Error getting action from bundle")
                        null
                    }

                    when (action) {
                        "launch_shortcut" -> handleShortcutLaunch(bundle)
                        AppContextMenuAction.Companion.ACTION_ID_APP_INFO -> showAppInfo(app)
                        AppContextMenuAction.Companion.ACTION_ID_TOGGLE_FAVORITE -> toggleFavorite(app)
                        AppContextMenuAction.Companion.ACTION_ID_HIDE_APP -> viewModel.onHideApp(app)
                        AppContextMenuAction.Companion.ACTION_ID_UNHIDE_APP -> viewModel.onShowApp(app)
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
                    AppContextMenuDialogFragment.RESULT_KEY_SHORTCUT,
                    ShortcutInfo::class.java
                )
            } catch (e: Throwable) {
                TimberWrapper.silentError(e, "Error getting shortcut from bundle")
                null
            }

            if (shortcut == null) {
                Timber.Forest.w("Shortcut is null")
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
            val state = viewModel.favoriteAppsState.value
            val currentFavoritesCount = if (state is UiState.Success) {
                state.data.apps.size
            } else {
                0
            }
            viewModel.onToggleFavorite(app, currentFavoritesCount)
        } catch (e: Throwable) {
            TimberWrapper.silentError(e, "Error toggling favorite for ${app.packageName}")
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

    // --- ÄNDERUNG (3/5): Umbenannt und Signatur geändert ---
    private fun updateAllColors(colors: UiColorsState) {
        if (_binding == null) return

        // Variablen aus dem State-Objekt extrahieren
        val textColor = colors.textColor
        val shadowColor = colors.shadowColor

        // Update time text - individual try-catch for independence
        try {
            binding.timeText.setTextColor(textColor)
            binding.timeText.setShadowLayer(
                AppConstants.SHADOW_RADIUS_TIME,
                AppConstants.SHADOW_DX,
                AppConstants.SHADOW_DY,
                shadowColor
            )
        } catch (e: Throwable) {
            TimberWrapper.silentError(e, "Error updating time text color")
        }

        // Update date text
        try {
            binding.dateText.setTextColor(textColor)
            binding.dateText.setShadowLayer(
                AppConstants.SHADOW_RADIUS_DATE,
                AppConstants.SHADOW_DX_SMALL,
                AppConstants.SHADOW_DY_SMALL,
                shadowColor
            )
        } catch (e: Throwable) {
            TimberWrapper.silentError(e, "Error updating date text color")
        }

        // Update battery text
        try {
            binding.batteryText.setTextColor(textColor)
            binding.batteryText.setShadowLayer(
                AppConstants.SHADOW_RADIUS_BATTERY,
                AppConstants.SHADOW_DX_SMALL,
                AppConstants.SHADOW_DY_SMALL,
                shadowColor
            )
        } catch (e: Throwable) {
            TimberWrapper.silentError(e, "Error updating battery text color")
        }

        // --- ÄNDERUNG (4/5): Übergibt das ganze Objekt ---
        updateCalendarChipsColors(colors)

        updateFavoriteAppsColors(textColor, shadowColor)
    }

    // --- ÄNDERUNG (5/5): Signatur und Logik angepasst ---
    private fun updateCalendarChipsColors(colors: UiColorsState) {
        if (_binding == null) return

        try {
            // Variablen extrahieren
            val textColor = colors.textColor
            val shadowColor = colors.shadowColor

            for (i in 0 until binding.calendarChipsContainer.childCount) {
                try {
                    val view = binding.calendarChipsContainer.getChildAt(i)
                    if (view is Chip) {
                        view.setTextColor(textColor)

                        // Neue Logik für Hintergrundfarbe
                        val finalChipBgColor = if (colors.chipBackgroundColor == 0) {
                            Color.argb(40, Color.red(textColor),
                                Color.green(textColor),
                                Color.blue(textColor))
                        } else {
                            colors.chipBackgroundColor
                        }
                        view.chipBackgroundColor = ColorStateList.valueOf(finalChipBgColor)

                        // Stroke bleibt an Textfarbe gekoppelt
                        view.chipStrokeColor = ColorStateList.valueOf(textColor)

                        // Schattenlogik bleibt gleich
                        if (shadowColor != Color.TRANSPARENT) {
                            view.setShadowLayer(
                                AppConstants.SHADOW_RADIUS_APPS,
                                AppConstants.SHADOW_DX,
                                AppConstants.SHADOW_DY,
                                shadowColor
                            )
                        } else {
                            view.setShadowLayer(0f, 0f, 0f, Color.TRANSPARENT)
                        }
                    }
                } catch (e: Throwable) {
                    TimberWrapper.silentError(e, "Error updating color for chip at index $i")
                }
            }
        } catch (e: Throwable) {
            TimberWrapper.silentError(e, "Error updating calendar chips colors")
        }
    }

    private fun updateFavoriteAppsColors(textColor: Int, shadowColor: Int) {
        if (_binding == null) return

        try {
            for (i in 0 until binding.favoriteAppsContainer.childCount) {
                try {
                    val view = binding.favoriteAppsContainer.getChildAt(i)
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
                    TimberWrapper.silentError(e, "Error updating color for child at index $i")
                    // Continue with other buttons
                }
            }
        } catch (e: Throwable) {
            TimberWrapper.silentError(e, "Error updating favorite apps colors")
        }
    }

    private fun safelyRemoveAllViews() {
        try {
            if (_binding != null && isAdded && !isDetached) {
                binding.favoriteAppsContainer.removeAllViews()
            }
        } catch (e: Throwable) {
            TimberWrapper.silentError(e, "Error removing all views")
        }
    }

    private fun updateFavoriteAppsUI(
        appsToShow: List<AppInfo>,
        textColor: Int,
        shadowColor: Int
    ) {
        if (_binding == null) return

        val ctx = context
        if (ctx == null) {
            Timber.Forest.w("Context is null, cannot update favorite apps UI")
            return
        }

        try {
            safelyRemoveAllViews()

            for (app in appsToShow) {
                try {
                    val appButton = createAppButton(ctx, app, textColor, shadowColor)
                    if (appButton != null) {
                        binding.favoriteAppsContainer.addView(appButton)
                    } else {
                        Timber.Forest.w("Failed to create button for ${app.packageName}")
                    }
                } catch (e: Throwable) {
                    TimberWrapper.silentError(e, "Error creating/adding button for ${app.packageName}")
                    // Continue with other apps
                }
            }
        } catch (e: Throwable) {
            TimberWrapper.silentError(e, "Error updating favorite apps UI")
        }
    }

    private fun createAppButton(
        context: Context,
        app: AppInfo,
        textColor: Int,
        shadowColor: Int
    ): Button? {
        return try {
            Button(context).apply {
                try {
                    text = app.displayName
                } catch (e: Throwable) {
                    TimberWrapper.silentError(e, "Error setting button text")
                    text = "App"  // FallbackF
                }

                try {
                    background = null
                } catch (e: Throwable) {
                    TimberWrapper.silentError(e, "Error setting background")
                }

                try {
                    val paddingPx = resources.getDimensionPixelSize(R.dimen.touch_target_padding)
                    setPadding(paddingPx, paddingPx, paddingPx, paddingPx)
                } catch (e: Throwable) {
                    TimberWrapper.silentError(e, "Error setting padding")
                }

                try {
                    gravity = Gravity.START or Gravity.CENTER_VERTICAL
                } catch (e: Throwable) {
                    TimberWrapper.silentError(e, "Error setting gravity")
                }

                try {
                    setTextColor(textColor)
                } catch (e: Throwable) {
                    TimberWrapper.silentError(e, "Error setting text color")
                }

                try {
                    val buttonTextSizeInPx = resources.getDimension(R.dimen.text_size_app_button)
                    setTextSize(TypedValue.COMPLEX_UNIT_PX, buttonTextSizeInPx)
                } catch (e: Throwable) {
                    TimberWrapper.silentError(e, "Error setting text size")
                }

                try {
                    maxLines = 1
                    ellipsize = TextUtils.TruncateAt.END
                } catch (e: Throwable) {
                    TimberWrapper.silentError(e, "Error setting ellipsize/maxlines")
                }

                try {
                    setShadowLayer(
                        AppConstants.SHADOW_RADIUS_APPS,
                        AppConstants.SHADOW_DX,
                        AppConstants.SHADOW_DY,
                        shadowColor
                    )
                } catch (e: Throwable) {
                    TimberWrapper.silentError(e, "Error setting shadow")
                }

                try {
                    layoutParams = LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.WRAP_CONTENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT
                    ).apply {
                        setMargins(0, 8, 0, 8)
                    }
                } catch (e: Throwable) {
                    TimberWrapper.silentError(e, "Error setting layout params")
                }

                setOnClickListener {
                    try {
                        viewModel.onAppClicked(app)
                    } catch (e: Throwable) {
                        TimberWrapper.silentError(e, "Error in app click for ${app.packageName}")
                    }
                }

                setOnLongClickListener {
                    try {
                        showAppContextMenu(app)
                        true
                    } catch (e: Throwable) {
                        TimberWrapper.silentError(e, "Error in long click for ${app.packageName}")
                        false
                    }
                }
            }
        } catch (e: Throwable) {
            TimberWrapper.silentError(e, "CRITICAL: Error creating app button for ${app.packageName}")
            null  // Return null, app won't be displayed but launcher won't crash
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
            TimberWrapper.silentError(e, "Error showing app context menu for ${app.packageName}")
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun setupGestures() {
        try {
            gestureDetector = GestureDetector(requireContext(), createGestureListener())

            binding.rootLayout.setOnTouchListener { _, event ->
                try {
                    gestureDetector?.onTouchEvent(event) ?: false
                } catch (e: Throwable) {
                    TimberWrapper.silentError(e, "Error in touch listener")
                    false  // Gesture failed, but app continues
                }
            }
        } catch (e: Throwable) {
            TimberWrapper.silentError(e, "Error setting up gestures")
            // Gestures won't work, but home screen still functional
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

                // Prüfe, ob die Geste primär horizontal oder vertikal war
                if (abs(diffX) > abs(diffY)) {
                    // HORIZONTALE Geste
                    if (abs(diffX) > AppConstants.SWIPE_THRESHOLD && abs(vX) > AppConstants.SWIPE_VELOCITY_THRESHOLD) {
                        if (diffX > 0) {
                            // Wisch von Links nach Rechts
                            viewModel.onFlingLeft()
                            true
                        } else {
                            // Wisch von Rechts nach Links
                            viewModel.onFlingRight()
                            true
                        }
                    } else {
                        false
                    }
                } else {
                    // VERTIKALE Geste
                    if (abs(diffY) > AppConstants.SWIPE_THRESHOLD &&
                        abs(vY) > AppConstants.SWIPE_VELOCITY_THRESHOLD &&
                        diffY < 0) { // Nur Wisch nach OBEN
                        viewModel.onFlingUp()
                        true
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
            TimberWrapper.silentError(e, "Error setting time click listener")
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
            TimberWrapper.silentError(e, "Error setting date click listener")
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
            TimberWrapper.silentError(e, "Error setting battery click listener")
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
                TimberWrapper.silentError(e, "Error in DoubleClickListener onClick")
            }
        }

        abstract fun onDoubleClick()
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