/*
 * Copyright (C) 2025 reygnn (Ulrich Kaufmann)
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */

package com.github.reygnn.kolibri_launcher.ui.main.delegate

import com.github.reygnn.kolibri_launcher.domain.usecase.GetRecentAppsUseCase
import com.github.reygnn.kolibri_launcher.domain.usecase.HandleSwipeActionUseCase
import com.github.reygnn.kolibri_launcher.ui.base.UiEvent
import com.github.reygnn.kolibri_launcher.domain.model.SwipeSlot
import com.github.reygnn.kolibri_launcher.domain.model.TimeBasedEvent

/**
 * Delegate responsible for all gesture handling:
 * fling up, swipe down (recent apps), swipe left/right, double-tap
 * (upcoming-events dialog), long press, and double-click on clock/date/battery.
 */
class GestureDelegate(
    private val getRecentAppsUseCase: GetRecentAppsUseCase,
    private val currentTimeBasedEvents: () -> List<TimeBasedEvent>,
    private val handleSwipeActionUseCase: HandleSwipeActionUseCase,
    private val scope: DelegateScope
) {

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

    // --- Double tap: upcoming-events dialog ---

    /**
     * Opens the upcoming time-based events (alarms + calendar) in a dialog.
     *
     * Reads the already-collected snapshot from the clock delegate
     * ([currentTimeBasedEvents]) rather than re-querying — the list is exactly
     * what drives the home-screen events indicator, so the dialog can never show
     * something the indicator doesn't. When the list is empty (no indicator
     * shown) the gesture is a silent no-op; the absence of the indicator is the
     * feedback. This is a pure snapshot read with no suspension point, so no
     * `CancellationException` can arise inside the block.
     */
    fun onDoubleTap() = scope.launchSafe("Error on double tap") {
        val events = currentTimeBasedEvents()
        if (events.isNotEmpty()) {
            scope.sendEvent(UiEvent.ShowTimeBasedEventsDialog(events))
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
