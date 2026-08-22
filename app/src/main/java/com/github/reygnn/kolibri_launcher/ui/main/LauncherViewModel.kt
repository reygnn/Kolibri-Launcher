/*
 * Copyright (C) 2025 reygnn (Ulrich Kaufmann)
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */

package com.github.reygnn.kolibri_launcher.ui.main

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.LiveData
import androidx.lifecycle.ProcessLifecycleOwner
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.viewModelScope
import com.github.reygnn.kolibri_launcher.R
import com.github.reygnn.kolibri_launcher.data.WallpaperFileManager
import com.github.reygnn.kolibri_launcher.ui.home.wallpaper.WallpaperCompositeCache
import com.github.reygnn.kolibri_launcher.data.wallpaper.WallpaperBitmapLuminanceImpl
import com.github.reygnn.kolibri_launcher.core.CompositeLuminanceSignal
import com.github.reygnn.kolibri_launcher.core.IoDispatcher
import com.github.reygnn.kolibri_launcher.core.MainDispatcher
import com.github.reygnn.kolibri_launcher.domain.model.AppInfo
import com.github.reygnn.kolibri_launcher.domain.model.FabPosition
import com.github.reygnn.kolibri_launcher.domain.model.FavoriteAppsResult
import com.github.reygnn.kolibri_launcher.domain.model.FavoritesAlignment
import com.github.reygnn.kolibri_launcher.domain.model.ResolvedBackground
import com.github.reygnn.kolibri_launcher.domain.model.SortOrder
import com.github.reygnn.kolibri_launcher.domain.model.UiColorsState
import com.github.reygnn.kolibri_launcher.domain.model.WallpaperState
import com.github.reygnn.kolibri_launcher.ui.home.wallpaper.LayerTransform
import com.github.reygnn.kolibri_launcher.ui.home.wallpaper.WallpaperFlattener
import com.github.reygnn.kolibri_launcher.domain.usecase.CheckAppUsageUseCase
import com.github.reygnn.kolibri_launcher.domain.usecase.ClearWallpaperUseCase
import com.github.reygnn.kolibri_launcher.domain.usecase.GetAutoLaunchSettingUseCase
import com.github.reygnn.kolibri_launcher.domain.usecase.GetAutoShowKeyboardSettingUseCase
import com.github.reygnn.kolibri_launcher.domain.usecase.GetDrawerAppsUseCase
import com.github.reygnn.kolibri_launcher.domain.usecase.GetFabPositionUseCase
import com.github.reygnn.kolibri_launcher.domain.usecase.GetFavoriteAppsUseCase
import com.github.reygnn.kolibri_launcher.domain.usecase.GetLayoutSettingsUseCase
import com.github.reygnn.kolibri_launcher.domain.usecase.GetTextShadowEnabledUseCase
import com.github.reygnn.kolibri_launcher.domain.usecase.GetRecentAppsUseCase
import com.github.reygnn.kolibri_launcher.domain.usecase.GetWallpaperScrimAlphaUseCase
import com.github.reygnn.kolibri_launcher.domain.usecase.HandleSwipeActionUseCase
import com.github.reygnn.kolibri_launcher.domain.usecase.HideAppUseCase
import com.github.reygnn.kolibri_launcher.domain.usecase.ObserveHomeSettingsUseCase
import com.github.reygnn.kolibri_launcher.domain.usecase.ObserveInstalledAppsUseCase
import com.github.reygnn.kolibri_launcher.domain.usecase.ObserveTimeBasedEventsUseCase
import com.github.reygnn.kolibri_launcher.domain.usecase.ObserveUiColorsUseCase
import com.github.reygnn.kolibri_launcher.domain.usecase.ObserveWallpaperStateUseCase
import com.github.reygnn.kolibri_launcher.domain.usecase.RecordAppLaunchUseCase
import com.github.reygnn.kolibri_launcher.domain.usecase.RefreshAppsUseCase
import com.github.reygnn.kolibri_launcher.domain.usecase.ResetAppUsageUseCase
import com.github.reygnn.kolibri_launcher.domain.usecase.ResolveWallpaperSurfaceUseCase
import com.github.reygnn.kolibri_launcher.domain.usecase.SaveFabPositionUseCase
import com.github.reygnn.kolibri_launcher.domain.usecase.SaveWallpaperStateUseCase
import com.github.reygnn.kolibri_launcher.domain.usecase.SetContentTopMarginUseCase
import com.github.reygnn.kolibri_launcher.domain.usecase.SetFavoritesAlignmentUseCase
import com.github.reygnn.kolibri_launcher.domain.usecase.SetFontBoldUseCase
import com.github.reygnn.kolibri_launcher.domain.usecase.SetLayoutScaleUseCase
import com.github.reygnn.kolibri_launcher.domain.usecase.SetWallpaperScrimAlphaUseCase
import com.github.reygnn.kolibri_launcher.domain.usecase.SetTextColorUseCase
import com.github.reygnn.kolibri_launcher.domain.usecase.SetTextShadowEnabledUseCase
import com.github.reygnn.kolibri_launcher.domain.usecase.SetVerticalPaddingUseCase
import com.github.reygnn.kolibri_launcher.domain.usecase.SetWallpaperImageUseCase
import com.github.reygnn.kolibri_launcher.domain.usecase.ShowAppUseCase
import com.github.reygnn.kolibri_launcher.domain.usecase.ToggleFavoriteUseCase
import com.github.reygnn.kolibri_launcher.domain.usecase.ToggleSortOrderUseCase
import com.github.reygnn.kolibri_launcher.ui.base.BaseViewModel
import com.github.reygnn.kolibri_launcher.ui.base.UiEvent
import com.github.reygnn.kolibri_launcher.domain.model.UiState
import com.github.reygnn.kolibri_launcher.ui.main.delegate.AppManagementDelegate
import com.github.reygnn.kolibri_launcher.ui.main.delegate.ClockDelegate
import com.github.reygnn.kolibri_launcher.ui.main.delegate.DelegateScope
import com.github.reygnn.kolibri_launcher.ui.main.delegate.GestureDelegate
import com.github.reygnn.kolibri_launcher.ui.main.delegate.LayoutDelegate
import com.github.reygnn.kolibri_launcher.ui.main.delegate.ThemingDelegate
import com.github.reygnn.kolibri_launcher.ui.main.delegate.WallpaperDelegate
import com.github.reygnn.kolibri_launcher.core.AppUpdateSignal
import com.github.reygnn.kolibri_launcher.ui.util.MonotonicClock
import com.github.reygnn.kolibri_launcher.ui.util.TestMode
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.mapNotNull
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import javax.inject.Inject

/**
 * Slim facade ViewModel that delegates all work to specialized delegates
 * and combines their states into a single HomeUiState via combine().
 *
 * The public API is identical to the original — no Fragment changes needed.
 */
@HiltViewModel
class LauncherViewModel @Inject constructor(
    // --- UseCases (injected by Hilt, passed to delegates) ---
    getFavoriteAppsUseCase: GetFavoriteAppsUseCase,
    getDrawerAppsUseCase: GetDrawerAppsUseCase,
    hideAppUseCase: HideAppUseCase,
    toggleFavoriteUseCase: ToggleFavoriteUseCase,
    recordAppLaunchUseCase: RecordAppLaunchUseCase,
    refreshAppsUseCase: RefreshAppsUseCase,
    resetAppUsageUseCase: ResetAppUsageUseCase,
    showAppUseCase: ShowAppUseCase,
    toggleSortOrderUseCase: ToggleSortOrderUseCase,
    handleSwipeActionUseCase: HandleSwipeActionUseCase,
    getRecentAppsUseCase: GetRecentAppsUseCase,
    observeTimeBasedEventsUseCase: ObserveTimeBasedEventsUseCase,
    observeUiColorsUseCase: ObserveUiColorsUseCase,
    setTextColorUseCase: SetTextColorUseCase,
    setTextShadowEnabledUseCase: SetTextShadowEnabledUseCase,
    observeInstalledAppsUseCase: ObserveInstalledAppsUseCase,
    getAutoLaunchSettingUseCase: GetAutoLaunchSettingUseCase,
    observeHomeSettingsUseCase: ObserveHomeSettingsUseCase,
    checkAppUsageUseCase: CheckAppUsageUseCase,
    getAutoShowKeyboardSettingUseCase: GetAutoShowKeyboardSettingUseCase,
    getTextShadowEnabledUseCase: GetTextShadowEnabledUseCase,
    getLayoutSettingsUseCase: GetLayoutSettingsUseCase,
    setLayoutScaleUseCase: SetLayoutScaleUseCase,
    getWallpaperScrimAlphaUseCase: GetWallpaperScrimAlphaUseCase,
    setWallpaperScrimAlphaUseCase: SetWallpaperScrimAlphaUseCase,
    setVerticalPaddingUseCase: SetVerticalPaddingUseCase,
    setFontBoldUseCase: SetFontBoldUseCase,
    setContentTopMarginUseCase: SetContentTopMarginUseCase,
    setFavoritesAlignmentUseCase: SetFavoritesAlignmentUseCase,
    resolveAppDrawerSurfaceUseCase: ResolveWallpaperSurfaceUseCase,
    observeWallpaperStateUseCase: ObserveWallpaperStateUseCase,
    saveWallpaperStateUseCase: SaveWallpaperStateUseCase,
    setWallpaperImageUseCase: SetWallpaperImageUseCase,
    clearWallpaperUseCase: ClearWallpaperUseCase,
    getFabPositionUseCase: GetFabPositionUseCase,
    saveFabPositionUseCase: SaveFabPositionUseCase,
    wallpaperFileManager: WallpaperFileManager,
    wallpaperFlattener: WallpaperFlattener,
    wallpaperCompositeCache: WallpaperCompositeCache,
    wallpaperBitmapLuminance: WallpaperBitmapLuminanceImpl,
    compositeLuminanceSignal: CompositeLuminanceSignal,
    appUpdateSignal: AppUpdateSignal,
    monotonicClock: MonotonicClock,
    private val savedStateHandle: SavedStateHandle,
    @param:ApplicationContext private val context: Context,
    @MainDispatcher mainDispatcher: CoroutineDispatcher,
    @IoDispatcher ioDispatcher: CoroutineDispatcher,
    private val testMode: TestMode
) : BaseViewModel<UiEvent>(mainDispatcher) {

    override val errorEvent = UiEvent.ShowToast(R.string.error_generic)

    // ===========================================
    // DELEGATE SCOPE (shared infrastructure)
    // ===========================================

    private val delegateScope = DelegateScope(
        coroutineScope = viewModelScope,
        mainDispatcher = mainDispatcher,
        eventSender = { event -> sendEvent(event) }
    )

    // ===========================================
    // DELEGATES
    // ===========================================

    private val clockDelegate = ClockDelegate(
        context = context,
        observeTimeBasedEventsUseCase = observeTimeBasedEventsUseCase,
        scope = delegateScope
    )

    private val appDelegate = AppManagementDelegate(
        context = context,
        getFavoriteAppsUseCase = getFavoriteAppsUseCase,
        getDrawerAppsUseCase = getDrawerAppsUseCase,
        hideAppUseCase = hideAppUseCase,
        showAppUseCase = showAppUseCase,
        toggleFavoriteUseCase = toggleFavoriteUseCase,
        toggleSortOrderUseCase = toggleSortOrderUseCase,
        recordAppLaunchUseCase = recordAppLaunchUseCase,
        refreshAppsUseCase = refreshAppsUseCase,
        resetAppUsageUseCase = resetAppUsageUseCase,
        observeInstalledAppsUseCase = observeInstalledAppsUseCase,
        observeHomeSettingsUseCase = observeHomeSettingsUseCase,
        getAutoLaunchSettingUseCase = getAutoLaunchSettingUseCase,
        getAutoShowKeyboardSettingUseCase = getAutoShowKeyboardSettingUseCase,
        checkAppUsageUseCase = checkAppUsageUseCase,
        appUpdateSignal = appUpdateSignal,
        monotonicClock = monotonicClock,
        savedStateHandle = savedStateHandle,
        scope = delegateScope
    )

    private val gestureDelegate = GestureDelegate(
        getRecentAppsUseCase = getRecentAppsUseCase,
        // Snapshot read of the same list that drives the home events indicator,
        // so the double-tap dialog can never diverge from what the indicator shows.
        currentTimeBasedEvents = { clockDelegate.timeBasedEvents.value },
        handleSwipeActionUseCase = handleSwipeActionUseCase,
        scope = delegateScope
    )

    private val themingDelegate = ThemingDelegate(
        observeUiColorsUseCase = observeUiColorsUseCase,
        setTextColorUseCase = setTextColorUseCase,
        setTextShadowEnabledUseCase = setTextShadowEnabledUseCase,
        getTextShadowEnabledUseCase = getTextShadowEnabledUseCase,
        getWallpaperScrimAlphaUseCase = getWallpaperScrimAlphaUseCase,
        setWallpaperScrimAlphaUseCase = setWallpaperScrimAlphaUseCase,
        resolveAppDrawerSurfaceUseCase = resolveAppDrawerSurfaceUseCase,
        appDrawerSurfaceLightColor = ContextCompat.getColor(context, R.color.app_drawer_surface_light),
        appDrawerSurfaceDarkColor = ContextCompat.getColor(context, R.color.app_drawer_surface_dark),
        scope = delegateScope
    )

    private val layoutDelegate = LayoutDelegate(
        getLayoutSettingsUseCase = getLayoutSettingsUseCase,
        setLayoutScaleUseCase = setLayoutScaleUseCase,
        setVerticalPaddingUseCase = setVerticalPaddingUseCase,
        setFontBoldUseCase = setFontBoldUseCase,
        setContentTopMarginUseCase = setContentTopMarginUseCase,
        setFavoritesAlignmentUseCase = setFavoritesAlignmentUseCase,
        scope = delegateScope
    )

    private val wallpaperDelegate = WallpaperDelegate(
        context = context,
        observeWallpaperStateUseCase = observeWallpaperStateUseCase,
        saveWallpaperStateUseCase = saveWallpaperStateUseCase,
        setWallpaperImageUseCase = setWallpaperImageUseCase,
        clearWallpaperUseCase = clearWallpaperUseCase,
        getFabPositionUseCase = getFabPositionUseCase,
        saveFabPositionUseCase = saveFabPositionUseCase,
        wallpaperFileManager = wallpaperFileManager,
        wallpaperFlattener = wallpaperFlattener,
        compositeCache = wallpaperCompositeCache,
        bitmapLuminance = wallpaperBitmapLuminance,
        compositeLuminanceSignal = compositeLuminanceSignal,
        ioDispatcher = ioDispatcher,
        scope = delegateScope
    )

    // ===========================================
    // COMBINED UI STATE (replaces old _uiState)
    // ===========================================

    val uiState: StateFlow<HomeUiState> = combine(
        clockDelegate.timeString,
        clockDelegate.dateString,
        clockDelegate.batteryString,
        clockDelegate.timeBasedEvents
    ) { time, date, battery, events ->
        HomeUiState(
            timeString = time,
            dateString = date,
            batteryString = battery,
            timeBasedEvents = events
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.Eagerly,
        initialValue = HomeUiState()
    )

    // ===========================================
    // DELEGATED STATE (direct pass-through)
    // ===========================================

    val favoriteAppsState: StateFlow<UiState<FavoriteAppsResult>> get() = appDelegate.favoriteAppsState
    val drawerApps: LiveData<List<AppInfo>> get() = appDelegate.drawerApps
    val sortOrder: LiveData<SortOrder> get() = appDelegate.sortOrder
    val appDrawerSearchQuery: StateFlow<String> get() = appDelegate.appDrawerSearchQuery

    val uiColorsState: StateFlow<UiColorsState> get() = themingDelegate.uiColorsState
    val appDrawerSurfaceState: StateFlow<ResolvedBackground> get() = themingDelegate.appDrawerSurfaceState

    val layoutScaleState: StateFlow<Float> get() = layoutDelegate.layoutScaleState
    val wallpaperScrimAlphaState: StateFlow<Float> get() = themingDelegate.wallpaperScrimAlphaState
    val verticalPaddingState: StateFlow<Float> get() = layoutDelegate.verticalPaddingState
    val isFontBoldState: StateFlow<Boolean> get() = layoutDelegate.isFontBoldState
    val contentTopMarginState: StateFlow<Float> get() = layoutDelegate.contentTopMarginState
    val favoritesAlignmentState: StateFlow<FavoritesAlignment> get() = layoutDelegate.favoritesAlignmentState

    val wallpaperState: StateFlow<WallpaperState> get() = wallpaperDelegate.wallpaperState
    val isWallpaperEditMode: StateFlow<Boolean> get() = wallpaperDelegate.isWallpaperEditMode

    /**
     * Emits the current scrim alpha (as a value to display) whenever the wallpaper
     * image changed (cleared or replaced) AND a non-zero scrim is set — the UI then
     * offers to reset the scrim so a leftover dim doesn't darken a fresh, non-extreme
     * wallpaper. Silent when the scrim is already 0 (nothing to offer). Coordinated
     * here because it needs both delegates: the image-change signal (WallpaperDelegate)
     * and the scrim value (ThemingDelegate).
     */
    val offerScrimResetEvents: Flow<Float> =
        wallpaperDelegate.wallpaperImageChanged.mapNotNull {
            wallpaperScrimAlphaState.value.takeIf { alpha -> alpha > 0f }
        }
    val pendingFocusLayerId: StateFlow<String?> get() = wallpaperDelegate.pendingFocusLayerId
    val fabPosition: StateFlow<FabPosition> get() = wallpaperDelegate.fabPosition

    fun consumePendingFocusLayerId() = wallpaperDelegate.consumePendingFocusLayerId()

    // ===========================================
    // APP LIFECYCLE OBSERVER
    // ===========================================

    private val appLifecycleObserver = LifecycleEventObserver { _, event ->
        if (event == Lifecycle.Event.ON_START) {
            clockDelegate.refreshTimeNow()
        }
    }

    // ===========================================
    // INIT
    // ===========================================

    init {
        ProcessLifecycleOwner.get().lifecycle.addObserver(appLifecycleObserver)

        clockDelegate.start()
        themingDelegate.start()
        appDelegate.start(isTestMode = testMode.isEnabled)
        wallpaperDelegate.start()
    }

    // ===========================================
    // CLEANUP
    // ===========================================

    override fun onCleared() {
        super.onCleared()
        ProcessLifecycleOwner.get().lifecycle.removeObserver(appLifecycleObserver)
    }

    // ===========================================
    // DELEGATED PUBLIC API — CLOCK
    // ===========================================

    fun refreshTimeNow() = clockDelegate.refreshTimeNow()
    fun updateTimeAndDate() = clockDelegate.refreshTimeNow()
    fun updateBatteryLevelFromIntent(intent: Intent?) = clockDelegate.updateBatteryLevelFromIntent(intent)
    fun updateBatteryLevel(level: Int, scale: Int) = clockDelegate.updateBatteryLevel(level, scale)

    // ===========================================
    // DELEGATED PUBLIC API — APP MANAGEMENT
    // ===========================================

    fun onAppClicked(app: AppInfo) = appDelegate.onAppClicked(app)
    fun onToggleFavorite(app: AppInfo) = appDelegate.onToggleFavorite(app)
    fun onHideApp(app: AppInfo) = appDelegate.onHideApp(app)
    fun onShowApp(app: AppInfo) = appDelegate.onShowApp(app)
    fun onResetAppUsage(app: AppInfo) = appDelegate.onResetAppUsage(app)
    fun toggleSortOrder() = appDelegate.toggleSortOrder()
    fun onAppDrawerSearchQueryChanged(query: String) = appDelegate.onAppDrawerSearchQueryChanged(query)
    fun onAppDrawerClosed() = appDelegate.onAppDrawerClosed()
    fun refreshInstalledApps() = appDelegate.refreshInstalledApps()
    fun onAppInfoError() = appDelegate.onAppInfoError()
    fun onFavoriteAppsError(message: String) = appDelegate.onFavoriteAppsError(message)
    suspend fun isAutoLaunchEnabled(): Boolean = appDelegate.isAutoLaunchEnabled()
    suspend fun isAutoShowKeyboardEnabled(): Boolean = appDelegate.isAutoShowKeyboardEnabled()
    suspend fun hasUsageData(packageName: String?): Boolean = appDelegate.hasUsageData(packageName)

    // ===========================================
    // DELEGATED PUBLIC API — GESTURES
    // ===========================================

    fun onFlingUp() = gestureDelegate.onFlingUp()
    fun onSwipeDown() = gestureDelegate.onSwipeDown()
    fun onDoubleTap() = gestureDelegate.onDoubleTap()
    fun onSwipeFromRightToLeft() = gestureDelegate.onSwipeFromRightToLeft()
    fun onSwipeFromLeftToRight() = gestureDelegate.onSwipeFromLeftToRight()
    fun onLongPress() = gestureDelegate.onLongPress()
    fun onTimeDoubleClick() = gestureDelegate.onTimeDoubleClick()
    fun onDateDoubleClick() = gestureDelegate.onDateDoubleClick()
    fun onBatteryDoubleClick() = gestureDelegate.onBatteryDoubleClick()

    // ===========================================
    // DELEGATED PUBLIC API — THEMING
    // ===========================================

    fun onSetTextColor(color: Int) = themingDelegate.onSetTextColor(color)
    fun onSetTextShadowEnabled(isEnabled: Boolean) = themingDelegate.onSetTextShadowEnabled(isEnabled)
    suspend fun isTextShadowEnabled(): Boolean = themingDelegate.isTextShadowEnabled()

    // ===========================================
    // DELEGATED PUBLIC API — LAYOUT
    // ===========================================

    fun onSetLayoutScale(scale: Float) = layoutDelegate.onSetLayoutScale(scale)
    fun onSetWallpaperScrimAlpha(alpha: Float) = themingDelegate.onSetWallpaperScrimAlpha(alpha)
    fun onSetVerticalPadding(factor: Float) = layoutDelegate.onSetVerticalPadding(factor)
    fun onSetFontBold(isBold: Boolean) = layoutDelegate.onSetFontBold(isBold)
    fun onSetContentTopMargin(scale: Float) = layoutDelegate.onSetContentTopMargin(scale)
    fun onSetFavoritesAlignment(alignment: FavoritesAlignment) = layoutDelegate.onSetFavoritesAlignment(alignment)
    fun onResetLayoutSettings() = layoutDelegate.onResetLayoutSettings()

    // ===========================================
    // DELEGATED PUBLIC API — WALLPAPER
    // ===========================================

    fun onSetWallpaperImage(imageUri: Uri) = wallpaperDelegate.onSetWallpaperImage(imageUri)
    fun onSaveWallpaperTransform(
        scale: Float,
        translateX: Float,
        translateY: Float,
        captureSampleSize: Int? = null
    ) = wallpaperDelegate.onSaveWallpaperTransform(scale, translateX, translateY, captureSampleSize)
    fun onClearWallpaper() = wallpaperDelegate.onClearWallpaper()
    fun onDisplayConfigChanged() = wallpaperDelegate.onDisplayConfigChanged()
    fun onSetWallpaperEditMode(enabled: Boolean) = wallpaperDelegate.onSetWallpaperEditMode(enabled)
    fun onToggleWallpaperEditMode() = wallpaperDelegate.onToggleWallpaperEditMode()
    fun onCommitWallpaperEditMode() = wallpaperDelegate.onCommitWallpaperEditMode()
    fun onCancelWallpaperEditMode() = wallpaperDelegate.onCancelWallpaperEditMode()
    fun onAddWallpaperLayer(imageUri: Uri) = wallpaperDelegate.onAddWallpaperLayer(imageUri)
    fun onRemoveWallpaperLayer(layerIndex: Int) = wallpaperDelegate.onRemoveWallpaperLayer(layerIndex)
    fun onSwapWallpaperLayers(indexA: Int, indexB: Int) = wallpaperDelegate.onSwapWallpaperLayers(indexA, indexB)
    fun onSaveLayerTransform(
        layerIndex: Int,
        scale: Float,
        translateX: Float,
        translateY: Float,
        captureSampleSize: Int? = null
    ) = wallpaperDelegate.onSaveLayerTransform(layerIndex, scale, translateX, translateY, captureSampleSize)
    fun onSaveAllLayerTransforms(transforms: List<LayerTransform>) =
        wallpaperDelegate.onSaveAllLayerTransforms(transforms)
    fun onFabPositionChanged(xFraction: Float, yFraction: Float) =
        wallpaperDelegate.onFabPositionChanged(xFraction, yFraction)

    // ===========================================
    // COMPOSITE REFRESH (orchestration)
    // ===========================================

    fun refreshDynamicUiData() {
        clockDelegate.refreshAll()
    }

    fun refreshAllData() {
        clockDelegate.refreshAll()
        appDelegate.refreshInstalledApps()
    }
}