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
import com.github.reygnn.kolibri_launcher.domain.usecase.HandleSwipeActionUseCase
import com.github.reygnn.kolibri_launcher.domain.usecase.RequestLockUseCase
import com.github.reygnn.kolibri_launcher.domain.usecase.RequestNotificationsUseCase
import com.github.reygnn.kolibri_launcher.ui.base.UiEvent
import com.github.reygnn.kolibri_launcher.ui.swipeactions.SwipeSlot
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Delegate responsible for all gesture handling:
 * fling up/down, swipe left/right, long press,
 * double-tap to lock, and double-click on clock/date/battery.
 */
class GestureDelegate(
    private val requestLockUseCase: RequestLockUseCase,
    private val requestNotificationsUseCase: RequestNotificationsUseCase,
    private val handleSwipeActionUseCase: HandleSwipeActionUseCase,
    private val scope: DelegateScope
) {

    // --- Exposed State ---

    private val _isLockingInProgress = MutableStateFlow(false)
    val isLockingInProgress: StateFlow<Boolean> = _isLockingInProgress.asStateFlow()

    // --- One-Time Toast Flags ---

    private var enableLockToastShown = false
    private var enableSwipeDownToastShown = false

    // --- Fling ---

    fun onFlingUp() = scope.launchSafe("Error on fling up") {
        scope.sendEvent(UiEvent.ShowAppDrawer)
    }

    fun onFlingDown() = scope.launchSafe("Error on fling down") {
        when (requestNotificationsUseCase()) {
            is RequestNotificationsUseCase.Result.Success -> {
            }

            is RequestNotificationsUseCase.Result.ErrorAccessibility -> {
                scope.sendEvent(UiEvent.ShowAccessibilityDialog)
            }

            is RequestNotificationsUseCase.Result.ErrorDisabled -> {
                if (!enableSwipeDownToastShown) {
                    enableSwipeDownToastShown = true
                    scope.sendEvent(UiEvent.ShowToast(R.string.toast_enable_swipe_down_to_notifications))
                }
            }

            is RequestNotificationsUseCase.Result.ErrorGeneric -> {
                scope.sendEvent(UiEvent.ShowToast(R.string.error_generic))
            }
        }
    }

    // --- Swipe ---

    fun onSwipeFromRightToLeft() = scope.launchSafe("Error in onSwipeFromRightToLeft") {
        try {
            when (val result = handleSwipeActionUseCase(SwipeSlot.SWIPE_FROM_RIGHT_TO_LEFT)) {
                is HandleSwipeActionUseCase.Result.LaunchApp -> {
                    scope.sendEvent(UiEvent.LaunchApp(result.app))
                }
                is HandleSwipeActionUseCase.Result.NoAction -> {
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            TimberWrapper.silentError(e, "Error in onSwipeFromRightToLeft")
        }
    }

    fun onSwipeFromLeftToRight() = scope.launchSafe("Error in onSwipeFromLeftToRight") {
        try {
            when (val result = handleSwipeActionUseCase(SwipeSlot.SWIPE_FROM_LEFT_TO_RIGHT)) {
                is HandleSwipeActionUseCase.Result.LaunchApp -> {
                    scope.sendEvent(UiEvent.LaunchApp(result.app))
                }
                is HandleSwipeActionUseCase.Result.NoAction -> {
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            TimberWrapper.silentError(e, "Error in onSwipeFromLeftToRight")
        }
    }

    // --- Long Press ---

    fun onLongPress() = scope.launchSafe("Error on long press") {
        scope.sendEvent(UiEvent.ShowCustomizationOptions)
    }

    // --- Double Tap ---

    fun onDoubleTapToLock() = scope.launchSafe("Error on double tap to lock") {
        when (requestLockUseCase()) {
            is RequestLockUseCase.Result.Success -> {
                _isLockingInProgress.value = true
                delay(AppConstants.LOCK_GESTURE_BLOCK_DURATION_MS)
                _isLockingInProgress.value = false
            }

            is RequestLockUseCase.Result.ErrorAccessibility -> {
                scope.sendEvent(UiEvent.ShowAccessibilityDialog)
            }

            is RequestLockUseCase.Result.ErrorDisabled -> {
                if (!enableLockToastShown) {
                    enableLockToastShown = true
                    scope.sendEvent(UiEvent.ShowToast(R.string.toast_enable_double_tap_to_lock))
                }
            }

            is RequestLockUseCase.Result.ErrorGeneric -> {
            }
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