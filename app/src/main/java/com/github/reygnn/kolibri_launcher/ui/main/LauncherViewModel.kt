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
import android.net.Uri
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.LiveData
import androidx.lifecycle.ProcessLifecycleOwner
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.viewModelScope
import com.github.reygnn.kolibri_launcher.data.WallpaperFileManager
import com.github.reygnn.kolibri_launcher.di.MainDispatcher
import com.github.reygnn.kolibri_launcher.domain.model.AppInfo
import com.github.reygnn.kolibri_launcher.domain.model.FavoriteAppsResult
import com.github.reygnn.kolibri_launcher.domain.model.SortOrder
import com.github.reygnn.kolibri_launcher.domain.model.UiColorsState
import com.github.reygnn.kolibri_launcher.domain.model.WallpaperState
import com.github.reygnn.kolibri_launcher.domain.usecase.CheckAppUsageUseCase
import com.github.reygnn.kolibri_launcher.domain.usecase.ClearWallpaperUseCase
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
import com.github.reygnn.kolibri_launcher.domain.usecase.ObserveWallpaperStateUseCase
import com.github.reygnn.kolibri_launcher.domain.usecase.RecordAppLaunchUseCase
import com.github.reygnn.kolibri_launcher.domain.usecase.RefreshAppsUseCase
import com.github.reygnn.kolibri_launcher.domain.usecase.RequestLockUseCase
import com.github.reygnn.kolibri_launcher.domain.usecase.RequestNotificationsUseCase
import com.github.reygnn.kolibri_launcher.domain.usecase.ResetAppUsageUseCase
import com.github.reygnn.kolibri_launcher.domain.usecase.SaveWallpaperStateUseCase
import com.github.reygnn.kolibri_launcher.domain.usecase.SetChipBackgroundColorUseCase
import com.github.reygnn.kolibri_launcher.domain.usecase.SetContentTopMarginUseCase
import com.github.reygnn.kolibri_launcher.domain.usecase.SetFontBoldUseCase
import com.github.reygnn.kolibri_launcher.domain.usecase.SetLayoutScaleUseCase
import com.github.reygnn.kolibri_launcher.domain.usecase.SetTextColorUseCase
import com.github.reygnn.kolibri_launcher.domain.usecase.SetTextShadowEnabledUseCase
import com.github.reygnn.kolibri_launcher.domain.usecase.SetVerticalPaddingUseCase
import com.github.reygnn.kolibri_launcher.domain.usecase.SetWallpaperImageUseCase
import com.github.reygnn.kolibri_launcher.domain.usecase.ShowAppUseCase
import com.github.reygnn.kolibri_launcher.domain.usecase.ToggleFavoriteUseCase
import com.github.reygnn.kolibri_launcher.domain.usecase.ToggleSortOrderUseCase
import com.github.reygnn.kolibri_launcher.ui.base.BaseViewModel
import com.github.reygnn.kolibri_launcher.ui.base.UiEvent
import com.github.reygnn.kolibri_launcher.ui.base.UiState
import com.github.reygnn.kolibri_launcher.ui.main.delegate.AppManagementDelegate
import com.github.reygnn.kolibri_launcher.ui.main.delegate.ClockDelegate
import com.github.reygnn.kolibri_launcher.ui.main.delegate.DelegateScope
import com.github.reygnn.kolibri_launcher.ui.main.delegate.GestureDelegate
import com.github.reygnn.kolibri_launcher.ui.main.delegate.LayoutDelegate
import com.github.reygnn.kolibri_launcher.ui.main.delegate.ThemingDelegate
import com.github.reygnn.kolibri_launcher.ui.main.delegate.WallpaperDelegate
import com.github.reygnn.kolibri_launcher.core.AppUpdateSignal
import com.github.reygnn.kolibri_launcher.ui.util.TestMode
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
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
    requestLockUseCase: RequestLockUseCase,
    requestNotificationsUseCase: RequestNotificationsUseCase,
    recordAppLaunchUseCase: RecordAppLaunchUseCase,
    refreshAppsUseCase: RefreshAppsUseCase,
    resetAppUsageUseCase: ResetAppUsageUseCase,
    showAppUseCase: ShowAppUseCase,
    toggleSortOrderUseCase: ToggleSortOrderUseCase,
    handleSwipeActionUseCase: HandleSwipeActionUseCase,
    observeTimeBasedEventsUseCase: ObserveTimeBasedEventsUseCase,
    observeUiColorsUseCase: ObserveUiColorsUseCase,
    setTextColorUseCase: SetTextColorUseCase,
    setTextShadowEnabledUseCase: SetTextShadowEnabledUseCase,
    setChipBackgroundColorUseCase: SetChipBackgroundColorUseCase,
    observeInstalledAppsUseCase: ObserveInstalledAppsUseCase,
    getAutoLaunchSettingUseCase: GetAutoLaunchSettingUseCase,
    observeHomeSettingsUseCase: ObserveHomeSettingsUseCase,
    checkAppUsageUseCase: CheckAppUsageUseCase,
    getAutoShowKeyboardSettingUseCase: GetAutoShowKeyboardSettingUseCase,
    getTextShadowEnabledUseCase: GetTextShadowEnabledUseCase,
    getSplitModeThresholdUseCase: GetSplitModeThresholdUseCase,
    getLayoutSettingsUseCase: GetLayoutSettingsUseCase,
    setLayoutScaleUseCase: SetLayoutScaleUseCase,
    setVerticalPaddingUseCase: SetVerticalPaddingUseCase,
    setFontBoldUseCase: SetFontBoldUseCase,
    setContentTopMarginUseCase: SetContentTopMarginUseCase,
    observeWallpaperStateUseCase: ObserveWallpaperStateUseCase,
    saveWallpaperStateUseCase: SaveWallpaperStateUseCase,
    setWallpaperImageUseCase: SetWallpaperImageUseCase,
    clearWallpaperUseCase: ClearWallpaperUseCase,
    wallpaperFileManager: WallpaperFileManager,
    appUpdateSignal: AppUpdateSignal,
    private val savedStateHandle: SavedStateHandle,
    @param:ApplicationContext private val context: Context,
    @MainDispatcher mainDispatcher: CoroutineDispatcher,
    private val testMode: TestMode
) : BaseViewModel<UiEvent>(mainDispatcher) {

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
        savedStateHandle = savedStateHandle,
        scope = delegateScope
    )

    private val gestureDelegate = GestureDelegate(
        requestLockUseCase = requestLockUseCase,
        requestNotificationsUseCase = requestNotificationsUseCase,
        handleSwipeActionUseCase = handleSwipeActionUseCase,
        scope = delegateScope
    )

    private val themingDelegate = ThemingDelegate(
        observeUiColorsUseCase = observeUiColorsUseCase,
        setTextColorUseCase = setTextColorUseCase,
        setTextShadowEnabledUseCase = setTextShadowEnabledUseCase,
        setChipBackgroundColorUseCase = setChipBackgroundColorUseCase,
        getTextShadowEnabledUseCase = getTextShadowEnabledUseCase,
        scope = delegateScope
    )

    private val layoutDelegate = LayoutDelegate(
        getLayoutSettingsUseCase = getLayoutSettingsUseCase,
        getSplitModeThresholdUseCase = getSplitModeThresholdUseCase,
        setLayoutScaleUseCase = setLayoutScaleUseCase,
        setVerticalPaddingUseCase = setVerticalPaddingUseCase,
        setFontBoldUseCase = setFontBoldUseCase,
        setContentTopMarginUseCase = setContentTopMarginUseCase,
        scope = delegateScope
    )

    private val wallpaperDelegate = WallpaperDelegate(
        context = context,
        observeWallpaperStateUseCase = observeWallpaperStateUseCase,
        saveWallpaperStateUseCase = saveWallpaperStateUseCase,
        setWallpaperImageUseCase = setWallpaperImageUseCase,
        clearWallpaperUseCase = clearWallpaperUseCase,
        wallpaperFileManager = wallpaperFileManager,
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
    val maxFavoritesOnHome: StateFlow<Int> get() = appDelegate.maxFavoritesOnHome
    val appDrawerSearchQuery: StateFlow<String> get() = appDelegate.appDrawerSearchQuery

    val uiColorsState: StateFlow<UiColorsState> get() = themingDelegate.uiColorsState

    val layoutScaleState: StateFlow<Float> get() = layoutDelegate.layoutScaleState
    val verticalPaddingState: StateFlow<Float> get() = layoutDelegate.verticalPaddingState
    val isFontBoldState: StateFlow<Boolean> get() = layoutDelegate.isFontBoldState
    val contentTopMarginState: StateFlow<Float> get() = layoutDelegate.contentTopMarginState
    val splitModeThreshold: StateFlow<Int> get() = layoutDelegate.splitModeThreshold

    val wallpaperState: StateFlow<WallpaperState> get() = wallpaperDelegate.wallpaperState
    val isWallpaperEditMode: StateFlow<Boolean> get() = wallpaperDelegate.isWallpaperEditMode
    val pendingFocusLayerId: StateFlow<String?> get() = wallpaperDelegate.pendingFocusLayerId

    fun consumePendingFocusLayerId() = wallpaperDelegate.consumePendingFocusLayerId()

    val isLockingInProgress: StateFlow<Boolean> get() = gestureDelegate.isLockingInProgress

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
        // gestureDelegate und layoutDelegate brauchen kein start()
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
    fun getInitialBatteryState() = clockDelegate.refreshAll()

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
    fun onFlingDown() = gestureDelegate.onFlingDown()
    fun onSwipeFromRightToLeft() = gestureDelegate.onSwipeFromRightToLeft()
    fun onSwipeFromLeftToRight() = gestureDelegate.onSwipeFromLeftToRight()
    fun onLongPress() = gestureDelegate.onLongPress()
    fun onDoubleTapToLock() = gestureDelegate.onDoubleTapToLock()
    fun onTimeDoubleClick() = gestureDelegate.onTimeDoubleClick()
    fun onDateDoubleClick() = gestureDelegate.onDateDoubleClick()
    fun onBatteryDoubleClick() = gestureDelegate.onBatteryDoubleClick()

    // ===========================================
    // DELEGATED PUBLIC API — THEMING
    // ===========================================

    fun onSetTextColor(color: Int) = themingDelegate.onSetTextColor(color)
    fun onSetTextShadowEnabled(isEnabled: Boolean) = themingDelegate.onSetTextShadowEnabled(isEnabled)
    fun onSetChipBackgroundColor(color: Int) = themingDelegate.onSetChipBackgroundColor(color)
    fun updateUiColors(wallpaperColors: WallpaperColors? = null) = themingDelegate.updateUiColors(wallpaperColors)
    suspend fun isTextShadowEnabled(): Boolean = themingDelegate.isTextShadowEnabled()

    // ===========================================
    // DELEGATED PUBLIC API — LAYOUT
    // ===========================================

    fun onSetLayoutScale(scale: Float) = layoutDelegate.onSetLayoutScale(scale)
    fun onSetVerticalPadding(factor: Float) = layoutDelegate.onSetVerticalPadding(factor)
    fun onSetFontBold(isBold: Boolean) = layoutDelegate.onSetFontBold(isBold)
    fun onSetContentTopMargin(scale: Float) = layoutDelegate.onSetContentTopMargin(scale)
    fun onResetLayoutSettings() = layoutDelegate.onResetLayoutSettings()

    // ===========================================
    // DELEGATED PUBLIC API — WALLPAPER
    // ===========================================

    fun onSetWallpaperImage(imageUri: Uri) = wallpaperDelegate.onSetWallpaperImage(imageUri)
    fun onSaveWallpaperTransform(scale: Float, translateX: Float, translateY: Float) =
        wallpaperDelegate.onSaveWallpaperTransform(scale, translateX, translateY)
    fun onClearWallpaper() = wallpaperDelegate.onClearWallpaper()
    fun onSetWallpaperEditMode(enabled: Boolean) = wallpaperDelegate.onSetWallpaperEditMode(enabled)
    fun onToggleWallpaperEditMode() = wallpaperDelegate.onToggleWallpaperEditMode()
    fun onEnterWallpaperEditMode() = wallpaperDelegate.onEnterWallpaperEditMode()
    fun onCommitWallpaperEditMode() = wallpaperDelegate.onCommitWallpaperEditMode()
    fun onCancelWallpaperEditMode() = wallpaperDelegate.onCancelWallpaperEditMode()
    fun onAddWallpaperLayer(imageUri: Uri, label: String? = null) = wallpaperDelegate.onAddWallpaperLayer(imageUri, label)
    fun onRemoveWallpaperLayer(layerIndex: Int) = wallpaperDelegate.onRemoveWallpaperLayer(layerIndex)
    fun onSwapWallpaperLayers(indexA: Int, indexB: Int) = wallpaperDelegate.onSwapWallpaperLayers(indexA, indexB)
    fun onSaveLayerTransform(layerIndex: Int, scale: Float, translateX: Float, translateY: Float) =
        wallpaperDelegate.onSaveLayerTransform(layerIndex, scale, translateX, translateY)
    fun onSaveAllLayerTransforms(transforms: List<Triple<Float, Float, Float>>) =
        wallpaperDelegate.onSaveAllLayerTransforms(transforms)
    fun onSetLayerAlpha(layerIndex: Int, alpha: Float) = wallpaperDelegate.onSetLayerAlpha(layerIndex, alpha)
    fun onSetLayerBlendMode(layerIndex: Int, blendModeName: String?) = wallpaperDelegate.onSetLayerBlendMode(layerIndex, blendModeName)
    fun onSetLayerVisibility(layerIndex: Int, isVisible: Boolean) = wallpaperDelegate.onSetLayerVisibility(layerIndex, isVisible)

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