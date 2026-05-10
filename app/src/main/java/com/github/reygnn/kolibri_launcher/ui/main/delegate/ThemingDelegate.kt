/*
 * Copyright (C) 2025 reygnn (Ulrich Kaufmann)
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */

package com.github.reygnn.kolibri_launcher.ui.main.delegate

import android.app.WallpaperColors
import com.github.reygnn.kolibri_launcher.R
import com.github.reygnn.kolibri_launcher.domain.model.AppDrawerSurfaceClassification
import com.github.reygnn.kolibri_launcher.domain.model.DomainWallpaperColors
import com.github.reygnn.kolibri_launcher.domain.model.ResolvedBackground
import com.github.reygnn.kolibri_launcher.domain.model.UiColorsState
import com.github.reygnn.kolibri_launcher.domain.usecase.GetTextShadowEnabledUseCase
import com.github.reygnn.kolibri_launcher.domain.usecase.ObserveUiColorsUseCase
import com.github.reygnn.kolibri_launcher.domain.usecase.ResolveAppDrawerSurfaceUseCase
import com.github.reygnn.kolibri_launcher.domain.usecase.SetChipBackgroundColorUseCase
import com.github.reygnn.kolibri_launcher.domain.usecase.SetTextColorUseCase
import com.github.reygnn.kolibri_launcher.domain.usecase.SetTextShadowEnabledUseCase
import com.github.reygnn.kolibri_launcher.ui.base.UiEvent
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Delegate responsible for UI theming:
 * color observation, text color, text shadow, chip background,
 * and wallpaper color extraction.
 */
class ThemingDelegate(
    private val observeUiColorsUseCase: ObserveUiColorsUseCase,
    private val setTextColorUseCase: SetTextColorUseCase,
    private val setTextShadowEnabledUseCase: SetTextShadowEnabledUseCase,
    private val setChipBackgroundColorUseCase: SetChipBackgroundColorUseCase,
    private val getTextShadowEnabledUseCase: GetTextShadowEnabledUseCase,
    private val resolveAppDrawerSurfaceUseCase: ResolveAppDrawerSurfaceUseCase,
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

    // --- Internal: WallpaperColors als Input für den UseCase ---

    private val wallpaperColorsFlow = MutableStateFlow<DomainWallpaperColors?>(null)

    // --- Init ---

    fun start() {
        scope.launchSafe("Error observing UI colors") {
            observeUiColorsUseCase(wallpaperColorsFlow).collect { colorsState ->
                _uiColorsState.value = colorsState
            }
        }
        scope.launchSafe("Error observing AppDrawer surface") {
            resolveAppDrawerSurfaceUseCase().collect { classification ->
                val color = when (classification) {
                    AppDrawerSurfaceClassification.LIGHT -> appDrawerSurfaceLightColor
                    AppDrawerSurfaceClassification.DARK -> appDrawerSurfaceDarkColor
                }
                _appDrawerSurfaceState.value = ResolvedBackground.SolidColor(color)
            }
        }
    }

    // --- Public API: Update WallpaperColors ---

    fun updateUiColors(wallpaperColors: WallpaperColors? = null) {
        wallpaperColorsFlow.value = wallpaperColors?.let {
            DomainWallpaperColors(
                supportsDarkText = (it.colorHints and WallpaperColors.HINT_SUPPORTS_DARK_TEXT) != 0,
                secondaryColorArgb = it.secondaryColor?.toArgb()
            )
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

    // --- Public API: Query ---

    suspend fun isTextShadowEnabled(): Boolean {
        return getTextShadowEnabledUseCase()
    }
}