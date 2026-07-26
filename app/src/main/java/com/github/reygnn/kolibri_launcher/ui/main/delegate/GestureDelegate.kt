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
import com.github.reygnn.kolibri_launcher.domain.usecase.HandleSwipeActionUseCase
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
    private val handleSwipeActionUseCase: HandleSwipeActionUseCase,
    private val scope: DelegateScope
) {

    // --- One-Time Toast Flags ---

    private var enableClipboardToastShown = false

    /**
     * Whether a double tap currently has an action behind it, i.e. whether the
     * clipboard setting is on. `HomeGestureLayout` needs this answer
     * *synchronously* — it must decide within the same ACTION_DOWN whether to
     * suppress the follow-on long-press and swipe, and the authoritative read
     * is a suspend DataStore call that only returns later.
     *
     * Getting it wrong in the "off" direction is the expensive case: the
     * setting ships default-off, so every user would otherwise lose the
     * tap-tap-hold customization dialog to a gesture that does nothing.
     *
     * Primed at construction and refreshed on every [onDoubleTap], so it is
     * stale for at most one gesture after the setting is toggled in Settings
     * — and only for the *suppression* decision. Which action runs always
     * comes from the fresh read below, never from this snapshot.
     *
     * Main-thread confined: written from [scope]'s coroutines, read from the
     * touch dispatch.
     */
    var doubleTapConsumesGesture: Boolean = false
        private set

    init {
        scope.launchSafe("Error priming double-tap clipboard setting") {
            doubleTapConsumesGesture = getDoubleTapClipboardSettingUseCase()
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
        val isEnabled = getDoubleTapClipboardSettingUseCase()
        doubleTapConsumesGesture = isEnabled
        if (isEnabled) {
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
