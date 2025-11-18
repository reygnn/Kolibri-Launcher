package com.github.reygnn.kolibri_launcher.ui.home

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.content.pm.LauncherApps
import android.content.pm.ShortcutInfo
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Rect
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
import android.widget.Button
import android.widget.LinearLayout
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
import com.github.reygnn.kolibri_launcher.domain.repository.FavoritesRepository
import com.github.reygnn.kolibri_launcher.domain.repository.HiddenAppsRepository
import com.github.reygnn.kolibri_launcher.ui.appcontextmenu.AppContextMenuAction
import com.github.reygnn.kolibri_launcher.ui.appcontextmenu.AppContextMenuDialogFragment
import com.github.reygnn.kolibri_launcher.ui.base.UiState
import com.github.reygnn.kolibri_launcher.ui.main.LauncherViewModel
import com.google.android.material.chip.Chip
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import timber.log.Timber
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import javax.inject.Inject
import kotlin.math.abs

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

    private val viewModel: LauncherViewModel by activityViewModels()

    // CRASH-SAFE: Nullable binding
    private var _binding: FragmentHomeBinding? = null
    private val binding get() = _binding!!

    // CRASH-SAFE: Nullable GestureDetector
    private var gestureDetector: GestureDetector? = null
    private var longClickedApp: AppInfo? = null
    private var currentDialog: DialogFragment? = null
    private var currentMaxItemsOnScreen: Int = Int.MAX_VALUE
    private var isSplitScreenActive = false
    private var shouldBlockGlobalVerticalGestures = false
    private var isTouchOnAppButton = false


    // Ultra Paranoia: Coroutine exception handler
    private val fragmentExceptionHandler = CoroutineExceptionHandler { _, throwable ->
        try {
            TimberWrapper.silentError(throwable, "Uncaught exception in HomeFragment")
        } catch (e: Throwable) {
            // Even logging can fail
        }
    }

    private var layoutChangeListener: View.OnLayoutChangeListener? = null

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
            setupHomeWindowInsets()
            setupDynamicMaxFavoritesListener()
        } catch (e: Throwable) {
            TimberWrapper.silentError(e, "Error in onViewCreated")
        }
    }

    private fun runCapacityCheck(container: View) {
        try {
            val newHeight = container.bottom - container.top

            if (newHeight == 0 || _binding == null) {
                // Wenn die Höhe 0 ist, verwenden wir den Fallback-Wert (könnte 0 sein, was OK ist)
                return
            }

            val ctx = context ?: return

            val (itemHeight, itemMargin) = measureFavoriteItemHeight(ctx)
            if (itemHeight == 0 || (itemHeight + itemMargin) == 0) {
                Timber.Forest.w("Dynamic calc failed: Item height is zero.")
                return
            }

            val totalHeightPerItem = itemHeight + itemMargin
            val maxItemsToShow = (newHeight / totalHeightPerItem).toInt()

            currentMaxItemsOnScreen = maxItemsToShow

            Timber.Forest.i("Capacity re-calculated: $maxItemsToShow (H: $newHeight)")

            viewModel.onHomeViewMeasured(AppConstants.MAX_FAVORITES_ON_HOME)

        } catch (e: Throwable) {
            TimberWrapper.silentError(e, "Error in manual capacity check")
        }
    }

    /**
     * Richtet den Listener ein und führt die Initialmessung durch.
     */
    private fun setupDynamicMaxFavoritesListener() {
        if (_binding == null) return

        layoutChangeListener =
            View.OnLayoutChangeListener { v, left, top, right, bottom, oldLeft, oldTop, oldRight, oldBottom ->
                // Nur neu berechnen, wenn sich die Höhe tatsächlich geändert hat
                if (bottom - top != oldBottom - oldTop) {
                    runCapacityCheck(v)
                }
            }

        // Führe die Messung einmal manuell aus, da der Listener sonst nur bei Änderungen feuert
        // Wir posten es, um sicherzustellen, dass die Layout-Initialisierung abgeschlossen ist
        binding.splitContainer.post {
            runCapacityCheck(binding.splitContainer)
        }

        binding.splitContainer.addOnLayoutChangeListener(layoutChangeListener)
    }

    /**
     * Misst die Höhe eines einzelnen Favoriten-Buttons, indem ein Dummy-Button
     * mit den exakt gleichen Eigenschaften wie in createAppButton erstellt wird.
     *
     * @return Ein Pair<Int, Int> mit (itemHeight, verticalMarginInPx)
     */
    private fun measureFavoriteItemHeight(context: Context): Pair<Int, Int> {
        try {
            val dummyButton = Button(context)

            // Wende die exakt gleichen Eigenschaften wie in createAppButton an,
            // die die Höhe beeinflussen.

            val paddingPx = try {
                resources.getDimensionPixelSize(R.dimen.touch_target_padding)
            } catch (e: Throwable) { 0 }
            dummyButton.setPadding(paddingPx, paddingPx, paddingPx, paddingPx)

            val buttonTextSizeInPx = try {
                resources.getDimension(R.dimen.text_size_app_button)
            } catch (e: Throwable) { 16f } // Fallback, falls Dimension nicht gefunden
            dummyButton.setTextSize(TypedValue.COMPLEX_UNIT_PX, buttonTextSizeInPx)

            dummyButton.maxLines = 1
            dummyButton.text = "Test" // Inhalt für die Messung der Höhe

            dummyButton.layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )

            // Messe den Button
            dummyButton.measure(
                View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED),
                View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED)
            )

            // Hole die Margins aus createAppButton: setMargins(0, 8, 0, 8)
            // Das sind 8px oben + 8px unten = 16px
            val verticalMarginInPx = 16

            val itemHeight = dummyButton.measuredHeight

            if (itemHeight > 0) {
                return Pair(itemHeight, verticalMarginInPx)
            }
        } catch (e: Throwable) {
            TimberWrapper.silentError(e, "Error measuring dummy button")
        }

        // Fallback, falls Messung fehlschlägt
        return Pair(0, 0)
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

        // Observer 3: Nur für TimeBasedEvents mit distinctUntilChanged
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
                                    TimberWrapper.silentError(e, "Error updating time-based chips")
                                }
                            }
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Throwable) {
                        TimberWrapper.silentError(e, "Error collecting timeBasedEvents")
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                TimberWrapper.silentError(e, "Error in repeatOnLifecycle for timeBasedEvents")
            }
        }

        // Observer 4: UI colors
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

    private fun refreshUiStateFromViewModel() {
        if (_binding == null) return

        // Daten und Farben direkt aus den StateFlows abrufen
        val state = viewModel.favoriteAppsState.value
        val colors = viewModel.uiColorsState.value

        if (state is UiState.Success) {
            // 1. Favoriten-UI inklusive der SplitScreen-Logik neu rendern
            updateFavoriteAppsUI(
                state.data.apps,
                colors.textColor,
                colors.shadowColor
            )
            // 2. Farben auf alle Elemente (Uhrzeit, Datum, Chips, neuer Rahmen) anwenden
            updateAllColors(colors)
        }
    }

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
                TimberWrapper.silentError(e, "Error getting layout_padding")
                0 // Fallback
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
                    TimberWrapper.silentError(e, "Error creating chip for ${event.title}")
                }
            }

            binding.calendarEventsScroll.visibility = View.VISIBLE
        } catch (e: Throwable) {
            TimberWrapper.silentError(e, "Error updating time-based chips")
        }
    }

    /**
     * Internal helper: Konfiguriert die gemeinsamen Styling-Eigenschaften für Alarm- und Kalender-Chips
     */
    private fun configureChip(
        chip: Chip,
        colors: UiColorsState,
        chipMaxWidth: Int
    ) {
        try {
            // Text-Ellipsize und Größe
            chip.ellipsize = TextUtils.TruncateAt.END
            chip.maxWidth = chipMaxWidth
            chip.isSingleLine = true

            // Hintergrundfarbe
            val finalChipBgColor = if (colors.chipBackgroundColor == 0) {
                // "Auto"-Modus: Leite Farbe von Textfarbe ab
                Color.argb(
                    40,
                    Color.red(colors.textColor),
                    Color.green(colors.textColor),
                    Color.blue(colors.textColor)
                )
            } else {
                // Vom Nutzer gewählte Farbe
                colors.chipBackgroundColor
            }
            chip.chipBackgroundColor = ColorStateList.valueOf(finalChipBgColor)

            // Textfarbe
            chip.setTextColor(colors.textColor)

            // Kein Close-Icon und nicht checkable
            chip.isCloseIconVisible = false
            chip.isCheckable = false

            // Border
            chip.chipStrokeWidth = 1f
            chip.chipStrokeColor = ColorStateList.valueOf(colors.textColor)

            // Textgröße und Höhe
            chip.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            chip.chipMinHeight = chip.resources.getDimension(R.dimen.chip_min_height)

        } catch (e: Throwable) {
            TimberWrapper.silentError(e, "Error configuring chip")
        }
    }

    /**
     * Internal helper: Aktualisiert AUSSCHLIESSLICH die Farb-Eigenschaften
     * eines bestehenden Chips.
     */
    private fun configureChipColorOnly(chip: Chip, colors: UiColorsState) {
        try {
            // 1. Hintergrundfarbe (mit der "Auto"-Logik)
            val finalChipBgColor = if (colors.chipBackgroundColor == 0) {
                // "Auto"-Modus: Leite Farbe von Textfarbe ab
                Color.argb(
                    40,
                    Color.red(colors.textColor),
                    Color.green(colors.textColor),
                    Color.blue(colors.textColor)
                )
            } else {
                // Vom Nutzer gewählte Farbe
                colors.chipBackgroundColor
            }
            chip.chipBackgroundColor = ColorStateList.valueOf(finalChipBgColor)

            // 2. Textfarbe
            chip.setTextColor(colors.textColor)

            // 3. Border (Stroke) Farbe
            chip.chipStrokeColor = ColorStateList.valueOf(colors.textColor)

        } catch (e: Throwable) {
            TimberWrapper.silentError(e, "Error applying colors to chip")
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

                    // FIX: Runde AUF zur nächsten vollen Minute
                    // Nutzer setzen Alarme immer auf volle Minuten (06:00)
                    // System gibt interne Trigger-Zeit zurück (05:59:22)
                    val calendar = Calendar.getInstance()
                    calendar.timeInMillis = event.triggerTimeMillis

                    // Wenn irgendwelche Sekunden vorhanden sind, runde auf nächste Minute
                    if (calendar.get(Calendar.SECOND) > 0 || calendar.get(Calendar.MILLISECOND) > 0) {
                        calendar.add(Calendar.MINUTE, 1)
                    }
                    calendar.set(Calendar.SECOND, 0)
                    calendar.set(Calendar.MILLISECOND, 0)

                    val displayTime = calendar.timeInMillis
                    val alarmTime = timeFormat.format(Date(displayTime))

                    text = "$alarmTime ${event.title}"
                } catch (e: Throwable) {
                    TimberWrapper.silentError(e, "Error formatting alarm chip text")
                    text = event.title
                }

                configureChip(this, colors, chipMaxWidth)
            }
        } catch (e: Throwable) {
            TimberWrapper.silentError(e, "CRITICAL: Error creating alarm chip")
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
                    TimberWrapper.silentError(e, "Error formatting calendar chip text")
                    text = event.title
                }

                configureChip(this, colors, chipMaxWidth)
            }
        } catch (e: Throwable) {
            TimberWrapper.silentError(e, "CRITICAL: Error creating calendar chip")
            null
        }
    }

    private fun setupFragmentResultListener() {
        try {
            childFragmentManager.setFragmentResultListener(
                AppContextMenuDialogFragment.Companion.REQUEST_KEY,
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
                    AppContextMenuDialogFragment.Companion.RESULT_KEY_SHORTCUT,
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
            viewModel.onToggleFavorite(app)
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

        updateCalendarChipsColors(colors)

        updateFavoriteAppsColors(textColor, shadowColor)
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
                    TimberWrapper.silentError(e, "Error updating chip color at index $i")
                }
            }
        } catch (e: Throwable) {
            TimberWrapper.silentError(e, "Error in updateCalendarChipsColors")
        }
    }

    private fun updateFavoriteAppsColors(textColor: Int, shadowColor: Int) {
        if (_binding == null) return

        try {
            // Select the active container based on the current mode
            val activeContainer = if (isSplitScreenActive) {
                binding.scrollingAppList
            } else {
                binding.staticAppList
            }

            for (i in 0 until activeContainer.childCount) {
                try {
                    val view = activeContainer.getChildAt(i)
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
                // Must target both potential list containers now
                binding.scrollingAppList.removeAllViews()
                binding.staticAppList.removeAllViews()
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
        val ctx = context ?: return

        try {
            // 1. Modus bestimmen
            val shouldSplitScreen = appsToShow.size > currentMaxItemsOnScreen

            // 2. Visuals und Layout-Container aktualisieren (Sichtbarkeit umschalten)
            applySplitScreenMode(shouldSplitScreen, textColor)

            // 3. Den korrekten Container für die Buttons auswählen
            val targetContainer = if (shouldSplitScreen) {
                binding.scrollingAppList
            } else {
                binding.staticAppList
            }

            // 4. Container leeren und Buttons hinzufügen
            targetContainer.removeAllViews()

            for (app in appsToShow) {
                try {
                    // createAppButton enthält jetzt die angepasste Touch-Logik
                    val appButton = createAppButton(ctx, app, textColor, shadowColor)
                    if (appButton != null) {
                        targetContainer.addView(appButton)
                    } else {
                        Timber.Forest.w("Failed to create button for ${app.packageName}")
                    }
                } catch (e: Throwable) {
                    TimberWrapper.silentError(e, "Error creating/adding button for ${app.packageName}")
                }
            }
        } catch (e: Throwable) {
            TimberWrapper.silentError(e, "Error updating favorite apps UI")
        }
    }

    /**
     * Steuert Layout-Modus:
     * - Normal: Container ist WRAP_CONTENT (lässt Platz unten frei für Gesten).
     * - Split: Container ist MATCH_CONSTRAINT (füllt Screen für Scrolling).
     */
    /**
     * Steuert Layout-Modus und Visuals (Rahmen).
     */
    private fun applySplitScreenMode(enableSplit: Boolean, baseTextColor: Int) {
        isSplitScreenActive = enableSplit

        // 1. WICHTIG: Sichtbarkeit der Haupt-Container umschalten
        binding.splitContainer.visibility = if (enableSplit) View.VISIBLE else View.GONE
        binding.staticFavoritesContainer.visibility = if (enableSplit) View.GONE else View.VISIBLE

        // 2. Logik und Visuals anwenden
        if (enableSplit) {
            // --- SPLIT MODUS (Rahmen AN) ---

            val scrollParams = binding.favoritesScrollView.layoutParams as LinearLayout.LayoutParams
            val gestureParams = binding.gestureZoneRight.layoutParams as LinearLayout.LayoutParams

            scrollParams.weight = 1f
            gestureParams.weight = 1f // Wichtig: 50/50 Split
            binding.favoritesScrollView.layoutParams = scrollParams
            binding.gestureZoneRight.layoutParams = gestureParams

            binding.favoritesScrollView.isScrollContainer = true

            // VISUAL: Dezenten Rahmen erstellen
            try {
                val alpha = 50
                val borderColor = androidx.core.graphics.ColorUtils.setAlphaComponent(baseTextColor, alpha)

                val borderDrawable = android.graphics.drawable.GradientDrawable().apply {
                    shape = android.graphics.drawable.GradientDrawable.RECTANGLE
                    cornerRadius = TypedValue.applyDimension(
                        TypedValue.COMPLEX_UNIT_DIP, 16f, resources.displayMetrics
                    )
                    setStroke(2, borderColor)
                    setColor(Color.TRANSPARENT)
                }

                binding.favoritesScrollView.background = borderDrawable

                val padding = TypedValue.applyDimension(
                    TypedValue.COMPLEX_UNIT_DIP, 8f, resources.displayMetrics
                ).toInt()
                binding.favoritesScrollView.setPadding(padding, padding, padding, padding)

            } catch (e: Throwable) {
                TimberWrapper.silentError(e, "Error creating border drawable")
            }

        } else {
            // --- NORMAL MODUS (Rahmen AUS) ---

            // Aufräumen des ScrollViews, falls er gerade unsichtbar wurde
            binding.favoritesScrollView.background = null
            binding.favoritesScrollView.setPadding(0, 0, 0, 0)
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

                setOnLongClickListener {
                    try {
                        // LOKALE AKTION: Zeige Shortcut Menu
                        showAppContextMenu(app)
                        true // Wichtig: Konsumiert das Event, unterdrückt den Click
                    } catch (e: Throwable) {
                        TimberWrapper.silentError(e, "Error in long click for ${app.packageName}")
                        false
                    }
                }

                setOnClickListener {
                    try {
                        viewModel.onAppClicked(app)
                    } catch (e: Throwable) {
                        TimberWrapper.silentError(e, "Error in app click for ${app.packageName}")
                    }
                }

                // Die Swipe-Logik wird nun vom root_layout via Event Bubbling übernommen.
                setOnTouchListener { v, event ->
                    // 1. LongPress Blockade Flag setzen/zurücksetzen
                    when (event.action) {
                        MotionEvent.ACTION_DOWN -> {
                            isTouchOnAppButton = true
                        }
                        MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                            isTouchOnAppButton = false
                        }
                    }

                    // 2. WICHTIG: Immer 'false' zurückgeben.
                    // Dies erlaubt dem Button, seine eigenen nativen Klick- und Long-Klick-Listener zu feuern.
                    // Es lässt das Event auch zum Parent (root_layout) hochblubbern, um Swipes zu erkennen.
                    return@setOnTouchListener false
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
            val dialog = AppContextMenuDialogFragment.Companion.newInstance(
                app,
                MenuContext.HOME_SCREEN,
                false
            )
            currentDialog = dialog
            dialog.show(childFragmentManager, AppContextMenuDialogFragment.Companion.TAG)
        } catch (e: Throwable) {
            TimberWrapper.silentError(e, "Error showing app context menu for ${app.packageName}")
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun setupGestures() {
        try {
            gestureDetector = GestureDetector(requireContext(), createGestureListener())

            // 1. Root Listener (Fängt alles, was durchfällt - globaler Swipe)
            binding.rootLayout.setOnTouchListener { _, event ->
                try {
                    shouldBlockGlobalVerticalGestures = false
                    gestureDetector?.onTouchEvent(event) ?: false
                } catch (e: Throwable) {
                    false
                }
            }

            // 2. ScrollView Listener (NUR NOCH für den Fall, dass SplitScreen AKTIV ist)
            binding.favoritesScrollView.setOnTouchListener { v, event ->
                if (!isSplitScreenActive) {
                    // Im Inaktiv-Modus lassen wir das Event durch,
                    // da der Root-Listener die Gesten erkennen soll.
                    return@setOnTouchListener false
                }

                // Logik für Split-Modus (Vertical Swipes fressen, um Scrolling zu erlauben)
                try {
                    // Wichtig: Verhindert, dass der Root-View Touch-Events klaut (Overscroll-Fix).
                    v.parent.requestDisallowInterceptTouchEvent(true)

                    shouldBlockGlobalVerticalGestures = true
                    val handledByDetector = gestureDetector?.onTouchEvent(event) ?: false
                    shouldBlockGlobalVerticalGestures = false

                    // Wenn Detector Horizontal Swipe/DoubleTap erkannt hat, konsumieren wir es.
                    if (handledByDetector) true else false
                } catch (e: Throwable) {
                    TimberWrapper.silentError(e, "Error in ScrollView touch bridge")
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
                if (!isTouchOnAppButton) {
                    viewModel.onLongPress() // Öffnet den Settings Dialog
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
                        abs(vY) > AppConstants.SWIPE_VELOCITY_THRESHOLD
                    ) {
                        // NEU: Blockier-Check!
                        // Wenn wir im Split-ScrollView sind, ignorieren wir das hier,
                        // damit der ScrollView scrollt statt den Drawer zu öffnen.
                        if (shouldBlockGlobalVerticalGestures) {
                            return false
                        }

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

    private fun setupHomeWindowInsets() {
        try {
            // Speichere die ursprünglichen Abstände aus dem XML
            val initialRootPadding = Rect(
                binding.rootLayout.paddingLeft,
                binding.rootLayout.paddingTop,
                binding.rootLayout.paddingRight,
                binding.rootLayout.paddingBottom
            )

            // Sicherstellen, dass die LayoutParams MarginLayoutParams sind
            val timeContainerParams = binding.timeContainer.layoutParams as? ViewGroup.MarginLayoutParams
            if (timeContainerParams == null) {
                TimberWrapper.silentError("TimeContainer LayoutParams sind keine MarginLayoutParams")
                // Fallback: Nutze das Root-Padding für alles
                ViewCompat.setOnApplyWindowInsetsListener(binding.rootLayout) { v, insets ->
                    val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
                    v.setPadding(
                        initialRootPadding.left + systemBars.left,
                        initialRootPadding.top + systemBars.top, // Fallback
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

                // 1. Root-Layout für Gesten-Navigation (unten) und Ränder (links/rechts) padden
                v.setPadding(
                    initialRootPadding.left + systemBars.left,
                    initialRootPadding.top, // Top-Padding des Roots bleibt statisch (von XML)
                    initialRootPadding.right + systemBars.right,
                    initialRootPadding.bottom + systemBars.bottom // Wichtig für Gesten-Navigationsleiste
                )

                // 2. Den Zeit-Container dynamisch positionieren:
                // (initialMargin + systemBars.top)
                timeContainerParams.topMargin = initialTimeMarginTop + systemBars.top
                binding.timeContainer.layoutParams = timeContainerParams

                // Wichtig: Insets nicht konsumieren, nur darauf reagieren
                insets
            }
        } catch (e: Throwable) {
            TimberWrapper.silentError(e, "Error applying window insets to HomeFragment")
        }
    }

    override fun onResume() {
        super.onResume()
        try {
            // Wenn HomeFragment sichtbar wird -> Statusleiste ausblenden
            hideStatusBar()

            // Zuerst eine Layout-Pass-Aktualisierung erzwingen.
            // Wir machen den Container kurz sichtbar und fordern ein Layout an,
            // um die OnLayoutChangeListener-Logik zu triggern und currentMaxItemsOnScreen zu aktualisieren.
            binding.splitContainer.visibility = View.VISIBLE
            binding.splitContainer.requestLayout()

            // Nach einer kurzen Verzögerung (einem Frame) wird dann die UI synchronisiert.
            binding.splitContainer.post {
                // Führt die Logik aus performInitialMeasurement erneut aus, um sicherzustellen,
                // dass die aktuellste Messung vorliegt.
                runCapacityCheck(binding.splitContainer)

                // Erst dann die UI aktualisieren
                refreshUiStateFromViewModel()

                // Die Sichtbarkeit wird in refreshUiStateFromViewModel > updateFavoriteAppsUI > applySplitScreenMode
                // wieder auf den korrekten Zustand (GONE oder VISIBLE) gesetzt.
            }
        } catch (e: Throwable) {
            TimberWrapper.silentError(e, "Error in onResume hiding status bar")
        }
    }

    override fun onPause() {
        super.onPause()
        try {
            // Wenn HomeFragment verlassen wird -> Statusleiste wieder einblenden
            // Wichtig, damit andere Apps und dein AppDrawer sie anzeigen.
            showStatusBar()
        } catch (e: Throwable) {
            TimberWrapper.silentError(e, "Error in onPause showing status bar")
        }
    }

    private fun getInsetsController(): WindowInsetsControllerCompat? {
        val window = activity?.window ?: return null
        return WindowInsetsControllerCompat(window, window.decorView)
    }

    private fun hideStatusBar() {
        val controller = getInsetsController() ?: return
        // Icons der Statusleiste verstecken
        controller.hide(WindowInsetsCompat.Type.statusBars())
        // Verhalten festlegen: Leiste erscheint kurz bei Wisch von oben
        controller.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
    }

    private fun showStatusBar() {
        // Icons der Statusleiste wieder anzeigen
        getInsetsController()?.show(WindowInsetsCompat.Type.statusBars())
    }

    override fun onDestroyView() {
        try {
            currentDialog?.dismissAllowingStateLoss()
            currentDialog = null

            gestureDetector = null
            longClickedApp = null

            if (layoutChangeListener != null) {
                _binding?.splitContainer?.removeOnLayoutChangeListener(layoutChangeListener)
                layoutChangeListener = null
            }

            _binding = null
        } catch (e: Throwable) {
            TimberWrapper.silentError(e, "Error in onDestroyView")
        } finally {
            super.onDestroyView()
        }
    }
}