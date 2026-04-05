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
import com.github.reygnn.kolibri_launcher.core.TimberWrapper
import com.github.reygnn.kolibri_launcher.domain.model.UiColorsState
import com.github.reygnn.kolibri_launcher.domain.usecase.GetTextShadowEnabledUseCase
import com.github.reygnn.kolibri_launcher.domain.usecase.ObserveUiColorsUseCase
import com.github.reygnn.kolibri_launcher.domain.usecase.SetChipBackgroundColorUseCase
import com.github.reygnn.kolibri_launcher.domain.usecase.SetTextColorUseCase
import com.github.reygnn.kolibri_launcher.domain.usecase.SetTextShadowEnabledUseCase
import com.github.reygnn.kolibri_launcher.ui.base.UiEvent
import kotlinx.coroutines.CancellationException
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
    private val scope: DelegateScope
) {

    // --- Exposed State ---

    private val _uiColorsState = MutableStateFlow(UiColorsState())
    val uiColorsState: StateFlow<UiColorsState> = _uiColorsState.asStateFlow()

    // --- Internal: WallpaperColors als Input für den UseCase ---

    private val wallpaperColorsFlow = MutableStateFlow<WallpaperColors?>(null)

    // --- Init ---

    fun start() {
        scope.launchSafe("Error observing UI colors") {
            observeUiColorsUseCase(wallpaperColorsFlow).collect { colorsState ->
                _uiColorsState.value = colorsState
            }
        }
    }

    // --- Public API: Update WallpaperColors ---

    fun updateUiColors(wallpaperColors: WallpaperColors? = null) {
        wallpaperColorsFlow.value = wallpaperColors
    }

    // --- Public API: Set Colors ---

    fun onSetTextColor(color: Int) = scope.launchSafe("Error setting text color") {
        try {
            setTextColorUseCase(color)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            TimberWrapper.silentError(e, "Error setting text color")
            scope.sendEvent(UiEvent.ShowToast(R.string.error_generic))
        }
    }

    fun onSetTextShadowEnabled(isEnabled: Boolean) = scope.launchSafe("Error setting text shadow") {
        try {
            setTextShadowEnabledUseCase(isEnabled)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            TimberWrapper.silentError(e, "Error setting text shadow")
            scope.sendEvent(UiEvent.ShowToast(R.string.error_generic))
        }
    }

    fun onSetChipBackgroundColor(color: Int) = scope.launchSafe("Error setting chip background") {
        try {
            setChipBackgroundColorUseCase(color)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            TimberWrapper.silentError(e, "Error setting chip background color")
            scope.sendEvent(UiEvent.ShowToast(R.string.error_generic))
        }
    }

    // --- Public API: Query ---

    suspend fun isTextShadowEnabled(): Boolean {
        return getTextShadowEnabledUseCase()
    }
}