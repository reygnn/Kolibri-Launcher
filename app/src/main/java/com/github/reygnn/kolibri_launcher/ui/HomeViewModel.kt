/*
 * Copyright (C) 2025 reygnn (Ulrich Kaufmann)
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */

package com.github.reygnn.kolibri_launcher.ui

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
import com.github.reygnn.kolibri_launcher.data.AppInfo
import com.github.reygnn.kolibri_launcher.data.AppUsageRepository
import com.github.reygnn.kolibri_launcher.data.TimeBasedEventsRepository
import com.github.reygnn.kolibri_launcher.data.FavoriteAppsResult
import com.github.reygnn.kolibri_launcher.data.FavoritesRepository
import com.github.reygnn.kolibri_launcher.data.HiddenAppsRepository
import com.github.reygnn.kolibri_launcher.data.InstalledAppsRepository
import com.github.reygnn.kolibri_launcher.data.InstalledAppsStateRepository
import com.github.reygnn.kolibri_launcher.data.ScreenLockRepository
import com.github.reygnn.kolibri_launcher.data.SettingsRepository
import com.github.reygnn.kolibri_launcher.data.SwipeActionsRepository
import com.github.reygnn.kolibri_launcher.di.MainDispatcher
import com.github.reygnn.kolibri_launcher.domain.GetDrawerAppsUseCaseRepository
import com.github.reygnn.kolibri_launcher.domain.GetFavoriteAppsUseCaseRepository
import com.github.reygnn.kolibri_launcher.domain.SortOrder
import com.github.reygnn.kolibri_launcher.domain.TimeBasedEvent
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

data class UiColorsState(
    val textColor: Int = Color.WHITE,
    val shadowColor: Int = Color.BLACK,
    val chipBackgroundColor: Int = 0
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
class HomeViewModel @Inject constructor(
    private val installedAppsManager: InstalledAppsRepository,
    private val appUpdateSignal: AppUpdateSignal,
    private val installedAppsStateManager: InstalledAppsStateRepository,
    getFavoriteAppsUseCase: GetFavoriteAppsUseCaseRepository,
    private val getDrawerAppsUseCase: GetDrawerAppsUseCaseRepository,
    @param:ApplicationContext private val context: Context,
    private val favoritesManager: FavoritesRepository,
    val settingsManager: SettingsRepository,
    private val appUsageManager: AppUsageRepository,
    private val screenLockManager: ScreenLockRepository,
    private val appVisibilityManager: HiddenAppsRepository,
    private val swipeActionsManager: SwipeActionsRepository,
    private val timeBasedEventsManager: TimeBasedEventsRepository,
    @MainDispatcher mainDispatcher: CoroutineDispatcher,
    private val testMode: TestMode
) : BaseViewModel<UiEvent>(mainDispatcher) {

    private val _uiState = MutableStateFlow(HomeUiState())
    val uiState: StateFlow<HomeUiState> = _uiState.asStateFlow()

    private val _uiColorsState = MutableStateFlow(UiColorsState())
    val uiColorsState: StateFlow<UiColorsState> = _uiColorsState.asStateFlow()

    private val _favoriteAppsState = MutableStateFlow<UiState<FavoriteAppsResult>>(UiState.Loading)
    val favoriteAppsState: StateFlow<UiState<FavoriteAppsResult>> = _favoriteAppsState.asStateFlow()

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
        updateCalendarEvent()
        observeEventSettings()


        if (!testMode.isEnabled) {
            launchSafe {
                try {
                    delay(100)
                    observeInstalledApps()
                    listenForAppUpdates()

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
        try {
            if (settingsManager.swipeDownToNotificationsEnabledFlow.first()) {
                if (screenLockManager.isLockingAvailableFlow.value) {
                    screenLockManager.requestOpenNotifications()
                } else {
                    sendEvent(UiEvent.ShowAccessibilityDialog)
                }
            } else {
                if (!enableSwipeDownToastShown) {
                    enableSwipeDownToastShown = true
                    sendEvent(UiEvent.ShowToast(R.string.toast_enable_swipe_down_to_notifications))
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            TimberWrapper.silentError(e, "Error requesting notification panel")
            sendEvent(UiEvent.ShowToast(R.string.error_generic))
        }
    }

    fun onFlingLeft() = launchSafe {
        try {
            val comp = swipeLeftComponent.value
            if (comp == null) {
                Timber.d("No app assigned to swipe left")
                return@launchSafe
            }

            // Finde die AppInfo aus dem StateManager
            val appToLaunch = findAppByComponentName(comp)

            if (appToLaunch != null) {
                // Verwende die existierende Logik wieder!
                onAppClicked(appToLaunch)
            } else {
                Timber.w("App for swipe left not found: $comp. Clearing setting.")
                // App ist nicht (mehr) installiert, Einstellung aufräumen
                swipeActionsManager.setSwipeAction(SwipeSlot.LEFT, null)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            TimberWrapper.silentError(e, "Error in onFlingLeft")
        }
    }

    // NEU: Wird bei Rechts-Wisch aufgerufen
    fun onFlingRight() = launchSafe {
        try {
            val comp = swipeRightComponent.value
            if (comp == null) {
                Timber.d("No app assigned to swipe right")
                return@launchSafe
            }

            // Finde die AppInfo aus dem StateManager
            val appToLaunch = findAppByComponentName(comp)

            if (appToLaunch != null) {
                // Verwende die existierende Logik wieder!
                onAppClicked(appToLaunch)
            } else {
                Timber.w("App for swipe right not found: $comp. Clearing setting.")
                // App ist nicht (mehr) installiert, Einstellung aufräumen
                swipeActionsManager.setSwipeAction(SwipeSlot.RIGHT, null)
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
        try {
            if (settingsManager.doubleTapToLockEnabledFlow.first()) {
                if (screenLockManager.isLockingAvailableFlow.value) {
                    screenLockManager.requestLock()
                } else {
                    sendEvent(UiEvent.ShowAccessibilityDialog)
                }
            } else {
                if (!enableLockToastShown) {
                    enableLockToastShown = true
                    sendEvent(UiEvent.ShowToast(R.string.toast_enable_double_tap_to_lock))
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            TimberWrapper.silentError(e, "Error during double tap to lock action")
        }
    }

    fun onToggleFavorite(app: AppInfo, currentFavoritesCount: Int) = launchSafe {
        try {
            if (!favoritesManager.isFavoriteComponent(app.componentName) &&
                currentFavoritesCount >= AppConstants.MAX_FAVORITES_ON_HOME
            ) {
                val message = context.getString(
                    R.string.favorites_limit_reached,
                    AppConstants.MAX_FAVORITES_ON_HOME
                )
                sendEvent(UiEvent.ShowToastFromString(message))
                return@launchSafe
            }

            val wasAdded = favoritesManager.toggleFavoriteComponent(app.componentName)
            val messageResId = if (wasAdded) {
                R.string.app_added_to_favorites
            } else {
                R.string.app_removed_from_favorites
            }

            sendEvent(
                UiEvent.ShowToastFromString(
                    context.getString(messageResId, app.displayName)
                )
            )
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            TimberWrapper.silentError(e, "Error toggling favorite for ${app.packageName}")
            sendEvent(UiEvent.ShowToast(R.string.error_generic))
        }
    }

    fun onHideApp(app: AppInfo) = launchSafe {
        try {
            appVisibilityManager.hideComponent(app.componentName)
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
            resetAppUsage(app)
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
            appUsageManager.recordPackageLaunch(app.packageName)
            refreshInstalledApps()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            TimberWrapper.silentError(e, "Error handling app click for ${app.packageName}")
            sendEvent(UiEvent.ShowToast(R.string.error_launching_app))
        }
    }

    fun onShowApp(app: AppInfo) = launchSafe {
        try {
            appVisibilityManager.showComponent(app.componentName)
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
            val newOrder = if (settingsManager.sortOrderFlow.first() == SortOrder.ALPHABETICAL) {
                SortOrder.TIME_WEIGHTED_USAGE
            } else {
                SortOrder.ALPHABETICAL
            }
            settingsManager.setSortOrder(newOrder)
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
            settingsManager.setTextColor(color)
            updateUiColors()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            TimberWrapper.silentError(e, "Error setting text color")
            sendEvent(UiEvent.ShowToast(R.string.error_generic))
        }
    }

    fun onSetTextShadowEnabled(isEnabled: Boolean) = launchSafe {
        try {
            settingsManager.setTextShadowEnabled(isEnabled)
            updateUiColors()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            TimberWrapper.silentError(e, "Error setting text shadow")
            sendEvent(UiEvent.ShowToast(R.string.error_generic))
        }
    }

    fun onSetChipBackgroundColor(color: Int) = launchSafe {
        try {
            settingsManager.setChipBackgroundColor(color)
            updateUiColors()
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

    fun updateUiColors(wallpaperColors: WallpaperColors? = null) = launchSafe {
        try {
            // 1. Retrieve user settings
            val userSelectedColor = settingsManager.textColorFlow.first()
            val isShadowEnabled = settingsManager.textShadowEnabledFlow.first()
            val userSelectedChipColor = settingsManager.chipBackgroundColorFlow.first()

            // 2. Determine text color
            val finalTextColor = if (userSelectedColor != 0) {
                userSelectedColor
            } else {
                // Automatic color detection with safe fallback
                try {
                    val readabilityMode = settingsManager.readabilityModeFlow.first()
                    when (readabilityMode) {
                        "smart_contrast" -> {
                            if (wallpaperColors != null &&
                                (wallpaperColors.colorHints and WallpaperColors.HINT_SUPPORTS_DARK_TEXT) != 0
                            ) Color.BLACK else Color.WHITE
                        }

                        "adaptive_colors" -> {
                            wallpaperColors?.secondaryColor?.toArgb() ?: Color.WHITE
                        }

                        else -> Color.WHITE
                    }
                } catch (e: Throwable) {
                    TimberWrapper.silentError(e, "Error determining automatic text color")
                    DEFAULT_TEXT_COLOR
                }
            }

            // 3. Determine shadow color
            val finalShadowColor = if (isShadowEnabled) {
                try {
                    calculateTonalShadowColor(finalTextColor)
                } catch (e: Throwable) {
                    TimberWrapper.silentError(e, "Error calculating shadow color")
                    DEFAULT_SHADOW_COLOR
                }
            } else {
                Color.TRANSPARENT
            }

            // 4. Update UI state
            _uiColorsState.update {
                it.copy(
                    textColor = finalTextColor,
                    shadowColor = finalShadowColor,
                    chipBackgroundColor = userSelectedChipColor
                )
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            TimberWrapper.silentError(e, "Error updating UI colors from settings")
            // Safe fallback
            _uiColorsState.value = UiColorsState(
                textColor = DEFAULT_TEXT_COLOR,
                shadowColor = DEFAULT_SHADOW_COLOR,
                chipBackgroundColor = DEFAULT_CHIP_BG_COLOR
            )
        }
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
}