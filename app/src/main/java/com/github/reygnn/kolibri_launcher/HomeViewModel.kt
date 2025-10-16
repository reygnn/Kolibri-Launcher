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
import androidx.core.graphics.ColorUtils
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
    val settingsManager: SettingsRepository,
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
        updateUiColors()

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
            sendEvent(UiEvent.ShowCustomizationOptions)
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
                sendEvent(
                    UiEvent.ShowToastFromString(
                        context.getString(R.string.app_now_hidden_in_drawer, app.displayName)
                    )
                )
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
                sendEvent(
                    UiEvent.ShowToastFromString(
                        context.getString(R.string.usage_data_reset_success, app.displayName)
                    )
                )
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
                sendEvent(
                    UiEvent.ShowToastFromString(
                        context.getString(R.string.app_now_visible_in_drawer, app.displayName)
                    )
                )
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
        installedAppsManager.triggerAppsUpdate()
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
        settingsManager.setTextColor(color)
        updateUiColors()
    }

    fun onSetTextShadowEnabled(isEnabled: Boolean) = launchSafe {
        settingsManager.setTextShadowEnabled(isEnabled)
        updateUiColors()
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

    fun updateUiColors(wallpaperColors: WallpaperColors? = null) {
        launchSafe {
            try {
                // 1. Gespeicherte User-Einstellungen abrufen
                val userSelectedColor = settingsManager.textColorFlow.first()
                val isShadowEnabled = settingsManager.textShadowEnabledFlow.first()

                // 2. Textfarbe bestimmen
                val finalTextColor = if (userSelectedColor != 0) {
                    // Der User hat eine feste Farbe gewählt
                    userSelectedColor
                } else {
                    // Automatische Farberkennung (deine alte Logik als Fallback)
                    val readabilityMode = settingsManager.readabilityModeFlow.first()
                    when (readabilityMode) {
                        "smart_contrast" -> {
                            if (wallpaperColors != null &&
                                (wallpaperColors.colorHints and WallpaperColors.HINT_SUPPORTS_DARK_TEXT) != 0
                            )
                                Color.BLACK else Color.WHITE
                        }

                        "adaptive_colors" -> {
                            wallpaperColors?.secondaryColor?.toArgb() ?: Color.WHITE
                        }

                        else -> Color.WHITE
                    }
                }

                // 3. Schattenfarbe bestimmen
                val finalShadowColor = if (isShadowEnabled) {
                    // Material-3-konformer Schatten: Eine dunklere Version der Textfarbe
                    calculateTonalShadowColor(finalTextColor)
                } else {
                    // Schatten ist aus
                    Color.TRANSPARENT
                }

                // 4. Den UI-State für das Fragment aktualisieren
                _uiColorsState.update {
                    it.copy(textColor = finalTextColor, shadowColor = finalShadowColor)
                }
            } catch (e: Exception) {
                TimberWrapper.silentError(e, "Error updating UI colors from settings.")
                _uiColorsState.value = UiColorsState() // Sicherer Fallback
            }
        }
    }

/*    private fun calculateTonalShadowColor(baseColor: Int): Int {
        // Berechnet die wahrgenommene Helligkeit der Farbe (0.0 = schwarz, 1.0 = weiß)
        val luminance = ColorUtils.calculateLuminance(baseColor)
        val threshold = 0.5f // Unsere klare Trennlinie zwischen "hell" und "dunkel"

        // IST DIE FARBE DUNKEL?
        return if (luminance < threshold) {
            // --- Ja, die Farbe ist dunkel -> wir brauchen einen HELLEN Schatten ---
            val hsl = FloatArray(3)
            ColorUtils.colorToHSL(baseColor, hsl)
            // Helligkeit deutlich erhöhen, um einen sichtbaren Kontrast zu schaffen
            hsl[2] = (hsl[2] + 0.5f).coerceIn(0.0f, 1.0f)
            val lighterColor = ColorUtils.HSLToColor(hsl)
            // Eine leichte Transparenz hinzufügen, damit es wie ein Schatten wirkt
            Color.argb(
                128, // 50% Deckkraft
                Color.red(lighterColor),
                Color.green(lighterColor),
                Color.blue(lighterColor)
            )
        }
        // IST DIE FARBE HELL?
        else {
            // --- Nein, die Farbe ist hell -> wir brauchen einen DUNKLEN Schatten ---
            // Halb-transparentes Schwarz ist hier die robusteste und am besten
            // sichtbare Lösung für JEDE helle Farbe.
            Color.argb(128, 0, 0, 0)
        }
    }*/

    private fun calculateTonalShadowColor(baseColor: Int): Int {
        // Berechnet die wahrgenommene Helligkeit der Farbe (0.0 = schwarz, 1.0 = weiß)
        val luminance = ColorUtils.calculateLuminance(baseColor)
        val threshold = 0.5f // Schwellenwert zwischen hell und dunkel

        // IST DIE FARBE DUNKEL?
        return if (luminance < threshold) {
            // --- Ja, die Farbe ist dunkel -> wir brauchen einen HELLEN Schatten ---
            val hsl = FloatArray(3)
            ColorUtils.colorToHSL(baseColor, hsl)
            // Helligkeit stark erhöhen, mindestens auf 0.7 für besseren Kontrast
            hsl[2] = (hsl[2] + 0.7f).coerceIn(0.7f, 1.0f) // Mindesthelligkeit 0.7
            // Optional: Sättigung leicht reduzieren, um den Schatten neutraler zu machen
            hsl[1] = (hsl[1] * 0.8f).coerceIn(0.0f, 1.0f)
            val lighterColor = ColorUtils.HSLToColor(hsl)
            // Höhere Deckkraft für bessere Sichtbarkeit
            Color.argb(
                192, // 75% Deckkraft
                Color.red(lighterColor),
                Color.green(lighterColor),
                Color.blue(lighterColor)
            )
        } else {
            // --- Nein, die Farbe ist hell -> wir brauchen einen DUNKLEN Schatten ---
            // Halb-transparentes Schwarz bleibt robust
            Color.argb(128, 0, 0, 0)
        }
    }

/*    private fun calculateTonalShadowColor(baseColor: Int): Int {
        // Berechnet die wahrgenommene Helligkeit der Farbe (0.0 = schwarz, 1.0 = weiß)
        val luminance = ColorUtils.calculateLuminance(baseColor)
        val threshold = 0.5f // Schwellenwert zwischen hell und dunkel
        val minContrast = 4.5 // Mindest-Kontrastverhältnis (WCAG-Empfehlung)

        // IST DIE FARBE DUNKEL?
        if (luminance < threshold) {
            // --- Ja, die Farbe ist dunkel -> wir brauchen einen HELLEN Schatten ---
            val hsl = FloatArray(3)
            ColorUtils.colorToHSL(baseColor, hsl)
            // Initiale Helligkeit stark erhöhen, mindestens auf 0.7 für besseren Kontrast
            hsl[2] = (hsl[2] + 0.7f).coerceIn(0.7f, 1.0f)
            // Sättigung leicht reduzieren, um den Schatten neutraler zu machen
            hsl[1] = (hsl[1] * 0.8f).coerceIn(0.0f, 1.0f)
            var lighterColor = ColorUtils.HSLToColor(hsl)

            // Kontrast prüfen
            var contrast = ColorUtils.calculateContrast(lighterColor, baseColor)
            // Falls der Kontrast zu niedrig ist, Helligkeit schrittweise erhöhen
            while (contrast < minContrast && hsl[2] < 1.0f) {
                hsl[2] = (hsl[2] + 0.05f).coerceIn(0.7f, 1.0f) // Inkrementelle Erhöhung
                lighterColor = ColorUtils.HSLToColor(hsl)
                contrast = ColorUtils.calculateContrast(lighterColor, baseColor)
            }

            // Schatten mit höherer Deckkraft für bessere Sichtbarkeit
            return Color.argb(
                192, // 75% Deckkraft
                Color.red(lighterColor),
                Color.green(lighterColor),
                Color.blue(lighterColor)
            )
        } else {
            // --- Nein, die Farbe ist hell -> wir brauchen einen DUNKLEN Schatten ---
            // Halb-transparentes Schwarz bleibt robust
            return Color.argb(128, 0, 0, 0)
        }
    }*/

}