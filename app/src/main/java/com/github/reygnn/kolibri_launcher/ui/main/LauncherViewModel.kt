/*
 * Copyright (C) 2025 reygnn (Ulrich Kaufmann)
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */

package com.github.reygnn.kolibri_launcher.ui.main

import android.app.WallpaperColors
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.text.format.DateFormat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.LiveData
import androidx.lifecycle.ProcessLifecycleOwner
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.asLiveData
import androidx.lifecycle.viewModelScope
import com.github.reygnn.kolibri_launcher.R
import com.github.reygnn.kolibri_launcher.core.AppConstants
import com.github.reygnn.kolibri_launcher.core.TimberWrapper
import com.github.reygnn.kolibri_launcher.core.coerceInSafe
import com.github.reygnn.kolibri_launcher.di.MainDispatcher
import com.github.reygnn.kolibri_launcher.domain.model.AppInfo
import com.github.reygnn.kolibri_launcher.domain.model.AppLoadResult
import com.github.reygnn.kolibri_launcher.domain.model.FavoriteAppsResult
import com.github.reygnn.kolibri_launcher.domain.model.HomeSettings
import com.github.reygnn.kolibri_launcher.domain.model.SortOrder
import com.github.reygnn.kolibri_launcher.domain.model.UiColorsState
import com.github.reygnn.kolibri_launcher.domain.model.WallpaperLayerState
import com.github.reygnn.kolibri_launcher.domain.usecase.CheckAppUsageUseCase
import com.github.reygnn.kolibri_launcher.domain.usecase.GetAutoLaunchSettingUseCase
import com.github.reygnn.kolibri_launcher.domain.usecase.GetAutoShowKeyboardSettingUseCase
import com.github.reygnn.kolibri_launcher.domain.usecase.GetDrawerAppsUseCase
import com.github.reygnn.kolibri_launcher.domain.usecase.GetFavoriteAppsUseCase
import com.github.reygnn.kolibri_launcher.domain.usecase.GetLayoutSettingsUseCase
import com.github.reygnn.kolibri_launcher.domain.usecase.GetSplitModeThresholdUseCase
import com.github.reygnn.kolibri_launcher.domain.usecase.GetTextShadowEnabledUseCase
import com.github.reygnn.kolibri_launcher.domain.usecase.HandleSwipeActionUseCase
import com.github.reygnn.kolibri_launcher.domain.usecase.HideAppUseCase
import com.github.reygnn.kolibri_launcher.domain.usecase.ObserveHomeSettingsUseCase
import com.github.reygnn.kolibri_launcher.domain.usecase.ObserveInstalledAppsUseCase
import com.github.reygnn.kolibri_launcher.domain.usecase.ObserveTimeBasedEventsUseCase
import com.github.reygnn.kolibri_launcher.domain.usecase.ObserveUiColorsUseCase
import com.github.reygnn.kolibri_launcher.domain.usecase.RecordAppLaunchUseCase
import com.github.reygnn.kolibri_launcher.domain.usecase.RefreshAppsUseCase
import com.github.reygnn.kolibri_launcher.domain.usecase.RequestLockUseCase
import com.github.reygnn.kolibri_launcher.domain.usecase.RequestNotificationsUseCase
import com.github.reygnn.kolibri_launcher.domain.usecase.ResetAppUsageUseCase
import com.github.reygnn.kolibri_launcher.domain.usecase.SetChipBackgroundColorUseCase
import com.github.reygnn.kolibri_launcher.domain.usecase.SetContentTopMarginUseCase
import com.github.reygnn.kolibri_launcher.domain.usecase.SetFontBoldUseCase
import com.github.reygnn.kolibri_launcher.domain.usecase.SetLayoutScaleUseCase
import com.github.reygnn.kolibri_launcher.domain.usecase.SetTextColorUseCase
import com.github.reygnn.kolibri_launcher.domain.usecase.SetTextShadowEnabledUseCase
import com.github.reygnn.kolibri_launcher.domain.usecase.SetVerticalPaddingUseCase
import com.github.reygnn.kolibri_launcher.domain.usecase.ShowAppUseCase
import com.github.reygnn.kolibri_launcher.domain.usecase.ToggleFavoriteUseCase
import com.github.reygnn.kolibri_launcher.domain.usecase.ToggleSortOrderUseCase
import com.github.reygnn.kolibri_launcher.domain.model.WallpaperState
import com.github.reygnn.kolibri_launcher.domain.usecase.ObserveWallpaperStateUseCase
import com.github.reygnn.kolibri_launcher.domain.usecase.SaveWallpaperStateUseCase
import com.github.reygnn.kolibri_launcher.domain.usecase.SetWallpaperImageUseCase
import com.github.reygnn.kolibri_launcher.domain.usecase.ClearWallpaperUseCase
import android.net.Uri
import com.github.reygnn.kolibri_launcher.data.WallpaperFileManager
import com.github.reygnn.kolibri_launcher.ui.base.BaseViewModel
import com.github.reygnn.kolibri_launcher.ui.base.UiEvent
import com.github.reygnn.kolibri_launcher.ui.base.UiState
import com.github.reygnn.kolibri_launcher.ui.swipeactions.SwipeSlot
import com.github.reygnn.kolibri_launcher.ui.util.AppUpdateSignal
import com.github.reygnn.kolibri_launcher.ui.util.TestMode
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import java.text.SimpleDateFormat
import java.util.Locale
import javax.inject.Inject

/**
 * ULTRA CRASH-SAFE HomeViewModel
 *
 * Critical launcher ViewModel with maximum stability:
 * - All operations catch Throwable (Exception + Error)
 * - CancellationException properly re-thrown
 * - Safe math operations with fallbacks
 * - Protected system calls
 * - Emergency fallbacks for all critical features
 * - Triple-layer error handling for app loading
 *
 * This ensures the home screen stays functional even under extreme conditions.
 */
@HiltViewModel
class LauncherViewModel @Inject constructor(
    private val getFavoriteAppsUseCase: GetFavoriteAppsUseCase,
    private val getDrawerAppsUseCase: GetDrawerAppsUseCase,
    private val hideAppUseCase: HideAppUseCase,
    private val toggleFavoriteUseCase: ToggleFavoriteUseCase,
    private val requestLockUseCase: RequestLockUseCase,
    private val requestNotificationsUseCase: RequestNotificationsUseCase,
    private val recordAppLaunchUseCase: RecordAppLaunchUseCase,
    private val refreshAppsUseCase: RefreshAppsUseCase,
    private val resetAppUsageUseCase: ResetAppUsageUseCase,
    private val showAppUseCase: ShowAppUseCase,
    private val toggleSortOrderUseCase: ToggleSortOrderUseCase,
    private val handleSwipeActionUseCase: HandleSwipeActionUseCase,
    private val observeTimeBasedEventsUseCase: ObserveTimeBasedEventsUseCase,
    private val observeUiColorsUseCase: ObserveUiColorsUseCase,
    private val setTextColorUseCase: SetTextColorUseCase,
    private val setTextShadowEnabledUseCase: SetTextShadowEnabledUseCase,
    private val setChipBackgroundColorUseCase: SetChipBackgroundColorUseCase,
    private val observeInstalledAppsUseCase: ObserveInstalledAppsUseCase,
    private val getAutoLaunchSettingUseCase: GetAutoLaunchSettingUseCase,
    private val observeHomeSettingsUseCase: ObserveHomeSettingsUseCase,
    private val checkAppUsageUseCase: CheckAppUsageUseCase,
    private val getAutoShowKeyboardSettingUseCase: GetAutoShowKeyboardSettingUseCase,
    private val getTextShadowEnabledUseCase: GetTextShadowEnabledUseCase,
    private val getSplitModeThresholdUseCase: GetSplitModeThresholdUseCase,
    private val getLayoutSettingsUseCase: GetLayoutSettingsUseCase,
    private val setLayoutScaleUseCase: SetLayoutScaleUseCase,
    private val setVerticalPaddingUseCase: SetVerticalPaddingUseCase,
    private val setFontBoldUseCase: SetFontBoldUseCase,
    private val setContentTopMarginUseCase: SetContentTopMarginUseCase,
    private val observeWallpaperStateUseCase: ObserveWallpaperStateUseCase,
    private val saveWallpaperStateUseCase: SaveWallpaperStateUseCase,
    private val setWallpaperImageUseCase: SetWallpaperImageUseCase,
    private val clearWallpaperUseCase: ClearWallpaperUseCase,
    private val wallpaperFileManager: WallpaperFileManager,

    private val appUpdateSignal: AppUpdateSignal,
    private val savedStateHandle: SavedStateHandle,
    @param:ApplicationContext private val context: Context,
    @MainDispatcher mainDispatcher: CoroutineDispatcher,
    private val testMode: TestMode
) : BaseViewModel<UiEvent>(mainDispatcher) {

    // ===========================================
    // COMPANION OBJECT - DEFAULTS
    // ===========================================

    companion object {
        private const val DEFAULT_TIME = "--:--"
        private const val DEFAULT_DATE = "---"
        private const val DEFAULT_BATTERY = "---%"
    }

    // ===========================================
    // UI STATE - HOME SCREEN
    // ===========================================

    private val _uiState = MutableStateFlow(HomeUiState())
    val uiState: StateFlow<HomeUiState> = _uiState.asStateFlow()

    private val _favoriteAppsState = MutableStateFlow<UiState<FavoriteAppsResult>>(UiState.Loading)
    val favoriteAppsState: StateFlow<UiState<FavoriteAppsResult>> = _favoriteAppsState.asStateFlow()

    val drawerApps: LiveData<List<AppInfo>> = getDrawerAppsUseCase.drawerApps

    // ===========================================
    // UI STATE - COLORS & THEMING
    // ===========================================

    private val _uiColorsState = MutableStateFlow(UiColorsState())
    val uiColorsState: StateFlow<UiColorsState> = _uiColorsState.asStateFlow()

    private val wallpaperColorsFlow = MutableStateFlow<WallpaperColors?>(null)

    // ===========================================
    // SETTINGS - HOME SCREEN
    // ===========================================

    private val _homeSettings = MutableStateFlow(HomeSettings())
    val sortOrder: LiveData<SortOrder> = _homeSettings
        .map { it.sortOrder }
        .asLiveData(viewModelScope.coroutineContext)

    private val _maxFavoritesOnHome = MutableStateFlow(AppConstants.MAX_FALLBACK_FAVORITES_ON_HOME)
    val maxFavoritesOnHome: StateFlow<Int> = _maxFavoritesOnHome.asStateFlow()

    // ===========================================
    // SETTINGS - LAYOUT
    // ===========================================

    val layoutScaleState: StateFlow<Float> = getLayoutSettingsUseCase.layoutScale
        .catch { e ->
            TimberWrapper.silentError(e, "Error observing layout scale")
            emit(AppConstants.DEFAULT_LAYOUT_SCALE)
        }
        .stateIn(viewModelScope, SharingStarted.Eagerly, AppConstants.DEFAULT_LAYOUT_SCALE)

    val verticalPaddingState: StateFlow<Float> = getLayoutSettingsUseCase.verticalPadding
        .catch { e ->
            TimberWrapper.silentError(e, "Error observing vertical padding")
            emit(AppConstants.DEFAULT_VERTICAL_PADDING_FACTOR)
        }
        .stateIn(viewModelScope, SharingStarted.Eagerly, AppConstants.DEFAULT_VERTICAL_PADDING_FACTOR)

    val isFontBoldState: StateFlow<Boolean> = getLayoutSettingsUseCase.isFontBold
        .catch { e ->
            TimberWrapper.silentError(e, "Error observing font bold")
            emit(AppConstants.DEFAULT_FONT_BOLD)
        }
        .stateIn(viewModelScope, SharingStarted.Eagerly, AppConstants.DEFAULT_FONT_BOLD)

    val contentTopMarginState: StateFlow<Float> = getLayoutSettingsUseCase.contentTopMargin
        .catch { e ->
            TimberWrapper.silentError(e, "Error observing content top margin")
            emit(0f)
        }
        .stateIn(viewModelScope, SharingStarted.Eagerly, 0f)

    // ===========================================
    // WALLPAPER STATE
    // ===========================================

    private val _wallpaperState = MutableStateFlow(WallpaperState.NONE)
    val wallpaperState: StateFlow<WallpaperState> = _wallpaperState.asStateFlow()

    /** Separater Edit-Mode State (UI-only, nicht persistiert) */
    private val _isWallpaperEditMode = MutableStateFlow(false)
    val isWallpaperEditMode: StateFlow<Boolean> = _isWallpaperEditMode.asStateFlow()

    // ===========================================
    // SETTINGS - SPLIT MODE
    // ===========================================

    /** 0 = Automatik (Android entscheidet) */
    val splitModeThreshold: StateFlow<Int> = getSplitModeThresholdUseCase()
        .catch { e ->
            TimberWrapper.silentError(e, "Error observing split mode threshold")
            emit(0)
        }
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(AppConstants.FLOW_SHARING_TIMEOUT_MS),
            initialValue = 0
        )

    // ===========================================
    // SYSTEM STATE - SCREEN LOCK
    // ===========================================

    private val _isLockingInProgress = MutableStateFlow(false)
    val isLockingInProgress: StateFlow<Boolean> = _isLockingInProgress.asStateFlow()

    // ===========================================
    // SEARCH & NAVIGATION
    // ===========================================

    val appDrawerSearchQuery: StateFlow<String> =
        savedStateHandle.getStateFlow(AppConstants.KEY_SEARCH_QUERY, "")

    // ===========================================
    // ONE-TIME TOAST FLAGS
    // ===========================================

    private var enableLockToastShown = false
    private var enableSwipeDownToastShown = false

    // ===========================================
    // APP LIFECYCLE OBSERVER
    // ===========================================

    private val appLifecycleObserver = LifecycleEventObserver { _, event ->
        if (event == Lifecycle.Event.ON_START) {
            updateTimeAndDate()
        }
    }

    // ===========================================
    // INIT
    // ===========================================

    init {
        // SYNC: Zeit sofort beim Kaltstart
        updateTimeAndDate()

        // APP-LEVEL: Zeit aktualisieren wenn App in Vordergrund kommt
        ProcessLifecycleOwner.get().lifecycle.addObserver(appLifecycleObserver)

        // ASYNC: Minuten-Ticks während App läuft
        launchSafe {
            observeSystemTimeChanges().collect {
                updateTimeAndDate()
            }
        }

        getInitialBatteryState()
        updateUiColors()

        // 1. Coroutine für Kalender-Events
        launchSafe {
            observeTimeBasedEventsUseCase().collect { events ->
                _uiState.update { it.copy(timeBasedEvents = events) }
            }
        }

        // 2. Coroutine für UI-Farben
        launchSafe {
            observeUiColorsUseCase(wallpaperColorsFlow).collect { colorsState ->
                _uiColorsState.value = colorsState
            }
        }

        if (!testMode.isEnabled) {

            // 3. EIGENE Coroutine für App-Updates (Der "Motor")
            launchSafe {
                try {
                    delay(AppConstants.INITIAL_APP_LOAD_DELAY_MS) // Kleiner Start-Delay
                    observeInstalledAppsUseCase().collect { result ->
                        if (result is AppLoadResult.Error) {
                            sendEvent(UiEvent.ShowToast(result.messageResId))
                        }
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Throwable) {
                    TimberWrapper.silentError(e, "Error observing installed apps")
                }
            }

            // 4. EIGENE Coroutine für Home-Settings
            launchSafe {
                try {
                    observeHomeSettingsUseCase().collect { settings ->
                        _homeSettings.value = settings
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Throwable) {
                    TimberWrapper.silentError(e, "Error observing home settings")
                }
            }

            // 5. Coroutine für Favoriten
            launchSafe {
                try {
                    getFavoriteAppsUseCase.favoriteApps.collect { state ->
                        handleFavoriteAppsState(state)
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Throwable) {
                    TimberWrapper.silentError(e, "Error observing favorite apps")
                }
            }

            // Wallpaper State beobachten
            launchSafe {
                observeWallpaperStateUseCase().collect { state ->
                    _wallpaperState.value = state
                }
            }

            // 6. App-Updates Listener starten (startet intern auch eine Coroutine via launchSafe)
            listenForAppUpdates()

        } else {
            // In test mode: ONLY observe favorites (but KEEP the toast logic!)
            launchSafe {
                try {
                    getFavoriteAppsUseCase.favoriteApps.collect { state ->
                        handleFavoriteAppsState(state)
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Throwable) {
                    TimberWrapper.silentError(e, "Error observing favorite apps in test mode")
                }
            }
        }
    }

    // ===========================================
    // CLEANUP
    // ===========================================

    override fun onCleared() {
        super.onCleared()
        ProcessLifecycleOwner.get().lifecycle.removeObserver(appLifecycleObserver)
    }

    private suspend fun handleFavoriteAppsState(state: UiState<FavoriteAppsResult>) {
        try {
            _favoriteAppsState.value = state

            val toastAlreadyShown = savedStateHandle.get<Boolean>(AppConstants.KEY_FALLBACK_TOAST_SHOWN) == true
            if (state is UiState.Success && state.data.isFallback && !toastAlreadyShown) {
                savedStateHandle[AppConstants.KEY_FALLBACK_TOAST_SHOWN] = true
                sendEvent(UiEvent.ShowToast(R.string.welcome_toast_fallback_favorites))
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            TimberWrapper.silentError(e, "Error processing favorite apps state")
        }
    }

    // ===========================================
    // TIME MANAGEMENT (CLEAN FLOW VERSION)
    // ===========================================

    /**
     * CLEAN & ELEGANT:
     * Erstellt einen Flow, der auf System-Events hört.
     * Feuert exakt dann, wenn die System-Uhr umspringt.
     */
    private fun observeSystemTimeChanges() = kotlinx.coroutines.flow.callbackFlow {
        val receiver = object : android.content.BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                // Ein Signal senden (Unit), der Inhalt ist egal
                trySend(Unit)
            }
        }

        // Wir hören auf Minuten-Ticks, manuelle Zeitänderungen und Zeitzonenwechsel
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_TIME_TICK)
            addAction(Intent.ACTION_TIME_CHANGED)
            addAction(Intent.ACTION_TIMEZONE_CHANGED)
        }

        // Receiver registrieren
        context.registerReceiver(receiver, filter)

        // WICHTIG: Einmal sofort feuern, damit beim Starten direkt eine Zeit da ist
        trySend(Unit)

        // Cleanup: Wird automatisch aufgerufen, wenn der Scope (ViewModel) stirbt
        awaitClose {
            context.unregisterReceiver(receiver)
        }
    }

    /**
     * Public Methode für das Fragment (für onResume)
     */
    fun refreshTimeNow() {
        updateTimeAndDate()
    }

    /**
     * Die eigentliche Update-Logik (unverändert zur vorherigen Version)
     */
    fun updateTimeAndDate() {
        try {
            val now = System.currentTimeMillis()
            val is24Hour = DateFormat.is24HourFormat(context)

            val timeFormat = if (is24Hour) {
                SimpleDateFormat("HH:mm", Locale.getDefault())
            } else {
                SimpleDateFormat("h:mm a", Locale.getDefault())
            }
            val dateFormat = SimpleDateFormat("E, d MMM", Locale.getDefault())

            val newTimeString = timeFormat.format(now)
            val newDateString = dateFormat.format(now)

            // Smart Update: Nur emittieren, wenn sich wirklich was geändert hat
            _uiState.update { current ->
                if (current.timeString == newTimeString && current.dateString == newDateString) {
                    current
                } else {
                    current.copy(timeString = newTimeString, dateString = newDateString)
                }
            }
        } catch (e: Throwable) {
            TimberWrapper.silentError(e, "Failed to update time and date")
            // Fallback
            if (_uiState.value.timeString == DEFAULT_TIME) {
                _uiState.update { it.copy(timeString = DEFAULT_TIME, dateString = DEFAULT_DATE) }
            }
        }
    }

    // --- PUBLIC FUNCTIONS CALLED FROM FRAGMENTS ---

    fun onFlingUp() = launchSafe {
        sendEvent(UiEvent.ShowAppDrawer)
    }

    fun onFlingDown() = launchSafe {
        when (requestNotificationsUseCase()) {

            is RequestNotificationsUseCase.Result.Success -> {
            }

            is RequestNotificationsUseCase.Result.ErrorAccessibility -> {
                sendEvent(UiEvent.ShowAccessibilityDialog)
            }

            is RequestNotificationsUseCase.Result.ErrorDisabled -> {
                if (!enableSwipeDownToastShown) {
                    enableSwipeDownToastShown = true
                    sendEvent(UiEvent.ShowToast(R.string.toast_enable_swipe_down_to_notifications))
                }
            }

            is RequestNotificationsUseCase.Result.ErrorGeneric -> {
                sendEvent(UiEvent.ShowToast(R.string.error_generic))
            }
        }
    }

    // Swipe nach LINKS → App vom RECHTEN Rand (ich "ziehe" von rechts herein)
    fun onSwipeFromRightToLeft() = launchSafe {
        try {
            when (val result = handleSwipeActionUseCase(SwipeSlot.SWIPE_FROM_RIGHT_TO_LEFT)) {
                is HandleSwipeActionUseCase.Result.LaunchApp -> {
                    sendEvent(UiEvent.LaunchApp(result.app))
                }

                is HandleSwipeActionUseCase.Result.NoAction -> {
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            TimberWrapper.silentError(e, "Error in onSwipeTowardsLeft")
        }
    }

    // Swipe nach RECHTS → App vom LINKEN Rand (ich "ziehe" von links herein)
    fun onSwipeFromLeftToRight() = launchSafe {
        try {
            when (val result = handleSwipeActionUseCase(SwipeSlot.SWIPE_FROM_LEFT_TO_RIGHT)) {
                is HandleSwipeActionUseCase.Result.LaunchApp -> {
                    sendEvent(UiEvent.LaunchApp(result.app))
                }

                is HandleSwipeActionUseCase.Result.NoAction -> {
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            TimberWrapper.silentError(e, "Error in onSwipeTowardsRight")
        }
    }

    fun onLongPress() = launchSafe {
        sendEvent(UiEvent.ShowCustomizationOptions)
    }

    fun onTimeDoubleClick() = launchSafe {
        sendEvent(UiEvent.OpenClock)
    }

    fun onDateDoubleClick() = launchSafe {
        sendEvent(UiEvent.OpenCalendar)
    }

    fun onBatteryDoubleClick() = launchSafe {
        sendEvent(UiEvent.OpenBatterySettings)
    }

    fun onDoubleTapToLock() = launchSafe {
        when (requestLockUseCase()) {

            is RequestLockUseCase.Result.Success -> {
                _isLockingInProgress.value = true
                delay(AppConstants.LOCK_GESTURE_BLOCK_DURATION_MS)
                _isLockingInProgress.value = false
            }

            is RequestLockUseCase.Result.ErrorAccessibility -> {
                sendEvent(UiEvent.ShowAccessibilityDialog)
            }

            is RequestLockUseCase.Result.ErrorDisabled -> {
                if (!enableLockToastShown) {
                    enableLockToastShown = true
                    sendEvent(UiEvent.ShowToast(R.string.toast_enable_double_tap_to_lock))
                }
            }

            is RequestLockUseCase.Result.ErrorGeneric -> {
            }
        }
    }

    fun onToggleFavorite(app: AppInfo) = launchSafe {
        try {
            // 1. Hole den Wert, den der UseCase braucht
            val currentMax = maxFavoritesOnHome.value

            // 2. Delegiere die Arbeit UND erhalte ein klares Ergebnis
            when (val result = toggleFavoriteUseCase(app, currentMax)) {

                is ToggleFavoriteUseCase.Result.Success -> {
                    // VM ist nur für den Toast zuständig
                    val message = context.getString(result.messageResId, app.displayName)
                    sendEvent(UiEvent.ShowToastFromString(message))
                }

                is ToggleFavoriteUseCase.Result.Error -> {
                    // VM ist nur für den Toast zuständig
                    // (Das VM weiß, wie es die Argumente für den Toast füllen muss)
                    val message = context.getString(result.messageResId, currentMax)
                    sendEvent(UiEvent.ShowToastFromString(message))
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            // Generische Fehlerbehandlung bleibt im VM
            TimberWrapper.silentError(e, "Error toggling favorite for ${app.packageName}")
            sendEvent(UiEvent.ShowToast(R.string.error_generic))
        }
    }

    fun onHideApp(app: AppInfo) = launchSafe {
        try {
            hideAppUseCase(app)
            sendEvent(
                UiEvent.ShowToastFromString(
                    context.getString(R.string.app_now_hidden_in_drawer, app.displayName)
                )
            )
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            TimberWrapper.silentError(e, "Error hiding app ${app.packageName}")
            sendEvent(UiEvent.ShowToast(R.string.error_generic))
        }
    }

    fun onResetAppUsage(app: AppInfo) = launchSafe {
        try {
            resetAppUsageUseCase(app)
            sendEvent(
                UiEvent.ShowToastFromString(
                    context.getString(R.string.usage_data_reset_success, app.displayName)
                )
            )
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            TimberWrapper.silentError(e, "Error resetting usage data for ${app.packageName}")
            sendEvent(UiEvent.ShowToast(R.string.error_generic))
        }
    }

    fun onAppClicked(app: AppInfo) = launchSafe {
        try {
            // 1. UI-Event senden
            sendEvent(UiEvent.LaunchApp(app))

            // 2. Statistik aufzeichnen (OHNE try-catch!)
            // Wenn das hier fehlschlägt, springt der Code sofort in den catch-Block unten.
            recordAppLaunchUseCase(app)

            // 3. Refresh (Wird nur erreicht, wenn Schritt 2 erfolgreich war)
            refreshAppsUseCase()

        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            // Hier landet der Fehler aus recordAppLaunchUseCase
            TimberWrapper.silentError(e, "Error handling app click")

            // Und HIER wird der Toast gesendet, auf den dein Test wartet!
            sendEvent(UiEvent.ShowToast(R.string.error_launching_app))
        }
    }

    fun onShowApp(app: AppInfo) = launchSafe {
        try {
            showAppUseCase(app)
            sendEvent(
                UiEvent.ShowToastFromString(
                    context.getString(R.string.app_now_visible_in_drawer, app.displayName)
                )
            )
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            TimberWrapper.silentError(e, "Failed to show app ${app.packageName}")
            sendEvent(UiEvent.ShowToast(R.string.error_generic))
        }
    }

    fun onSetLayoutScale(scale: Float) = launchSafe {
        setLayoutScaleUseCase(
            scale.coerceInSafe(
                AppConstants.LAYOUT_SCALE_MIN,
                AppConstants.LAYOUT_SCALE_MAX
            )
        )
    }

    fun onSetVerticalPadding(factor: Float) = launchSafe {
        setVerticalPaddingUseCase(
            factor.coerceInSafe(
                AppConstants.VERTICAL_PADDING_SCALE_MIN,
                AppConstants.VERTICAL_PADDING_SCALE_MAX
            )
        )
    }

    fun onSetFontBold(isBold: Boolean) = launchSafe {
        setFontBoldUseCase(isBold)
    }

    fun onSetContentTopMargin(scale: Float) = launchSafe {
        setContentTopMarginUseCase(
            scale.coerceInSafe(
                AppConstants.CONTENT_TOP_MARGIN_SCALE_MIN,
                AppConstants.CONTENT_TOP_MARGIN_SCALE_MAX
            )
        )
    }

    fun onResetLayoutSettings() = launchSafe {
        setLayoutScaleUseCase(AppConstants.DEFAULT_LAYOUT_SCALE)
        setVerticalPaddingUseCase(AppConstants.DEFAULT_VERTICAL_PADDING_FACTOR)
        setFontBoldUseCase(AppConstants.DEFAULT_FONT_BOLD)
        setContentTopMarginUseCase(AppConstants.DEFAULT_TOP_MARGIN)
    }

    fun onAppInfoError() = launchSafe {
        sendEvent(UiEvent.ShowToast(R.string.error_app_info_open))
    }

    fun onFavoriteAppsError(message: String) = launchSafe {
        sendEvent(UiEvent.ShowToastFromString(message))
    }

    fun toggleSortOrder() = launchSafe {
        try {
            toggleSortOrderUseCase()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            TimberWrapper.silentError(e, "Error toggling sort order")
            sendEvent(UiEvent.ShowToast(R.string.error_generic))
        }
    }

    fun refreshDynamicUiData() {
        updateTimeAndDate()
        getInitialBatteryState()
        observeTimeBasedEventsUseCase.refresh()
    }

    fun refreshAllData() {
        refreshDynamicUiData()
        refreshInstalledApps()
    }

    fun onSetTextColor(color: Int) = launchSafe {
        try {
            setTextColorUseCase(color)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            TimberWrapper.silentError(e, "Error setting text color")
            sendEvent(UiEvent.ShowToast(R.string.error_generic))
        }
    }

    fun onSetTextShadowEnabled(isEnabled: Boolean) = launchSafe {
        try {
            setTextShadowEnabledUseCase(isEnabled)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            TimberWrapper.silentError(e, "Error setting text shadow")
            sendEvent(UiEvent.ShowToast(R.string.error_generic))
        }
    }

    fun onSetChipBackgroundColor(color: Int) = launchSafe {
        try {
            setChipBackgroundColorUseCase(color)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            TimberWrapper.silentError(e, "Error setting chip background color")
            sendEvent(UiEvent.ShowToast(R.string.error_generic))
        }
    }

    /**
     * Stellt die 'autoLaunchApp'-Einstellung für Fragments bereit.
     * Dies ist eine saubere Kapselung der Geschäftslogik.
     */
    suspend fun isAutoLaunchEnabled(): Boolean {
        return getAutoLaunchSettingUseCase()
    }

    /**
     * Stellt die 'textShadowEnabled'-Einstellung für Fragments bereit.
     */
    suspend fun isTextShadowEnabled(): Boolean {
        return getTextShadowEnabledUseCase()
    }

    /**
     * Wird vom AppDrawerFragment aufgerufen, wenn sich der Suchtext ändert.
     */
    fun onAppDrawerSearchQueryChanged(query: String) {
        // Speichere einfach den rohen Text. Das Fragment kümmert sich um Debouncing.
        // Wir schreiben direkt in den Handle. Der StateFlow oben aktualisiert sich automatisch!
        savedStateHandle[AppConstants.KEY_SEARCH_QUERY] = query
    }

    /**
     * Wird vom AppDrawerFragment aufgerufen, wenn es geschlossen wird,
     * um die Suchanfrage zurückzusetzen.
     */
    fun onAppDrawerClosed() {
        savedStateHandle[AppConstants.KEY_SEARCH_QUERY] = ""
    }


    // ===========================================
    // WALLPAPER FUNCTIONS
    // ===========================================

    /**
     * Setzt ein neues Wallpaper-Bild.
     * Wird aufgerufen, nachdem der User ein Bild aus der Galerie gewählt hat.
     */
    fun onSetWallpaperImage(imageUri: Uri) = launchSafe {
        try {
            // Dateinamen VOR dem Kopieren extrahieren
            val displayName = getDisplayName(imageUri)

            // Bild in internen Speicher kopieren (überlebt Reinstall)
            val internalUri = wallpaperFileManager.copyToInternal(imageUri)
            if (internalUri == null) {
                TimberWrapper.silentError("Failed to copy wallpaper to internal storage")
                sendEvent(UiEvent.ShowToast(R.string.error_generic))
                return@launchSafe
            }
            setWallpaperImageUseCase(internalUri)

            // Toast mit Dateinamen
            val message = displayName ?: context.getString(R.string.wallpaper_set_success)
            sendEvent(UiEvent.ShowToastFromString(message))
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            TimberWrapper.silentError(e, "Error setting wallpaper image")
            sendEvent(UiEvent.ShowToast(R.string.error_generic))
        }
    }

    private fun getDisplayName(uri: Uri): String? {
        return try {
            context.contentResolver.query(uri, arrayOf(android.provider.OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
                if (cursor.moveToFirst()) {
                    cursor.getString(0)
                } else null
            }
        } catch (e: Throwable) {
            null
        }
    }

    /**
     * Speichert die aktuelle Wallpaper-Transformation (Zoom/Pan).
     * Wird aufgerufen, wenn der User den Edit-Mode verlässt.
     */
    fun onSaveWallpaperTransform(scale: Float, translateX: Float, translateY: Float) = launchSafe {
        try {
            val currentState = _wallpaperState.value
            if (currentState.hasWallpaper) {
                saveWallpaperStateUseCase.updateTransform(
                    currentState = currentState,
                    scale = scale,
                    translateX = translateX,
                    translateY = translateY
                )
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            TimberWrapper.silentError(e, "Error saving wallpaper transform")
        }
    }

    /**
     * Entfernt das Custom Wallpaper.
     */
    fun onClearWallpaper() = launchSafe {
        try {
            wallpaperFileManager.clearAll()
            clearWallpaperUseCase()
            sendEvent(UiEvent.ShowToast(R.string.wallpaper_removed))
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            TimberWrapper.silentError(e, "Error clearing wallpaper")
            sendEvent(UiEvent.ShowToast(R.string.error_generic))
        }
    }

    /**
     * Aktiviert/Deaktiviert den Wallpaper Edit-Mode.
     */
    fun onSetWallpaperEditMode(enabled: Boolean) {
        _isWallpaperEditMode.value = enabled
    }

    /**
     * Toggle für den Edit-Mode (für Menu-Button).
     */
    fun onToggleWallpaperEditMode() {
        _isWallpaperEditMode.value = !_isWallpaperEditMode.value
    }

    // =============================================================================
// WALLPAPER MULTI-LAYER METHODS
// Die bestehenden Methoden (onSetWallpaperImage, onSaveWallpaperTransform,
// onClearWallpaper, onSetWallpaperEditMode, onToggleWallpaperEditMode)
// bleiben UNVERÄNDERT – sie funktionieren weiter für Single-Layer.
// =============================================================================

    // --- LAYER MANAGEMENT ---

    /**
     * Fügt ein neues Layer hinzu.
     * Beim ersten Aufruf: Bestehender Single-Layer wird automatisch migriert.
     */
    fun onAddWallpaperLayer(imageUri: Uri, label: String? = null) = launchSafe {
        try {
            // Bild in internen Speicher kopieren (überlebt Reinstall)
            val internalUri = wallpaperFileManager.copyToInternal(imageUri)
            if (internalUri == null) {
                TimberWrapper.silentError("Failed to copy layer image to internal storage")
                return@launchSafe
            }

            val current = _wallpaperState.value

            // Migration: Single → Multi beim ersten addLayer
            val base = if (!current.isMultiLayer && current.hasWallpaper) {
                current.toMultiLayer()
            } else {
                current
            }

            val newLayer = WallpaperLayerState(
                imageUri = internalUri,
                label = label ?: "Layer ${base.layerCount + 1}"
            )

            val newState = base.withAddedLayer(newLayer)
            _wallpaperState.value = newState
            saveWallpaperStateUseCase(newState.forPersistence())
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            TimberWrapper.silentError(e, "Error adding wallpaper layer")
        }
    }

    /**
     * Entfernt ein Layer. Wenn das letzte Layer entfernt wird,
     * wird der Wallpaper-State komplett geleert.
     */
    fun onRemoveWallpaperLayer(layerIndex: Int) = launchSafe {
        try {
            val current = _wallpaperState.value

            // Interne Datei des entfernten Layers löschen
            current.getLayer(layerIndex)?.imageUri?.let { uri ->
                wallpaperFileManager.deleteFile(uri)
            }

            val newState = current.withRemovedLayer(layerIndex)

            // Letztes Layer entfernt? → Clear
            if (newState.layers.isEmpty() && !newState.hasWallpaper) {
                clearWallpaperUseCase()
            } else {
                _wallpaperState.value = newState
                saveWallpaperStateUseCase(newState.forPersistence())
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            TimberWrapper.silentError(e, "Error removing wallpaper layer")
        }
    }

    /**
     * Tauscht die Z-Order zweier Layer.
     */
    fun onSwapWallpaperLayers(indexA: Int, indexB: Int) = launchSafe {
        try {
            val newState = _wallpaperState.value.withSwappedLayers(indexA, indexB)
            _wallpaperState.value = newState
            saveWallpaperStateUseCase(newState.forPersistence())
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            TimberWrapper.silentError(e, "Error swapping wallpaper layers")
        }
    }

    // --- LAYER TRANSFORM ---

    /**
     * Speichert die Transformation eines bestimmten Layers.
     * Wird aufgerufen wenn der User im Edit-Mode "Save" drückt.
     */
    fun onSaveLayerTransform(
        layerIndex: Int,
        scale: Float,
        translateX: Float,
        translateY: Float
    ) = launchSafe {
        try {
            val newState = _wallpaperState.value.withUpdatedLayer(layerIndex) {
                it.copy(scale = scale, translateX = translateX, translateY = translateY)
            }
            _wallpaperState.value = newState
            saveWallpaperStateUseCase(newState.forPersistence())
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            TimberWrapper.silentError(e, "Error saving layer transform")
        }
    }

    /**
     * Speichert ALLE Layer-Transforms auf einmal.
     * Nützlich wenn der User mehrere Layer bearbeitet hat.
     */
    fun onSaveAllLayerTransforms(
        transforms: List<Triple<Float, Float, Float>>  // (scale, tx, ty) pro Layer
    ) = launchSafe {
        try {
            var state = _wallpaperState.value
            transforms.forEachIndexed { index, (scale, tx, ty) ->
                state = state.withUpdatedLayer(index) {
                    it.copy(scale = scale, translateX = tx, translateY = ty)
                }
            }
            _wallpaperState.value = state
            saveWallpaperStateUseCase(state.forPersistence())
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            TimberWrapper.silentError(e, "Error saving all layer transforms")
        }
    }

    // --- LAYER PROPERTIES ---

    /**
     * Setzt die Deckkraft eines Layers.
     */
    fun onSetLayerAlpha(layerIndex: Int, alpha: Float) = launchSafe {
        try {
            val newState = _wallpaperState.value.withUpdatedLayer(layerIndex) {
                it.copy(alpha = alpha.coerceIn(0f, 1f))
            }
            _wallpaperState.value = newState
            saveWallpaperStateUseCase(newState.forPersistence())
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            TimberWrapper.silentError(e, "Error setting layer alpha")
        }
    }

    /**
     * Setzt den Blend-Modus eines Layers.
     * @param blendModeName Name des BlendMode (z.B. "MULTIPLY") oder null für Normal
     */
    fun onSetLayerBlendMode(layerIndex: Int, blendModeName: String?) = launchSafe {
        try {
            val newState = _wallpaperState.value.withUpdatedLayer(layerIndex) {
                it.copy(blendModeName = blendModeName)
            }
            _wallpaperState.value = newState
            saveWallpaperStateUseCase(newState.forPersistence())
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            TimberWrapper.silentError(e, "Error setting layer blend mode")
        }
    }

    /**
     * Setzt die Sichtbarkeit eines Layers.
     */
    fun onSetLayerVisibility(layerIndex: Int, isVisible: Boolean) = launchSafe {
        try {
            val newState = _wallpaperState.value.withUpdatedLayer(layerIndex) {
                it.copy(isVisible = isVisible)
            }
            _wallpaperState.value = newState
            saveWallpaperStateUseCase(newState.forPersistence())
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            TimberWrapper.silentError(e, "Error setting layer visibility")
        }
    }

    // --- PRIVATE/INTERNAL LOGIC ---

    fun refreshInstalledApps() = launchSafe {
        try {
            refreshAppsUseCase()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            TimberWrapper.silentError(e, "Error refreshing installed apps")
        }
    }

    private fun listenForAppUpdates() = launchSafe {
        try {
            appUpdateSignal.events.collect {
                try {
                    refreshAppsUseCase()
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Throwable) {
                    TimberWrapper.silentError(e, "Error refreshing apps on update signal")
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            TimberWrapper.silentError(e, "Error listening for app updates")
        }
    }

    fun getInitialBatteryState() {
        try {
            val batteryIntent = context.registerReceiver(
                null,
                IntentFilter(Intent.ACTION_BATTERY_CHANGED),
                Context.RECEIVER_NOT_EXPORTED
            )
            updateBatteryLevelFromIntent(batteryIntent)
        } catch (e: Throwable) {
            TimberWrapper.silentError(e, "Failed to register battery receiver")
            // Safe fallback
            _uiState.update {
                it.copy(batteryString = DEFAULT_BATTERY)
            }
        }
    }

    fun updateBatteryLevelFromIntent(intent: Intent?) {
        try {
            if (intent != null) {
                val level = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
                val scale = intent.getIntExtra(BatteryManager.EXTRA_SCALE, -1)
                updateBatteryLevel(level, scale)
            } else {
                _uiState.update {
                    it.copy(batteryString = DEFAULT_BATTERY)
                }
            }
        } catch (e: Throwable) {
            TimberWrapper.silentError(e, "Failed to update battery level from intent")
            _uiState.update {
                it.copy(batteryString = DEFAULT_BATTERY)
            }
        }
    }

    fun updateBatteryLevel(level: Int, scale: Int) {
        try {
            if (level != -1 && scale != -1 && scale > 0) {
                val batteryPercent = (level.toLong() * 100 / scale).toInt()
                _uiState.update { it.copy(batteryString = "${batteryPercent}%") }
            } else {
                _uiState.update { it.copy(batteryString = DEFAULT_BATTERY) }
            }
        } catch (e: Throwable) {
            TimberWrapper.silentError(e, "Failed to calculate battery level")
            _uiState.update { it.copy(batteryString = DEFAULT_BATTERY) }
        }
    }

    fun updateUiColors(wallpaperColors: WallpaperColors? = null) {
        wallpaperColorsFlow.value = wallpaperColors
    }

    suspend fun hasUsageData(packageName: String?): Boolean {
        return checkAppUsageUseCase(packageName)
    }

    suspend fun isAutoShowKeyboardEnabled(): Boolean {
        return getAutoShowKeyboardSettingUseCase()
    }
}
