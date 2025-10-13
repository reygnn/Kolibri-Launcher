/*
    * Copyright (C) 2025 reygnn (Ulrich Kaufmann)
    *
    * This program is free software: you can redistribute it and/or modify
    * it under the terms of the GNU General Public License as published by
    * the Free Software Foundation, either version 3 of the License, or
    * (at your option) any later version.
    */

package com.github.reygnn.kolibri_launcher

import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import timber.log.Timber
import javax.inject.Inject

@HiltViewModel
class SettingsViewModel @Inject constructor(
    private val installedAppsRepository: InstalledAppsRepository,
    @MainDispatcher mainDispatcher: CoroutineDispatcher
) : BaseViewModel(mainDispatcher) {

    private val _installedApps = MutableStateFlow<List<AppInfo>>(emptyList())
    val installedApps: StateFlow<List<AppInfo>> = _installedApps.asStateFlow()

    init {
        observeApps()
    }

    // CHANGED: launchSafe mit error handling
    private fun observeApps() {
        launchSafe(
            onError = { e ->
                TimberWrapper.silentError(e, "Error observing apps")
                sendEvent(UiEvent.ShowToast(R.string.error_loading_apps))
            }
        ) {
            installedAppsRepository.getInstalledApps().collect { apps ->
                try {
                    if (BuildConfig.DEBUG) {
                        Timber.d("[ViewModel] Collected ${apps.size} apps")
                    }
                    _installedApps.value = apps
                } catch (e: kotlinx.coroutines.CancellationException) {
                    throw e
                } catch (e: Exception) {
                    TimberWrapper.silentError(e, "Error updating installed apps")
                }
            }
        }
    }

    fun onAppListNotLoaded() {
        sendEvent(UiEvent.ShowToast(R.string.error_app_list_not_loaded))
        if (BuildConfig.DEBUG) {
            Timber.w("App list not loaded when action attempted")
        }
    }

    fun onNoFavoritesToSort() {
        sendEvent(UiEvent.ShowToast(R.string.no_favorites_to_sort))
    }

    fun onWallpaperSettingsFallback() {
        sendEvent(UiEvent.ShowToast(R.string.wallpaper_settings_fallback))
    }

    fun onErrorOpeningWallpaperSettings(e: Exception) {
        sendEvent(UiEvent.ShowToast(R.string.error_wallpaper_settings_open))
        TimberWrapper.silentError(e, "Error opening wallpaper settings")
    }

    fun onErrorOpeningAppInfo(e: Exception) {
        sendEvent(UiEvent.ShowToast(R.string.error_app_info_open))
        TimberWrapper.silentError(e, "Error opening app info")
    }

    fun onErrorOpeningAccessibilitySettings(e: Exception) {
        sendEvent(UiEvent.ShowToast(R.string.error_accessibility_settings_open))
        TimberWrapper.silentError(e, "Error opening accessibility settings")
    }

    fun onErrorOpeningDefaultLauncherSettings(e: Exception) {
        sendEvent(UiEvent.ShowToast(R.string.error_default_launcher_settings_open))
        TimberWrapper.silentError(e, "Error opening default launcher settings")
    }
}