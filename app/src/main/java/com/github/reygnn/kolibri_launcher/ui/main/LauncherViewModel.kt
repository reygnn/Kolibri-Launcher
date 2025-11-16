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
import androidx.core.graphics.ColorUtils
import androidx.lifecycle.LiveData
import androidx.lifecycle.asLiveData
import androidx.lifecycle.viewModelScope
import com.github.reygnn.kolibri_launcher.R
import com.github.reygnn.kolibri_launcher.core.AppConstants
import com.github.reygnn.kolibri_launcher.core.TimberWrapper
import com.github.reygnn.kolibri_launcher.domain.model.AppInfo
import com.github.reygnn.kolibri_launcher.domain.repository.AppUsageRepository
import com.github.reygnn.kolibri_launcher.domain.model.FavoriteAppsResult
import com.github.reygnn.kolibri_launcher.domain.repository.FavoritesRepository
import com.github.reygnn.kolibri_launcher.domain.repository.HiddenAppsRepository
import com.github.reygnn.kolibri_launcher.domain.repository.InstalledAppsRepository
import com.github.reygnn.kolibri_launcher.domain.repository.InstalledAppsStateRepository
import com.github.reygnn.kolibri_launcher.domain.repository.ScreenLockRepository
import com.github.reygnn.kolibri_launcher.domain.repository.SettingsRepository
import com.github.reygnn.kolibri_launcher.domain.repository.SwipeActionsRepository
import com.github.reygnn.kolibri_launcher.domain.repository.TimeBasedEventsRepository
import com.github.reygnn.kolibri_launcher.di.MainDispatcher
import com.github.reygnn.kolibri_launcher.domain.repository.GetDrawerAppsUseCaseRepository
import com.github.reygnn.kolibri_launcher.domain.repository.GetFavoriteAppsUseCaseRepository
import com.github.reygnn.kolibri_launcher.domain.model.SortOrder
import com.github.reygnn.kolibri_launcher.domain.model.TimeBasedEvent
import com.github.reygnn.kolibri_launcher.domain.usecase.AppLoadResult
import com.github.reygnn.kolibri_launcher.domain.usecase.GetDrawerAppsUseCase
import com.github.reygnn.kolibri_launcher.domain.usecase.GetFavoriteAppsUseCase
import com.github.reygnn.kolibri_launcher.domain.usecase.HandleSwipeActionUseCase
import com.github.reygnn.kolibri_launcher.domain.usecase.HideAppUseCase
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
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.retry
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import timber.log.Timber
import java.io.IOException
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

    private val _favoriteAppsState = MutableStateFlow<UiState<FavoriteAppsResult>>(UiState.Loading)
    val favoriteAppsState: StateFlow<UiState<FavoriteAppsResult>> = _favoriteAppsState.asStateFlow()

    private val wallpaperColorsFlow = MutableStateFlow<WallpaperColors?>(null)

    private val _appDrawerSearchQuery = MutableStateFlow("")
    val appDrawerSearchQuery: StateFlow<String> = _appDrawerSearchQuery.asStateFlow()

    val drawerApps: LiveData<List<AppInfo>> = getDrawerAppsUseCase.drawerApps
    val sortOrder: LiveData<SortOrder> = settingsManager.sortOrderFlow.asLiveData()

    private var fallbackToastShown = false
    private var enableLockToastShown = false
    private var enableSwipeDownToastShown = false

    private val swipeLeftComponent: StateFlow<String?> =
        swipeActionsManager.swipeLeftAppFlow.stateIn(
            viewModelScope,
            SharingStarted.Eagerly,
            null
        )

    private val swipeRightComponent: StateFlow<String?> =
        swipeActionsManager.swipeRightAppFlow.stateIn(
            viewModelScope,
            SharingStarted.Eagerly,
            null
        )

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

        launchSafe {
            observeTimeBasedEventsUseCase().collect { events ->
                _uiState.update { it.copy(timeBasedEvents = events) }
            }
        }

        launchSafe {
            observeUiColorsUseCase(wallpaperColorsFlow).collect { colorsState ->
                _uiColorsState.value = colorsState
            }
        }


        if (!testMode.isEnabled) {
            launchSafe {
                try {
                    delay(100)
                    // listenForAppUpdates() // (Diesen müssen wir auch noch auslagern)

                    // --- STARTET DEN "MOTOR" ---
                    // Der UseCase wird aufgerufen. Er kümmert sich um ALLES.
                    observeInstalledAppsUseCase().collect { result ->
                        // Das VM reagiert nur noch auf das Ergebnis
                        if (result is AppLoadResult.Error) {
                            sendEvent(UiEvent.ShowToast(result.messageResId))
                        }
                    }

                    // Dieser Block bleibt, da er den UI-State für Favoriten direkt verwaltet
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
                    TimberWrapper.silentError(e, "Error in init block")
                }
            }
        } else {
            // In test mode: ONLY observe favorites
            launchSafe {
                try {
                    getFavoriteAppsUseCase.favoriteApps.collect { state ->
                        _favoriteAppsState.value = state
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Throwable) {
                    TimberWrapper.silentError(e, "Error in test mode init")
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
            hideAppUseCase(app.componentName)
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

    fun refreshInstalledApps() = launchSafe {
        try {
            installedAppsManager.triggerAppsUpdate()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            TimberWrapper.silentError(e, "Error refreshing installed apps")
        }
    }

    fun refreshDynamicUiData() {
        updateTimeAndDate()
        getInitialBatteryState()
        updateCalendarEvent()
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

    // --- PRIVATE/INTERNAL LOGIC ---

    /**
     * NEU: Helper-Funktion zum Suchen der App im StateManager.
     * Greift auf die gecachte App-Liste im State Manager zu.
     */
    private fun findAppByComponentName(componentName: String): AppInfo? {
        return try {
            installedAppsStateManager.getCurrentApps().find {
                it.componentName == componentName
            }
        } catch (e: Throwable) {
            TimberWrapper.silentError(e, "Error finding app by component name")
            null
        }
    }

    private fun listenForAppUpdates() = launchSafe {
        try {
            appUpdateSignal.events.collect {
                try {
                    refreshInstalledApps()
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

    private var appLoadRetryCount = 0
    private val maxAppLoadRetries = 3

    /**
     * Observes installed apps with triple-layer error protection:
     * 1. Retry mechanism for transient errors
     * 2. Catch block with cached fallback
     * 3. Empty list protection to prevent data loss
     */
    private fun observeInstalledApps() = launchSafe {
        try {
            installedAppsManager.getInstalledApps()
                .retry(maxAppLoadRetries.toLong()) { cause ->
                    try {
                        if (cause is IOException) {
                            appLoadRetryCount++
                            Timber.w("App loading failed, retry ${appLoadRetryCount}/${maxAppLoadRetries}")
                            delay(1000L * appLoadRetryCount)
                            true
                        } else {
                            false
                        }
                    } catch (e: Throwable) {
                        TimberWrapper.silentError(e, "Error in retry logic")
                        false
                    }
                }
                .catch { e ->
                    try {
                        TimberWrapper.silentError(e, "Failed to collect installed apps")

                        val cachedApps = installedAppsStateManager.getCurrentApps()
                        if (cachedApps.isNotEmpty()) {
                            Timber.w("Using cached apps as fallback (${cachedApps.size} apps)")
                            installedAppsStateManager.updateApps(cachedApps)
                        } else {
                            installedAppsStateManager.updateApps(emptyList())
                            sendEvent(UiEvent.ShowToast(R.string.error_app_list_not_loaded))
                        }
                    } catch (catchError: Throwable) {
                        TimberWrapper.silentError(catchError, "Error in catch block")
                        // Last resort: ensure we have at least an empty list
                        try {
                            installedAppsStateManager.updateApps(emptyList())
                        } catch (lastResort: Throwable) {
                            TimberWrapper.silentError(
                                lastResort,
                                "CRITICAL: Cannot update apps state"
                            )
                        }
                    }
                }
                .collect { realApps ->
                    try {
                        if (realApps.isEmpty()) {
                            Timber.w("Collected an empty app list. Skipping cleanup to prevent data loss.")
                            installedAppsStateManager.updateApps(emptyList())
                            return@collect
                        }

                        val allValidComponentNames = realApps.map { it.componentName }

                        try {
                            favoritesManager.cleanupFavoriteComponents(allValidComponentNames)
                        } catch (cleanupError: Throwable) {
                            TimberWrapper.silentError(cleanupError, "Error cleaning up favorites")
                            // Continue anyway - cleanup failure shouldn't block app list
                        }

                        installedAppsStateManager.updateApps(realApps)
                        appLoadRetryCount = 0
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Throwable) {
                        TimberWrapper.silentError(e, "Error processing collected apps")

                        try {
                            installedAppsStateManager.updateApps(realApps)
                        } catch (updateError: CancellationException) {
                            throw updateError
                        } catch (updateError: Throwable) {
                            TimberWrapper.silentError(updateError, "CRITICAL: Cannot update apps")
                        }
                    }
                }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            TimberWrapper.silentError(e, "CRITICAL: Error in observeInstalledApps")
        }
    }

    private fun observeEventSettings() = launchSafe {
        try {
            combine(
                settingsManager.showAlarmFlow,
                settingsManager.showCalendarEventFlow
            ) { showAlarm, showCalendar ->
                Pair(showAlarm, showCalendar)
            }.collect { (showAlarm, showCalendar) ->
                try {
                    updateCalendarEvent()
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Throwable) {
                    TimberWrapper.silentError(e, "Error updating events after settings change")
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            TimberWrapper.silentError(e, "Error observing event settings")
        }
    }

    private fun resetAppUsage(app: AppInfo) = launchSafe {
        try {
            appUsageManager.removeUsageDataForPackage(app.packageName)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            TimberWrapper.silentError(e, "Error removing usage data")
            throw e  // Re-throw to let caller handle
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

    /**
     * Calculates tonal shadow color with safe math operations.
     * Protected against all math errors with safe fallbacks.
     */
    /**
     * Calculates tonal shadow color with safe math operations.
     * Protected against all math errors with safe fallbacks.
     */
    /**
     * Calculates tonal shadow color with safe math operations.
     * Only ColorUtils.calculateLuminance() can throw - math operations cannot.
     */
    private fun calculateTonalShadowColor(baseColor: Int): Int {
        return try {
            val luminance = ColorUtils.calculateLuminance(baseColor).toDouble()

            // Simple lerp function - no try-catch needed (math can't throw)
            fun lerp(start: Double, stop: Double, fraction: Double): Double {
                return (start + fraction * (stop - start)).coerceIn(0.0, 1.0)
            }

            when {
                luminance < 0.1 -> {
                    Color.argb(204, 255, 255, 255) // 80% white
                }

                luminance < 0.5 -> {
                    val fraction = ((luminance - 0.1) / 0.4).coerceIn(0.0, 1.0)
                    val alpha = lerp(0.75, 0.4, fraction)
                    Color.argb((alpha * 255).toInt().coerceIn(0, 255), 255, 255, 255)
                }

                luminance < 0.9 -> {
                    val fraction = ((luminance - 0.5) / 0.4).coerceIn(0.0, 1.0)
                    val alpha = lerp(0.3, 0.6, fraction)
                    Color.argb((alpha * 255).toInt().coerceIn(0, 255), 0, 0, 0)
                }

                else -> {
                    Color.argb(153, 0, 0, 0) // 60% black
                }
            }
        } catch (e: Throwable) {
            // Only ColorUtils.calculateLuminance() can throw here
            TimberWrapper.silentError(e, "Error calculating luminance, using default shadow")
            DEFAULT_SHADOW_COLOR
        }
    }

    fun updateCalendarEvent() = launchSafe {
        try {
            val showAlarm = settingsManager.showAlarmFlow.first()
            val showCalendar = settingsManager.showCalendarEventFlow.first()

            // Wenn beides deaktiviert ist, leere Liste zurückgeben
            if (!showAlarm && !showCalendar) {
                _uiState.update { it.copy(timeBasedEvents = emptyList()) }
                return@launchSafe
            }

            // Ansonsten lade Events - TimeBasedEventsManager prüft intern welche aktiviert sind
            val events = timeBasedEventsManager.getUpcomingTimeBasedEvents(maxCount = 5)
            _uiState.update { it.copy(timeBasedEvents = events) }

        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            TimberWrapper.silentError(e, "Failed to update time-based events")
            _uiState.update { it.copy(timeBasedEvents = emptyList()) }
        }
    }

    fun onHomeViewMeasured(calculatedMaxFavorites: Int) {
        if (calculatedMaxFavorites > 0) {
            _maxFavoritesOnHome.value = calculatedMaxFavorites
            getFavoriteAppsUseCase.setDynamicMaxFavorites(calculatedMaxFavorites)
        }
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