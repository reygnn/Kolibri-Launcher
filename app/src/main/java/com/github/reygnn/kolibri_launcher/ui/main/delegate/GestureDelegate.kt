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
import com.github.reygnn.kolibri_launcher.domain.usecase.GetRecentAppsUseCase
import com.github.reygnn.kolibri_launcher.domain.usecase.ObserveDoubleTapClipboardSettingUseCase
import com.github.reygnn.kolibri_launcher.domain.usecase.HandleSwipeActionUseCase
import kotlinx.coroutines.flow.first
import com.github.reygnn.kolibri_launcher.ui.base.UiEvent
import com.github.reygnn.kolibri_launcher.domain.model.SwipeSlot

/**
 * Delegate responsible for all gesture handling:
 * fling up, swipe down (recent apps), swipe left/right, double-tap
 * (clipboard action), long press, and double-click on clock/date/battery.
 */
class GestureDelegate(
    private val getRecentAppsUseCase: GetRecentAppsUseCase,
    private val observeDoubleTapClipboardSettingUseCase: ObserveDoubleTapClipboardSettingUseCase,
    private val handleSwipeActionUseCase: HandleSwipeActionUseCase,
    private val scope: DelegateScope
) {

    // --- One-Time Toast Flags ---

    private var enableClipboardToastShown = false

    // --- Fling ---

    fun onFlingUp() = scope.launchSafe("Error on fling up") {
        scope.sendEvent(UiEvent.ShowAppDrawer)
    }

    // --- Swipe down: recent apps ---

    fun onSwipeDown() = scope.launchSafe("Error on swipe down") {
        // Fixed at 8 (the use case's default). MainActivity decides how to
        // present an empty list (fresh install / after a usage reset).
        scope.sendEvent(UiEvent.ShowRecentApps(getRecentAppsUseCase()))
    }

    // --- Swipe ---

    fun onSwipeFromRightToLeft() = scope.launchSafe("Error in onSwipeFromRightToLeft") {
        when (val result = handleSwipeActionUseCase(SwipeSlot.SWIPE_FROM_RIGHT_TO_LEFT)) {
            is HandleSwipeActionUseCase.Result.LaunchApp -> {
                scope.sendEvent(UiEvent.LaunchApp(result.app))
            }
            is HandleSwipeActionUseCase.Result.NoAction -> {
            }
        }
    }

    fun onSwipeFromLeftToRight() = scope.launchSafe("Error in onSwipeFromLeftToRight") {
        when (val result = handleSwipeActionUseCase(SwipeSlot.SWIPE_FROM_LEFT_TO_RIGHT)) {
            is HandleSwipeActionUseCase.Result.LaunchApp -> {
                scope.sendEvent(UiEvent.LaunchApp(result.app))
            }
            is HandleSwipeActionUseCase.Result.NoAction -> {
            }
        }
    }

    // --- Long Press ---

    fun onLongPress() = scope.launchSafe("Error on long press") {
        scope.sendEvent(UiEvent.ShowCustomizationOptions)
    }

    // --- Double tap: clipboard action ---

    /**
     * Opt-in (default OFF, see [com.github.reygnn.kolibri_launcher.core.AppConstants.DEFAULT_DOUBLE_TAP_CLIPBOARD]):
     * the gesture reads the clipboard and can forward its content to a search
     * provider, so it is never switched on behind the user's back. While
     * disabled the gesture points at the setting rather than doing nothing
     * silently — same shape the swipe-down and lock gestures used before they
     * were removed. The hint shows once per ViewModel/Activity session (the
     * flag is instance state on this delegate, which lives on
     * `LauncherViewModel`): it survives configuration changes, but resets when
     * the Activity is really destroyed.
     *
     * Reads the setting freshly here, and this is the ONLY reader of it: whether
     * the double tap consumes the touch sequence no longer depends on the
     * setting at all (a detected double tap always consumes — see
     * `HomeGestureLayout`), so there is no second consumer this read must agree
     * with, and no divergence window to design around. A stale read here at
     * worst shows the hint one extra time or misses the clipboard action once;
     * both self-correct on the next tap and neither can double up a dialog.
     */
    fun onDoubleTap() = scope.launchSafe("Error on double tap") {
        if (observeDoubleTapClipboardSettingUseCase().first()) {
            scope.sendEvent(UiEvent.PerformClipboardAction)
        } else if (!enableClipboardToastShown) {
            enableClipboardToastShown = true
            scope.sendEvent(UiEvent.ShowToast(R.string.toast_enable_double_tap_clipboard))
        }
    }

    // --- Double Click on Status Elements ---

    fun onTimeDoubleClick() = scope.launchSafe("Error on time double click") {
        scope.sendEvent(UiEvent.OpenClock)
    }

    fun onDateDoubleClick() = scope.launchSafe("Error on date double click") {
        scope.sendEvent(UiEvent.OpenCalendar)
    }

    fun onBatteryDoubleClick() = scope.launchSafe("Error on battery double click") {
        scope.sendEvent(UiEvent.OpenBatterySettings)
    }
}
