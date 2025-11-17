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
import android.graphics.Color
import android.os.BatteryManager
import android.text.format.DateFormat
import androidx.lifecycle.LiveData
import androidx.lifecycle.asLiveData
import androidx.lifecycle.viewModelScope
import com.github.reygnn.kolibri_launcher.R
import com.github.reygnn.kolibri_launcher.core.AppConstants
import com.github.reygnn.kolibri_launcher.core.TimberWrapper
import com.github.reygnn.kolibri_launcher.domain.model.AppInfo
import com.github.reygnn.kolibri_launcher.domain.model.FavoriteAppsResult
import com.github.reygnn.kolibri_launcher.di.MainDispatcher
import com.github.reygnn.kolibri_launcher.domain.model.SortOrder
import com.github.reygnn.kolibri_launcher.domain.model.TimeBasedEvent
import com.github.reygnn.kolibri_launcher.domain.model.UiColorsState
import com.github.reygnn.kolibri_launcher.domain.usecase.AppLoadResult
import com.github.reygnn.kolibri_launcher.domain.usecase.CheckAppUsageUseCase
import com.github.reygnn.kolibri_launcher.domain.usecase.GetAutoLaunchSettingUseCase
import com.github.reygnn.kolibri_launcher.domain.usecase.GetAutoShowKeyboardSettingUseCase
import com.github.reygnn.kolibri_launcher.domain.usecase.GetDrawerAppsUseCase
import com.github.reygnn.kolibri_launcher.domain.usecase.GetFavoriteAppsUseCase
import com.github.reygnn.kolibri_launcher.domain.usecase.GetTextShadowEnabledUseCase
import com.github.reygnn.kolibri_launcher.domain.usecase.HandleSwipeActionUseCase
import com.github.reygnn.kolibri_launcher.domain.usecase.HideAppUseCase
import com.github.reygnn.kolibri_launcher.domain.model.HomeSettings
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
import com.github.reygnn.kolibri_launcher.domain.usecase.SetTextColorUseCase
import com.github.reygnn.kolibri_launcher.domain.usecase.SetTextShadowEnabledUseCase
import com.github.reygnn.kolibri_launcher.domain.usecase.ShowAppUseCase
import com.github.reygnn.kolibri_launcher.domain.usecase.ToggleFavoriteUseCase
import com.github.reygnn.kolibri_launcher.domain.usecase.ToggleSortOrderUseCase
import com.github.reygnn.kolibri_launcher.ui.swipeactions.SwipeSlot
import com.github.reygnn.kolibri_launcher.ui.base.UiEvent
import com.github.reygnn.kolibri_launcher.ui.base.UiState
import com.github.reygnn.kolibri_launcher.ui.base.BaseViewModel
import com.github.reygnn.kolibri_launcher.ui.util.AppUpdateSignal
import com.github.reygnn.kolibri_launcher.ui.util.TestMode
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale
import javax.inject.Inject

data class HomeUiState(
    val timeString: String = "",
    val dateString: String = "",
    val batteryString: String = "",
    val timeBasedEvents: List<TimeBasedEvent> = emptyList()
)
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

    private val appUpdateSignal: AppUpdateSignal,
    @param:ApplicationContext private val context: Context,
    @MainDispatcher mainDispatcher: CoroutineDispatcher,
    private val testMode: TestMode
) : BaseViewModel<UiEvent>(mainDispatcher) {

    private val _uiState = MutableStateFlow(HomeUiState())
    val uiState: StateFlow<HomeUiState> = _uiState.asStateFlow()

    private val _uiColorsState = MutableStateFlow(UiColorsState())
    val uiColorsState: StateFlow<UiColorsState> = _uiColorsState.asStateFlow()

    private val _maxFavoritesOnHome = MutableStateFlow(AppConstants.MAX_FAVORITES_ON_HOME)
    val maxFavoritesOnHome: StateFlow<Int> = _maxFavoritesOnHome.asStateFlow()

    private val _homeSettings = MutableStateFlow(HomeSettings())
    val sortOrder: LiveData<SortOrder> = _homeSettings
        .map { it.sortOrder }
        .asLiveData(viewModelScope.coroutineContext)

    private val _favoriteAppsState = MutableStateFlow<UiState<FavoriteAppsResult>>(UiState.Loading)
    val favoriteAppsState: StateFlow<UiState<FavoriteAppsResult>> = _favoriteAppsState.asStateFlow()

    private val wallpaperColorsFlow = MutableStateFlow<WallpaperColors?>(null)

    private val _appDrawerSearchQuery = MutableStateFlow("")
    val appDrawerSearchQuery: StateFlow<String> = _appDrawerSearchQuery.asStateFlow()

    val drawerApps: LiveData<List<AppInfo>> = getDrawerAppsUseCase.drawerApps

    private var fallbackToastShown = false
    private var enableLockToastShown = false
    private var enableSwipeDownToastShown = false

    companion object {
        private const val DEFAULT_TEXT_COLOR = Color.WHITE
        private const val DEFAULT_SHADOW_COLOR = Color.BLACK
        private const val DEFAULT_CHIP_BG_COLOR = 0
        private const val DEFAULT_TIME = "--:--"
        private const val DEFAULT_DATE = "---"
        private const val DEFAULT_BATTERY = "---%"
    }

    init {
        // Initialize with safe defaults
        updateTimeAndDate()
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
                    delay(100) // Kleiner Start-Delay
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

            // 5. EIGENE Coroutine für Favoriten (Endlich wird sie gestartet!)
            launchSafe {
                try {
                    getFavoriteAppsUseCase.favoriteApps.collect { state ->
                        try {
                            _favoriteAppsState.value = state
                            if (state is UiState.Success && state.data.isFallback && !fallbackToastShown) {
                                fallbackToastShown = true
                                sendEvent(UiEvent.ShowToast(R.string.welcome_toast_fallback_favorites))
                            }
                        } catch (e: CancellationException) {
                            throw e
                        } catch (e: Throwable) {
                            TimberWrapper.silentError(e, "Error processing favorite apps state")
                        }
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Throwable) {
                    TimberWrapper.silentError(e, "Error observing favorite apps")
                }
            }

            // 6. App-Updates Listener starten (startet intern auch eine Coroutine via launchSafe)
            listenForAppUpdates()

        } else {
            // Test Mode: Nur Favoriten
            launchSafe {
                try {
                    getFavoriteAppsUseCase.favoriteApps.collect { state ->
                        _favoriteAppsState.value = state
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Throwable) {
                    TimberWrapper.silentError(e, "Error observing favorite apps in test mode")
                }
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

    fun onFlingLeft() = launchSafe {
        try {
            when (val result = handleSwipeActionUseCase(SwipeSlot.LEFT)) {
                is HandleSwipeActionUseCase.Result.LaunchApp -> {
                    sendEvent(UiEvent.LaunchApp(result.app))
                }
                is HandleSwipeActionUseCase.Result.NoAction -> {
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            TimberWrapper.silentError(e, "Error in onFlingLeft")
        }
    }

    fun onFlingRight() = launchSafe {
        try {
            when (val result = handleSwipeActionUseCase(SwipeSlot.RIGHT)) {
                is HandleSwipeActionUseCase.Result.LaunchApp -> {
                    sendEvent(UiEvent.LaunchApp(result.app))
                }
                is HandleSwipeActionUseCase.Result.NoAction -> {
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            TimberWrapper.silentError(e, "Error in onFlingRight")
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
            sendEvent(UiEvent.LaunchApp(app))

            recordAppLaunchUseCase(app)
            refreshAppsUseCase()

        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            TimberWrapper.silentError(e, "Error handling app click for ${app.packageName}")
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

    fun updateTimeAndDate() {
        try {
            val currentTime = Calendar.getInstance().time
            val is24Hour = DateFormat.is24HourFormat(context)
            val timePattern = if (is24Hour) "HH:mm" else "h:mm a"
            val timeFormat = SimpleDateFormat(timePattern, Locale.getDefault())
            val dateFormat = SimpleDateFormat("E, d MMM", Locale.getDefault())

            _uiState.update {
                it.copy(
                    timeString = timeFormat.format(currentTime),
                    dateString = dateFormat.format(currentTime)
                )
            }
        } catch (e: Throwable) {
            TimberWrapper.silentError(e, "Failed to update time and date")
            // Safe fallback
            _uiState.update {
                it.copy(
                    timeString = DEFAULT_TIME,
                    dateString = DEFAULT_DATE
                )
            }
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
            intent?.let {
                val level = it.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
                val scale = it.getIntExtra(BatteryManager.EXTRA_SCALE, -1)
                updateBatteryLevel(level, scale)
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
                val batteryPercent = (level * 100 / scale)
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

    fun onHomeViewMeasured(calculatedMaxFavorites: Int) {
        if (calculatedMaxFavorites > 0) {
            _maxFavoritesOnHome.value = calculatedMaxFavorites
            getFavoriteAppsUseCase.setDynamicMaxFavorites(calculatedMaxFavorites)
        }
    }

    suspend fun isAutoShowKeyboardEnabled(): Boolean {
        return getAutoShowKeyboardSettingUseCase()
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
        _appDrawerSearchQuery.value = query
    }

    /**
     * Wird vom AppDrawerFragment aufgerufen, wenn es geschlossen wird,
     * um die Suchanfrage zurückzusetzen.
     */
    fun onAppDrawerClosed() {
        _appDrawerSearchQuery.value = ""
    }
}