/*
 * Copyright (C) 2025 reygnn (Ulrich Kaufmann)
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */

package com.github.reygnn.kolibri_launcher.ui.main.delegate

import android.content.Context
import androidx.lifecycle.LiveData
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.asLiveData
import com.github.reygnn.kolibri_launcher.R
import com.github.reygnn.kolibri_launcher.core.AppConstants
import com.github.reygnn.kolibri_launcher.core.TimberWrapper
import com.github.reygnn.kolibri_launcher.domain.model.AppInfo
import com.github.reygnn.kolibri_launcher.domain.model.AppLoadResult
import com.github.reygnn.kolibri_launcher.domain.model.FavoriteAppsResult
import com.github.reygnn.kolibri_launcher.domain.model.HomeSettings
import com.github.reygnn.kolibri_launcher.domain.model.SortOrder
import com.github.reygnn.kolibri_launcher.domain.usecase.CheckAppUsageUseCase
import com.github.reygnn.kolibri_launcher.domain.usecase.GetAutoLaunchSettingUseCase
import com.github.reygnn.kolibri_launcher.domain.usecase.GetAutoShowKeyboardSettingUseCase
import com.github.reygnn.kolibri_launcher.domain.usecase.GetDrawerAppsUseCase
import com.github.reygnn.kolibri_launcher.domain.usecase.GetFavoriteAppsUseCase
import com.github.reygnn.kolibri_launcher.domain.usecase.HideAppUseCase
import com.github.reygnn.kolibri_launcher.domain.usecase.ObserveHomeSettingsUseCase
import com.github.reygnn.kolibri_launcher.domain.usecase.ObserveInstalledAppsUseCase
import com.github.reygnn.kolibri_launcher.domain.usecase.RecordAppLaunchUseCase
import com.github.reygnn.kolibri_launcher.domain.usecase.RefreshAppsUseCase
import com.github.reygnn.kolibri_launcher.domain.usecase.ResetAppUsageUseCase
import com.github.reygnn.kolibri_launcher.domain.usecase.ShowAppUseCase
import com.github.reygnn.kolibri_launcher.domain.usecase.ToggleFavoriteUseCase
import com.github.reygnn.kolibri_launcher.ui.util.toStringResId
import com.github.reygnn.kolibri_launcher.domain.usecase.ToggleSortOrderUseCase
import com.github.reygnn.kolibri_launcher.ui.base.UiEvent
import com.github.reygnn.kolibri_launcher.domain.model.UiState
import com.github.reygnn.kolibri_launcher.core.AppUpdateSignal
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map

/**
 * Delegate responsible for app management:
 * favorites, drawer, hide/show, sort order, app clicks,
 * usage tracking, search query, and installed-app observation.
 */
class AppManagementDelegate(
    private val context: Context,
    private val getFavoriteAppsUseCase: GetFavoriteAppsUseCase,
    private val getDrawerAppsUseCase: GetDrawerAppsUseCase,
    private val hideAppUseCase: HideAppUseCase,
    private val showAppUseCase: ShowAppUseCase,
    private val toggleFavoriteUseCase: ToggleFavoriteUseCase,
    private val toggleSortOrderUseCase: ToggleSortOrderUseCase,
    private val recordAppLaunchUseCase: RecordAppLaunchUseCase,
    private val refreshAppsUseCase: RefreshAppsUseCase,
    private val resetAppUsageUseCase: ResetAppUsageUseCase,
    private val observeInstalledAppsUseCase: ObserveInstalledAppsUseCase,
    private val observeHomeSettingsUseCase: ObserveHomeSettingsUseCase,
    private val getAutoLaunchSettingUseCase: GetAutoLaunchSettingUseCase,
    private val getAutoShowKeyboardSettingUseCase: GetAutoShowKeyboardSettingUseCase,
    private val checkAppUsageUseCase: CheckAppUsageUseCase,
    private val appUpdateSignal: AppUpdateSignal,
    private val savedStateHandle: SavedStateHandle,
    private val scope: DelegateScope
) {

    // --- Exposed State ---

    private val _favoriteAppsState = MutableStateFlow<UiState<FavoriteAppsResult>>(UiState.Loading)
    val favoriteAppsState: StateFlow<UiState<FavoriteAppsResult>> = _favoriteAppsState.asStateFlow()

    val drawerApps: LiveData<List<AppInfo>> = getDrawerAppsUseCase.drawerApps.asLiveData()

    private val _homeSettings = MutableStateFlow(HomeSettings())

    val sortOrder: LiveData<SortOrder> = _homeSettings
        .map { it.sortOrder }
        .asLiveData(scope.coroutineScope.coroutineContext)

    val appDrawerSearchQuery: StateFlow<String> =
        savedStateHandle.getStateFlow(AppConstants.KEY_SEARCH_QUERY, "")

    // --- Init ---

    fun start(isTestMode: Boolean) {
        if (!isTestMode) {
            // App-Updates beobachten
            scope.launchSafe("Error observing installed apps") {
                delay(AppConstants.INITIAL_APP_LOAD_DELAY_MS)
                observeInstalledAppsUseCase().collect { result ->
                    if (result is AppLoadResult.Error) {
                        scope.sendEvent(UiEvent.ShowToast(result.failure.toStringResId()))
                    }
                }
            }

            // Home-Settings beobachten
            scope.launchSafe("Error observing home settings") {
                observeHomeSettingsUseCase().collect { settings ->
                    _homeSettings.value = settings
                }
            }

            // App-Update-Signals (Package install/uninstall)
            listenForAppUpdates()
        }

        // Favoriten beobachten (immer, auch im Test-Mode)
        scope.launchSafe("Error observing favorite apps") {
            getFavoriteAppsUseCase.favoriteApps.collect { state ->
                handleFavoriteAppsState(state)
            }
        }
    }

    // --- Public API: App Actions ---

    fun onAppClicked(app: AppInfo) = scope.launchSafe("Error handling app click") {
        try {
            scope.sendEvent(UiEvent.LaunchApp(app))
            recordAppLaunchUseCase(app)
            refreshAppsUseCase()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            TimberWrapper.silentError(e, "Error handling app click")
            scope.sendEvent(UiEvent.ShowToast(R.string.error_launching_app))
        }
    }

    fun onToggleFavorite(app: AppInfo) = scope.launchSafe(
        errorMessage = "Error toggling favorite for ${app.packageName}",
        defaultErrorToast = R.string.error_generic
    ) {
        // Static home-favorites cap. The former dynamic "fits-on-screen" value
        // (fed via LauncherViewModel.onHomeViewMeasured) was intentionally
        // dropped when favorites moved to a scrollable RecyclerView (SplitScreen
        // refactor). The same constant is enforced in GetFavoriteAppsUseCase.
        val currentMax = AppConstants.MAX_FAVORITES_ON_HOME
        when (val result = toggleFavoriteUseCase(app, currentMax)) {
            is ToggleFavoriteUseCase.Result.Success -> {
                val message = context.getString(result.toStringResId(), app.displayName)
                scope.sendEvent(UiEvent.ShowToastFromString(message))
            }
            is ToggleFavoriteUseCase.Result.Error -> {
                val limit = (result as ToggleFavoriteUseCase.Result.Error.LimitReached).maxFavorites
                val message = context.getString(result.toStringResId(), limit)
                scope.sendEvent(UiEvent.ShowToastFromString(message))
            }
        }
    }

    fun onHideApp(app: AppInfo) = scope.launchSafe(
        errorMessage = "Error hiding app ${app.packageName}",
        defaultErrorToast = R.string.error_generic
    ) {
        hideAppUseCase(app)
        scope.sendEvent(
            UiEvent.ShowToastFromString(
                context.getString(R.string.app_now_hidden_in_drawer, app.displayName)
            )
        )
    }

    fun onShowApp(app: AppInfo) = scope.launchSafe(
        errorMessage = "Failed to show app ${app.packageName}",
        defaultErrorToast = R.string.error_generic
    ) {
        showAppUseCase(app)
        scope.sendEvent(
            UiEvent.ShowToastFromString(
                context.getString(R.string.app_now_visible_in_drawer, app.displayName)
            )
        )
    }

    fun onResetAppUsage(app: AppInfo) = scope.launchSafe(
        errorMessage = "Error resetting usage data for ${app.packageName}",
        defaultErrorToast = R.string.error_generic
    ) {
        resetAppUsageUseCase(app)
        scope.sendEvent(
            UiEvent.ShowToastFromString(
                context.getString(R.string.usage_data_reset_success, app.displayName)
            )
        )
    }

    // --- Public API: Sort & Search ---

    fun toggleSortOrder() = scope.launchSafe(
        errorMessage = "Error toggling sort order",
        defaultErrorToast = R.string.error_generic
    ) {
        toggleSortOrderUseCase()
    }

    fun onAppDrawerSearchQueryChanged(query: String) {
        savedStateHandle[AppConstants.KEY_SEARCH_QUERY] = query
    }

    fun onAppDrawerClosed() {
        savedStateHandle[AppConstants.KEY_SEARCH_QUERY] = ""
    }

    // --- Public API: Refresh ---

    fun refreshInstalledApps() = scope.launchSafe("Error refreshing installed apps") {
        refreshAppsUseCase()
    }

    // --- Public API: Settings Queries ---

    suspend fun isAutoLaunchEnabled(): Boolean {
        return getAutoLaunchSettingUseCase()
    }

    suspend fun isAutoShowKeyboardEnabled(): Boolean {
        return getAutoShowKeyboardSettingUseCase()
    }

    suspend fun hasUsageData(packageName: String?): Boolean {
        return checkAppUsageUseCase(packageName)
    }

    // --- Public API: Error Helpers ---

    fun onAppInfoError() = scope.launchSafe("Error opening app info") {
        scope.sendEvent(UiEvent.ShowToast(R.string.error_app_info_open))
    }

    fun onFavoriteAppsError(message: String) = scope.launchSafe("Error with favorite apps") {
        scope.sendEvent(UiEvent.ShowToastFromString(message))
    }

    // --- Internal ---

    private suspend fun handleFavoriteAppsState(state: UiState<FavoriteAppsResult>) {
        try {
            _favoriteAppsState.value = state

            val toastAlreadyShown =
                savedStateHandle.get<Boolean>(AppConstants.KEY_FALLBACK_TOAST_SHOWN) == true
            if (state is UiState.Success && state.data.isFallback && !toastAlreadyShown) {
                savedStateHandle[AppConstants.KEY_FALLBACK_TOAST_SHOWN] = true
                scope.sendEvent(UiEvent.ShowToast(R.string.welcome_toast_fallback_favorites))
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            TimberWrapper.silentError(e, "Error processing favorite apps state")
        }
    }

    private fun listenForAppUpdates() = scope.launchSafe("Error listening for app updates") {
        // Step 1 (RECONCILE_SPEC §9): behaviour-neutral. Every PackageEvent still
        // drives a full display refresh; the payload is not yet consulted.
        // Event-targeted reconcile (acting on Added/Removed differently) lands in
        // §3 — the world-diff cleanup stays in place until then.
        appUpdateSignal.events.collect {
            try {
                refreshAppsUseCase()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                TimberWrapper.silentError(e, "Error refreshing apps on update signal")
            }
        }
    }
}