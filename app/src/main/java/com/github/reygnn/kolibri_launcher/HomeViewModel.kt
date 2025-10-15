/*
    * Copyright (C) 2025 reygnn (Ulrich Kaufmann)
    *
    * This program is free software: you can redistribute it and/or modify
    * it under the terms of the GNU General Public License as published by
    * the Free Software Foundation, either version 3 of the License, or
    * (at your option) any later version.
    */

package com.github.reygnn.kolibri_launcher

import android.app.WallpaperColors
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Color
import android.os.BatteryManager
import android.text.format.DateFormat
import androidx.lifecycle.LiveData
import androidx.lifecycle.asLiveData
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.retry
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import timber.log.Timber
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale
import javax.inject.Inject

data class HomeUiState(
    val timeString: String = "",
    val dateString: String = "",
    val batteryString: String = ""
)

data class UiColorsState(
    val textColor: Int = Color.WHITE,
    val shadowColor: Int = Color.BLACK
)

@HiltViewModel
class HomeViewModel @Inject constructor(
    private val installedAppsManager: InstalledAppsRepository,
    private val appUpdateSignal: AppUpdateSignal,
    private val installedAppsStateManager: InstalledAppsStateRepository,
    getFavoriteAppsUseCase: GetFavoriteAppsUseCaseRepository,
    private val getDrawerAppsUseCase: GetDrawerAppsUseCaseRepository,
    @param:ApplicationContext private val context: Context,
    private val favoritesManager: FavoritesRepository,
    private val settingsManager: SettingsRepository,
    private val appUsageManager: AppUsageRepository,
    private val screenLockManager: ScreenLockRepository,
    private val appVisibilityManager: AppVisibilityRepository,
    @MainDispatcher mainDispatcher: CoroutineDispatcher
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

    companion object {
        @Volatile
        var isInTestMode = false
    }

    init {
        updateTimeAndDate()
        getInitialBatteryState()

        // NUR starten wenn NICHT im Test-Modus
        if (!isInTestMode) {
            launchSafe {
                delay(100)
                observeInstalledApps()
                listenForAppUpdates()

                getFavoriteAppsUseCase.favoriteApps.collect { state ->
                    _favoriteAppsState.value = state
                    if (state is UiState.Success && state.data.isFallback && !fallbackToastShown) {
                        fallbackToastShown = true
                        sendEvent(UiEvent.ShowToast(R.string.welcome_toast_fallback_favorites))
                    }
                }
            }
        } else {
            // Im Test-Modus: NUR Favorites observen
            launchSafe {
                getFavoriteAppsUseCase.favoriteApps.collect { state ->
                    _favoriteAppsState.value = state
                }
            }
        }
    }

    // --- PUBLIC FUNCTIONS CALLED FROM FRAGMENTS ---

    fun onFlingUp() {
        launchSafe {
            sendEvent(UiEvent.ShowAppDrawer)
        }
    }
    fun onLongPress() {
        launchSafe {
            sendEvent(UiEvent.ShowSettings)
        }
    }
    fun onTimeDoubleClick() {
        launchSafe {
            sendEvent(UiEvent.OpenClock)
        }
    }
    fun onDateDoubleClick() {
        launchSafe {
            sendEvent(UiEvent.OpenCalendar)
        }
    }
    fun onBatteryDoubleClick() {
        launchSafe {
            sendEvent(UiEvent.OpenBatterySettings)
        }
    }

    fun onDoubleTapToLock() {
        launchSafe {
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
            } catch (e: Exception) {
                TimberWrapper.silentError(e, "Error during double tap to lock action")
            }
        }
    }

    fun onToggleFavorite(app: AppInfo, currentFavoritesCount: Int) {
        launchSafe {
            try {
                if (!favoritesManager.isFavoriteComponent(app.componentName) &&
                    currentFavoritesCount >= AppConstants.MAX_FAVORITES_ON_HOME) {
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

                sendEvent(UiEvent.ShowToastFromString(
                    context.getString(messageResId, app.displayName)
                ))
            } catch (e: Exception) {
                TimberWrapper.silentError(e, "Error toggling favorite")
                sendEvent(UiEvent.ShowToast(R.string.error_generic))
            }
        }
    }

    fun onHideApp(app: AppInfo) {
        launchSafe {
            try {
                appVisibilityManager.hideComponent(app.componentName)
                sendEvent(UiEvent.ShowToastFromString(
                    context.getString(R.string.app_now_hidden_in_drawer, app.displayName)
                ))
            } catch (e: Exception) {
                TimberWrapper.silentError(e, "Error hiding app")
                sendEvent(UiEvent.ShowToast(R.string.error_generic))
            }
        }
    }

    fun onResetAppUsage(app: AppInfo) {
        launchSafe {
            try {
                resetAppUsage(app)
                sendEvent(UiEvent.ShowToastFromString(
                    context.getString(R.string.usage_data_reset_success, app.displayName)
                ))
            } catch (e: Exception) {
                TimberWrapper.silentError(e, "Error resetting usage data")
                sendEvent(UiEvent.ShowToast(R.string.error_generic))
            }
        }
    }

    fun onAppClicked(app: AppInfo) {
        launchSafe {
            try {
                sendEvent(UiEvent.LaunchApp(app))

                appUsageManager.recordPackageLaunch(app.packageName)
                refreshInstalledApps()
            } catch (e: Exception) {
                TimberWrapper.silentError(e, "Error handling app click for ${app.packageName}")
                sendEvent(UiEvent.ShowToast(R.string.error_launching_app))
            }
        }
    }

    fun onShowApp(app: AppInfo) {
        launchSafe {
            try {
                appVisibilityManager.showComponent(app.componentName)
                sendEvent(UiEvent.ShowToastFromString(
                    context.getString(R.string.app_now_visible_in_drawer, app.displayName)
                ))
            } catch (e: Exception) {
                TimberWrapper.silentError(e, "Failed to show app")
                sendEvent(UiEvent.ShowToast(R.string.error_generic))
            }
        }
    }

    fun onAppInfoError() {
        launchSafe {
            sendEvent(UiEvent.ShowToast(R.string.error_app_info_open))
        }
    }

    fun onFavoriteAppsError(message: String) {
        launchSafe {
            sendEvent(UiEvent.ShowToastFromString(message))
        }
    }

    fun toggleSortOrder() = launchSafe {
        val newOrder = if (settingsManager.sortOrderFlow.first() == SortOrder.ALPHABETICAL) {
            SortOrder.TIME_WEIGHTED_USAGE
        } else {
            SortOrder.ALPHABETICAL
        }
        settingsManager.setSortOrder(newOrder)
    }

    fun refreshInstalledApps() = launchSafe {
        (installedAppsManager as? InstalledAppsManager)?.triggerAppsUpdate()
    }

    fun refreshDynamicUiData() {
        updateTimeAndDate()
        getInitialBatteryState()
    }

    fun refreshAllData() {
        refreshDynamicUiData()
        refreshInstalledApps()
    }

    // --- PRIVATE/INTERNAL LOGIC ---

    private fun listenForAppUpdates() = launchSafe {
        appUpdateSignal.events.collect {
            refreshInstalledApps()
        }
    }

    private var appLoadRetryCount = 0
    private val MAX_APP_LOAD_RETRIES = 3

    private fun observeInstalledApps() = launchSafe {
        installedAppsManager.getInstalledApps()
            .retry(MAX_APP_LOAD_RETRIES.toLong()) { cause ->
                if (cause is IOException) {
                    appLoadRetryCount++
                    Timber.w("App loading failed, retry ${appLoadRetryCount}/${MAX_APP_LOAD_RETRIES}")
                    delay(1000L * appLoadRetryCount)
                    true
                } else {
                    false
                }
            }
            .catch { e ->
                TimberWrapper.silentError(e, "Failed to collect installed apps.")

                val cachedApps = installedAppsStateManager.getCurrentApps()
                if (cachedApps.isNotEmpty()) {
                    Timber.w("Using cached apps as fallback (${cachedApps.size} apps)")
                    installedAppsStateManager.updateApps(cachedApps)
                } else {
                    installedAppsStateManager.updateApps(emptyList())
                    sendEvent(UiEvent.ShowToast(R.string.error_app_list_not_loaded))
                }
            }
            .collect { realApps ->
                if (realApps.isEmpty()) {
                    Timber.w("Collected an empty app list. Skipping cleanup to prevent data loss.")
                    installedAppsStateManager.updateApps(emptyList())
                    return@collect
                }

                try {
                    val allValidComponentNames = realApps.map { it.componentName }
                    favoritesManager.cleanupFavoriteComponents(allValidComponentNames)
                    installedAppsStateManager.updateApps(realApps)
                    appLoadRetryCount = 0
                } catch (e: kotlinx.coroutines.CancellationException) {
                    throw e
                } catch (e: Exception) {
                    TimberWrapper.silentError(e, "Error cleaning up favorites")
                    installedAppsStateManager.updateApps(realApps)
                }
            }
    }

    private fun resetAppUsage(app: AppInfo) = launchSafe {
        appUsageManager.removeUsageDataForPackage(app.packageName)
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
        } catch (e: Exception) {
            TimberWrapper.silentError(e, "Failed to update time and date")
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
        } catch (e: Exception) {
            TimberWrapper.silentError(e, "Failed to register battery receiver.")
        }
    }

    fun updateBatteryLevelFromIntent(intent: Intent?) {
        try {
            intent?.let {
                val level = it.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
                val scale = it.getIntExtra(BatteryManager.EXTRA_SCALE, -1)
                updateBatteryLevel(level, scale)
            }
        } catch (e: Exception) {
            TimberWrapper.silentError(e, "Failed to update battery level")
        }
    }

    // New testable function
    fun updateBatteryLevel(level: Int, scale: Int) {
        try {
            if (level != -1 && scale != -1 && scale > 0) {
                val batteryPercent = (level * 100 / scale)
                _uiState.update { it.copy(batteryString = "${batteryPercent}%") }
            }
        } catch (e: Exception) {
            TimberWrapper.silentError(e, "Failed to update battery level")
        }
    }

    fun updateUiColorsFromWallpaper(wallpaperColors: WallpaperColors?) {
        launchSafe {
            try {
                val readabilityMode = settingsManager.readabilityModeFlow.first()
                val colorPair = when (readabilityMode) {
                    "smart_contrast" -> {
                        val textColor = if (wallpaperColors != null &&
                            (wallpaperColors.colorHints and WallpaperColors.HINT_SUPPORTS_DARK_TEXT) != 0)
                            Color.BLACK else Color.WHITE
                        Pair(textColor, if (textColor == Color.WHITE) Color.BLACK else Color.WHITE)
                    }
                    "adaptive_colors" -> {
                        val textColor = wallpaperColors?.secondaryColor?.toArgb() ?: Color.WHITE
                        Pair(textColor, if (Color.luminance(textColor) < 0.5) Color.WHITE else Color.BLACK)
                    }
                    else -> Pair(Color.WHITE, Color.BLACK)
                }
                _uiColorsState.value = UiColorsState(
                    textColor = colorPair.first,
                    shadowColor = colorPair.second
                )
            } catch (e: Exception) {
                TimberWrapper.silentError(e, "Error getting optimal text color.")
                _uiColorsState.value = UiColorsState()
            }
        }
    }

}