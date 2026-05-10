package com.github.reygnn.kolibri_launcher.core

import com.github.reygnn.kolibri_launcher.domain.model.DomainWallpaperColors
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton

/**
 * App-wide signal carrying the **system** wallpaper's colour hints.
 * Bridge between `WallpaperManager.OnColorsChangedListener` (wired up
 * non-Hilt in `KolibriLauncherApp.onCreate`) and Hilt-managed
 * consumers (use cases, view models).
 *
 * StateFlow rather than SharedFlow so subscribers see the latest
 * value on subscription instead of waiting for the next system
 * change. Initial value is `null`; `KolibriLauncherApp` does an
 * initial poll of `WallpaperManager.getWallpaperColors(FLAG_SYSTEM)`
 * during startup so the first non-null emission lands before any
 * UI subscribes.
 *
 * `null` represents "system colour hints unknown" (either we
 * haven't polled yet, or the OS returned `null` for the system
 * wallpaper). Consumers should treat that as "no signal" and either
 * fall back to a default or wait for the next emission.
 */
@Singleton
open class SystemWallpaperColorsSignal @Inject constructor() {

    private val _colors = MutableStateFlow<DomainWallpaperColors?>(null)
    val colors: StateFlow<DomainWallpaperColors?> = _colors.asStateFlow()

    open fun emit(value: DomainWallpaperColors?) {
        _colors.value = value
    }
}
