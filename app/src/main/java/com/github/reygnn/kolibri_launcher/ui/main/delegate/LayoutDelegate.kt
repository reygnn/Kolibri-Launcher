/*
 * Copyright (C) 2025 reygnn (Ulrich Kaufmann)
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */

package com.github.reygnn.kolibri_launcher.ui.main.delegate

import com.github.reygnn.kolibri_launcher.core.AppConstants
import com.github.reygnn.kolibri_launcher.core.TimberWrapper
import com.github.reygnn.kolibri_launcher.core.coerceInSafe
import com.github.reygnn.kolibri_launcher.domain.usecase.GetLayoutSettingsUseCase
import com.github.reygnn.kolibri_launcher.domain.usecase.GetSplitModeThresholdUseCase
import com.github.reygnn.kolibri_launcher.domain.usecase.SetContentTopMarginUseCase
import com.github.reygnn.kolibri_launcher.domain.usecase.SetFontBoldUseCase
import com.github.reygnn.kolibri_launcher.domain.usecase.SetLayoutScaleUseCase
import com.github.reygnn.kolibri_launcher.domain.usecase.SetVerticalPaddingUseCase
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.stateIn

/**
 * Delegate responsible for layout settings:
 * scale, vertical padding, font bold, content top margin,
 * split mode threshold, and reset to defaults.
 */
class LayoutDelegate(
    getLayoutSettingsUseCase: GetLayoutSettingsUseCase,
    getSplitModeThresholdUseCase: GetSplitModeThresholdUseCase,
    private val setLayoutScaleUseCase: SetLayoutScaleUseCase,
    private val setVerticalPaddingUseCase: SetVerticalPaddingUseCase,
    private val setFontBoldUseCase: SetFontBoldUseCase,
    private val setContentTopMarginUseCase: SetContentTopMarginUseCase,
    private val scope: DelegateScope
) {

    // --- Exposed State ---

    val layoutScaleState: StateFlow<Float> = getLayoutSettingsUseCase.layoutScale
        .catch { e ->
            TimberWrapper.silentError(e, "Error observing layout scale")
            emit(AppConstants.DEFAULT_LAYOUT_SCALE)
        }
        .stateIn(
            scope = scope.coroutineScope,
            started = SharingStarted.Eagerly,
            initialValue = AppConstants.DEFAULT_LAYOUT_SCALE
        )

    val verticalPaddingState: StateFlow<Float> = getLayoutSettingsUseCase.verticalPadding
        .catch { e ->
            TimberWrapper.silentError(e, "Error observing vertical padding")
            emit(AppConstants.DEFAULT_VERTICAL_PADDING_FACTOR)
        }
        .stateIn(
            scope = scope.coroutineScope,
            started = SharingStarted.Eagerly,
            initialValue = AppConstants.DEFAULT_VERTICAL_PADDING_FACTOR
        )

    val isFontBoldState: StateFlow<Boolean> = getLayoutSettingsUseCase.isFontBold
        .catch { e ->
            TimberWrapper.silentError(e, "Error observing font bold")
            emit(AppConstants.DEFAULT_FONT_BOLD)
        }
        .stateIn(
            scope = scope.coroutineScope,
            started = SharingStarted.Eagerly,
            initialValue = AppConstants.DEFAULT_FONT_BOLD
        )

    val contentTopMarginState: StateFlow<Float> = getLayoutSettingsUseCase.contentTopMargin
        .catch { e ->
            TimberWrapper.silentError(e, "Error observing content top margin")
            emit(0f)
        }
        .stateIn(
            scope = scope.coroutineScope,
            started = SharingStarted.Eagerly,
            initialValue = 0f
        )

    /** 0 = Automatik (Android entscheidet) */
    val splitModeThreshold: StateFlow<Int> = getSplitModeThresholdUseCase()
        .catch { e ->
            TimberWrapper.silentError(e, "Error observing split mode threshold")
            emit(0)
        }
        .stateIn(
            scope = scope.coroutineScope,
            started = SharingStarted.WhileSubscribed(AppConstants.FLOW_SHARING_TIMEOUT_MS),
            initialValue = 0
        )

    // --- Public API: Setters ---

    fun onSetLayoutScale(scale: Float) = scope.launchSafe("Error setting layout scale") {
        setLayoutScaleUseCase(
            scale.coerceInSafe(
                AppConstants.LAYOUT_SCALE_MIN,
                AppConstants.LAYOUT_SCALE_MAX
            )
        )
    }

    fun onSetVerticalPadding(factor: Float) = scope.launchSafe("Error setting vertical padding") {
        setVerticalPaddingUseCase(
            factor.coerceInSafe(
                AppConstants.VERTICAL_PADDING_SCALE_MIN,
                AppConstants.VERTICAL_PADDING_SCALE_MAX
            )
        )
    }

    fun onSetFontBold(isBold: Boolean) = scope.launchSafe("Error setting font bold") {
        setFontBoldUseCase(isBold)
    }

    fun onSetContentTopMargin(scale: Float) = scope.launchSafe("Error setting content top margin") {
        setContentTopMarginUseCase(
            scale.coerceInSafe(
                AppConstants.CONTENT_TOP_MARGIN_SCALE_MIN,
                AppConstants.CONTENT_TOP_MARGIN_SCALE_MAX
            )
        )
    }

    fun onResetLayoutSettings() = scope.launchSafe("Error resetting layout settings") {
        setLayoutScaleUseCase(AppConstants.DEFAULT_LAYOUT_SCALE)
        setVerticalPaddingUseCase(AppConstants.DEFAULT_VERTICAL_PADDING_FACTOR)
        setFontBoldUseCase(AppConstants.DEFAULT_FONT_BOLD)
        setContentTopMarginUseCase(AppConstants.DEFAULT_TOP_MARGIN)
    }
}