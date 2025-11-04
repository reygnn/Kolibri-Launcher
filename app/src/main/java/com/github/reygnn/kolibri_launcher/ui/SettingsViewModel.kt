package com.github.reygnn.kolibri_launcher.ui

import com.github.reygnn.kolibri_launcher.data.AppInfo
import com.github.reygnn.kolibri_launcher.BuildConfig
import com.github.reygnn.kolibri_launcher.R
import com.github.reygnn.kolibri_launcher.ui.UiEvent
import com.github.reygnn.kolibri_launcher.core.TimberWrapper
import com.github.reygnn.kolibri_launcher.data.InstalledAppsRepository
import com.github.reygnn.kolibri_launcher.di.MainDispatcher
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
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
) : BaseViewModel<UiEvent>(mainDispatcher) {

    private val _installedApps = MutableStateFlow<List<AppInfo>>(emptyList())
    val installedApps: StateFlow<List<AppInfo>> = _installedApps.asStateFlow()

    init {
        observeApps()
    }

    private fun observeApps() {
        launchSafe {
            try {
                installedAppsRepository.getInstalledApps().collect { apps ->
                    try {
                        if (BuildConfig.DEBUG) {
                            Timber.Forest.d("[ViewModel] Collected ${apps.size} apps")
                        }
                        _installedApps.value = apps
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Throwable) {
                        TimberWrapper.silentError(e, "Error updating installed apps")
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                TimberWrapper.silentError(e, "Error observing apps")
                sendEvent(UiEvent.ShowToast(R.string.error_loading_apps))
            }
        }
    }

    fun onAppListNotLoaded() {
        launchSafe {
            sendEvent(UiEvent.ShowToast(R.string.error_app_list_not_loaded))
            if (BuildConfig.DEBUG) {
                Timber.Forest.w("App list not loaded when action attempted")
            }
        }
    }

    fun onNoFavoritesToSort() {
        launchSafe {
            sendEvent(UiEvent.ShowToast(R.string.no_favorites_to_sort))
        }
    }

    fun onWallpaperSettingsFallback() {
        launchSafe {
            sendEvent(UiEvent.ShowToast(R.string.wallpaper_settings_fallback))
        }
    }

    fun onErrorOpeningWallpaperSettings(e: Throwable) {
        launchSafe {
            sendEvent(UiEvent.ShowToast(R.string.error_wallpaper_settings_open))
            TimberWrapper.silentError(e, "Error opening wallpaper settings")
        }
    }

    fun onErrorOpeningAppInfo(e: Throwable) {
        launchSafe {
            sendEvent(UiEvent.ShowToast(R.string.error_app_info_open))
            TimberWrapper.silentError(e, "Error opening app info")
        }
    }

    fun onErrorOpeningAccessibilitySettings(e: Throwable) {
        launchSafe {
            sendEvent(UiEvent.ShowToast(R.string.error_accessibility_settings_open))
            TimberWrapper.silentError(e, "Error opening accessibility settings")
        }
    }

    fun onErrorOpeningDefaultLauncherSettings(e: Throwable) {
        launchSafe {
            sendEvent(UiEvent.ShowToast(R.string.error_default_launcher_settings_open))
            TimberWrapper.silentError(e, "Error opening default launcher settings")
        }
    }
}