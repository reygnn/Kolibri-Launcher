/*
 * Copyright (C) 2025 reygnn (Ulrich Kaufmann)
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */

package com.github.reygnn.kolibri_launcher.ui.main.delegate

import com.github.reygnn.kolibri_launcher.R
import com.github.reygnn.kolibri_launcher.core.AppConstants
import com.github.reygnn.kolibri_launcher.core.TimberWrapper
import com.github.reygnn.kolibri_launcher.core.coerceInSafe
import com.github.reygnn.kolibri_launcher.domain.model.LuminanceClassification
import com.github.reygnn.kolibri_launcher.domain.model.ResolvedBackground
import com.github.reygnn.kolibri_launcher.domain.model.UiColorsState
import com.github.reygnn.kolibri_launcher.domain.usecase.GetTextShadowEnabledUseCase
import com.github.reygnn.kolibri_launcher.domain.usecase.GetWallpaperScrimAlphaUseCase
import com.github.reygnn.kolibri_launcher.domain.usecase.ObserveUiColorsUseCase
import com.github.reygnn.kolibri_launcher.domain.usecase.ResolveWallpaperSurfaceUseCase
import com.github.reygnn.kolibri_launcher.domain.usecase.SetChipBackgroundColorUseCase
import com.github.reygnn.kolibri_launcher.domain.usecase.SetTextColorUseCase
import com.github.reygnn.kolibri_launcher.domain.usecase.SetTextShadowEnabledUseCase
import com.github.reygnn.kolibri_launcher.domain.usecase.SetWallpaperScrimAlphaUseCase
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.stateIn

/**
 * Delegate responsible for UI theming:
 * color observation, text color, text shadow, chip background,
 * and AppDrawer surface resolution.
 */
class ThemingDelegate(
    private val observeUiColorsUseCase: ObserveUiColorsUseCase,
    private val setTextColorUseCase: SetTextColorUseCase,
    private val setTextShadowEnabledUseCase: SetTextShadowEnabledUseCase,
    private val setChipBackgroundColorUseCase: SetChipBackgroundColorUseCase,
    private val getTextShadowEnabledUseCase: GetTextShadowEnabledUseCase,
    getWallpaperScrimAlphaUseCase: GetWallpaperScrimAlphaUseCase,
    private val setWallpaperScrimAlphaUseCase: SetWallpaperScrimAlphaUseCase,
    private val resolveAppDrawerSurfaceUseCase: ResolveWallpaperSurfaceUseCase,
    /**
     * The two pre-defined AppDrawer surface colours, resolved by the
     * ViewModel from `R.color.app_drawer_surface_*` and passed in as
     * raw ARGB ints. Doing the resource resolution outside the
     * delegate keeps `ThemingDelegate` Context-free.
     */
    private val appDrawerSurfaceLightColor: Int,
    private val appDrawerSurfaceDarkColor: Int,
    private val scope: DelegateScope
) {

    // --- Exposed State ---

    private val _uiColorsState = MutableStateFlow(UiColorsState())
    val uiColorsState: StateFlow<UiColorsState> = _uiColorsState.asStateFlow()

    private val _appDrawerSurfaceState =
        MutableStateFlow<ResolvedBackground>(ResolvedBackground.SolidColor(appDrawerSurfaceDarkColor))
    val appDrawerSurfaceState: StateFlow<ResolvedBackground> = _appDrawerSurfaceState.asStateFlow()

    /**
     * User-controlled home wallpaper scrim alpha (opt-in dim, default 0). Grouped
     * here with the text-shadow / readability controls; surfaced next to the
     * shadow toggle in the colors & shadow dialog. Cold flow → `stateIn` so it
     * needs no wiring in [start].
     */
    val wallpaperScrimAlphaState: StateFlow<Float> = getWallpaperScrimAlphaUseCase()
        .catch { e ->
            if (e is CancellationException) throw e
            TimberWrapper.silentError(e, "Error observing wallpaper scrim alpha")
            emit(AppConstants.DEFAULT_WALLPAPER_SCRIM_ALPHA)
        }
        .stateIn(
            scope = scope.coroutineScope,
            started = SharingStarted.Eagerly,
            initialValue = AppConstants.DEFAULT_WALLPAPER_SCRIM_ALPHA
        )

    // --- Init ---

    fun start() {
        scope.launchSafe("Error observing UI colors") {
            observeUiColorsUseCase().collect { colorsState ->
                _uiColorsState.value = colorsState
            }
        }
        scope.launchSafe("Error observing AppDrawer surface") {
            resolveAppDrawerSurfaceUseCase().collect { classification ->
                val color = when (classification) {
                    LuminanceClassification.LIGHT -> appDrawerSurfaceLightColor
                    LuminanceClassification.DARK -> appDrawerSurfaceDarkColor
                }
                _appDrawerSurfaceState.value = ResolvedBackground.SolidColor(color)
            }
        }
    }

    // --- Public API: Set Colors ---

    fun onSetTextColor(color: Int) = scope.launchSafe(
        errorMessage = "Error setting text color",
        defaultErrorToast = R.string.error_generic
    ) {
        setTextColorUseCase(color)
    }

    fun onSetTextShadowEnabled(isEnabled: Boolean) = scope.launchSafe(
        errorMessage = "Error setting text shadow",
        defaultErrorToast = R.string.error_generic
    ) {
        setTextShadowEnabledUseCase(isEnabled)
    }

    fun onSetChipBackgroundColor(color: Int) = scope.launchSafe(
        errorMessage = "Error setting chip background color",
        defaultErrorToast = R.string.error_generic
    ) {
        setChipBackgroundColorUseCase(color)
    }

    fun onSetWallpaperScrimAlpha(alpha: Float) = scope.launchSafe(
        errorMessage = "Error setting wallpaper scrim alpha",
        defaultErrorToast = R.string.error_generic
    ) {
        setWallpaperScrimAlphaUseCase(
            alpha.coerceInSafe(
                AppConstants.WALLPAPER_SCRIM_ALPHA_MIN,
                AppConstants.WALLPAPER_SCRIM_ALPHA_MAX
            )
        )
    }

    // --- Public API: Query ---

    suspend fun isTextShadowEnabled(): Boolean {
        return getTextShadowEnabledUseCase()
    }
}