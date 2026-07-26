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
import com.github.reygnn.kolibri_launcher.domain.usecase.GetDoubleTapClipboardSettingUseCase
import com.github.reygnn.kolibri_launcher.domain.usecase.GetRecentAppsUseCase
import com.github.reygnn.kolibri_launcher.domain.usecase.ObserveDoubleTapClipboardSettingUseCase
import com.github.reygnn.kolibri_launcher.domain.usecase.HandleSwipeActionUseCase
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import com.github.reygnn.kolibri_launcher.ui.base.UiEvent
import com.github.reygnn.kolibri_launcher.domain.model.SwipeSlot

/**
 * Delegate responsible for all gesture handling:
 * fling up, swipe down (recent apps), swipe left/right, double-tap
 * (clipboard action), long press, and double-click on clock/date/battery.
 */
class GestureDelegate(
    private val getRecentAppsUseCase: GetRecentAppsUseCase,
    private val getDoubleTapClipboardSettingUseCase: GetDoubleTapClipboardSettingUseCase,
    private val observeDoubleTapClipboardSettingUseCase: ObserveDoubleTapClipboardSettingUseCase,
    private val handleSwipeActionUseCase: HandleSwipeActionUseCase,
    private val scope: DelegateScope
) {

    // --- One-Time Toast Flags ---

    private var enableClipboardToastShown = false

    private val _doubleTapConsumesGesture = MutableStateFlow(false)

    /**
     * Whether a double tap currently has an action behind it, i.e. whether the
     * clipboard setting is on.
     *
     * `HomeGestureLayout` needs this answer *synchronously*: it decides within
     * the same ACTION_DOWN whether the double tap consumes the follow-on
     * long-press and swipe, while the authoritative read is a suspend DataStore
     * call that only returns later. Hence a continuously observed copy rather
     * than a read at gesture time.
     *
     * It used to be a snapshot primed at construction and refreshed per tap,
     * which left it one gesture stale after any settings change — so the first
     * tap-tap-hold after enabling the feature showed the clipboard dialog and
     * then had it torn down by the customization dialog. Observing the flow
     * removes the window entirely; default `false` still applies until the
     * first value arrives, which is the safe direction.
     */
    val doubleTapConsumesGesture: StateFlow<Boolean> = _doubleTapConsumesGesture.asStateFlow()

    fun start() {
        scope.launchSafe("Error observing double-tap clipboard setting") {
            observeDoubleTapClipboardSettingUseCase().collect { isEnabled ->
                _doubleTapConsumesGesture.value = isEnabled
            }
        }
    }

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
     */
    fun onDoubleTap() = scope.launchSafe("Error on double tap") {
        if (getDoubleTapClipboardSettingUseCase()) {
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
