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
import com.github.reygnn.kolibri_launcher.domain.usecase.HandleSwipeActionUseCase
import com.github.reygnn.kolibri_launcher.domain.usecase.RequestLockUseCase
import com.github.reygnn.kolibri_launcher.domain.usecase.RequestNotificationsUseCase
import com.github.reygnn.kolibri_launcher.ui.base.UiEvent
import com.github.reygnn.kolibri_launcher.domain.model.SwipeSlot
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
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

    /**
     * Drives the lock-transition black overlay's visibility. Separate
     * from [isLockingInProgress] (the two flags answer different
     * questions); see [onDoubleTapToLock]'s KDoc for the full design
     * rationale.
     *
     * Authoritative for the dismiss path. NOT authoritative for the
     * paint-before-lock path: there `HomeFragment` sets the view's
     * visibility synchronously off [lockPaintTrigger] before
     * attaching the OneShotPreDrawListener, so the trigger collector
     * doesn't depend on the StateFlow collector having run first.
     * The StateFlow's `true` emission is still useful (it keeps the
     * dismiss path symmetric and ensures any future direct
     * subscriber observes the overlay state), but it is not the
     * mechanism that the Pre-Draw synchronization relies on. See
     * [onDoubleTapToLock]'s KDoc, "The two-channel split".
     */
    private val _showLockOverlay = MutableStateFlow(false)
    val showLockOverlay: StateFlow<Boolean> = _showLockOverlay.asStateFlow()

    /**
     * One-shot trigger consumed by `HomeFragment` to drive the
     * Pre-Draw-synchronized lock IPC. Emits exactly once per
     * accepted [onDoubleTapToLock] call (after the gating flags
     * have flipped to true). The collector synchronously sets the
     * overlay visibility, attaches a `OneShotPreDrawListener`, and
     * arms a `postDelayed` fallback; the actual lock IPC is fired
     * from inside the listener via [executeLockAfterOverlayPaint]
     * so the overlay's first frame reaches SurfaceFlinger before
     * the keyguard's. Full rationale on [onDoubleTapToLock]'s KDoc.
     *
     * `extraBufferCapacity = 1, BufferOverflow.DROP_OLDEST` so a
     * tap that fires before the (lifecycle-bound) collector is
     * subscribed doesn't suspend the delegate's coroutine. A
     * dropped emission means a missed Pre-Draw sync, not a missed
     * lock — the actual lock IPC is gated by
     * [executeLockAfterOverlayPaint], which the collector calls.
     * Pattern follows `TESTING_CONVENTIONS.kt` →
     * "MUTABLESHAREDFLOW IN CONSTRUCTOR".
     */
    private val _lockPaintTrigger = MutableSharedFlow<Unit>(
        extraBufferCapacity = 1,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )
    val lockPaintTrigger: SharedFlow<Unit> = _lockPaintTrigger.asSharedFlow()

    /**
     * Reset hook called from `HomeFragment.onPause`. See
     * [onDoubleTapToLock]'s KDoc for why dismissal happens at this
     * lifecycle point and why the alternatives were rejected.
     *
     * Wired to `onPause` unconditionally — i.e., also fires for
     * non-lock pauses (Onboarding launch, AppDrawer push, Settings
     * open). On those paths [_showLockOverlay] is already false and
     * the assignment is a no-op; the breadth is intentional, not
     * something to narrow with a guard.
     */
    fun dismissLockOverlay() {
        _showLockOverlay.value = false
    }

    // --- One-Time Toast Flags ---

    private var enableLockToastShown = false
    private var enableSwipeDownToastShown = false

    // --- Fling ---
    //
    // Each directional handler short-circuits while a lock animation
    // is in progress. Moved here from `HomeFragment.createGestureListener`
    // (homescroll.md §8 decision 6) so the gate is JVM-testable and
    // applies regardless of which UI surface invoked the action.

    fun onFlingUp() = scope.launchSafe("Error on fling up") {
        if (_isLockingInProgress.value) return@launchSafe
        scope.sendEvent(UiEvent.ShowAppDrawer)
    }

    fun onFlingDown() = scope.launchSafe("Error on fling down") {
        if (_isLockingInProgress.value) return@launchSafe
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
        if (_isLockingInProgress.value) return@launchSafe
        when (val result = handleSwipeActionUseCase(SwipeSlot.SWIPE_FROM_RIGHT_TO_LEFT)) {
            is HandleSwipeActionUseCase.Result.LaunchApp -> {
                scope.sendEvent(UiEvent.LaunchApp(result.app))
            }
            is HandleSwipeActionUseCase.Result.NoAction -> {
            }
        }
    }

    fun onSwipeFromLeftToRight() = scope.launchSafe("Error in onSwipeFromLeftToRight") {
        if (_isLockingInProgress.value) return@launchSafe
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

    // --- Double Tap ---

    /**
     * Handles the double-tap-to-lock gesture on the home screen.
     *
     * ## Behavior
     *
     * Two quick taps on the home screen ask the launcher to lock the
     * device. The flow (split between this method and
     * [executeLockAfterOverlayPaint]):
     *
     *     onDoubleTapToLock              ← here
     *       sets gating flags + emits lockPaintTrigger
     *
     *     HomeFragment.scheduleLockAfterOverlayPaint
     *       sets overlay view visible synchronously
     *       attaches OneShotPreDrawListener (+ postDelayed fallback)
     *       — waits for the View pipeline ——————————
     *
     *     executeLockAfterOverlayPaint   ← fired from the listener
     *       → RequestLockUseCase (gates on the user's
     *         doubleTapToLock setting and on the AccessibilityService
     *         being connected)
     *       → ScreenLockRepository.requestLock — emits Unit on a
     *         SharedFlow
     *       → ScreenLockAccessibilityService collects the emit and
     *         calls performGlobalAction(GLOBAL_ACTION_LOCK_SCREEN)
     *
     * The accessibility-service route is the only path a regular app
     * has to lock the screen on Android 14+ short of becoming a
     * device-admin — see ScreenLockAccessibilityService's class KDoc
     * for the full argument.
     *
     * ## Why this method has a long KDoc
     *
     * The naive implementation is "fire the use case, done." That
     * worked functionally but produced a visible wallpaper flicker on
     * every double-tap loud enough to be obvious to anyone sensitive
     * to UI polish. Three mitigation attempts were tried; the third
     * (overlay + onPause dismissal) cleared the perceptual axis on
     * Pixel 9a but only because the IPC chain to the keyguard
     * happened to be longer than one frame on that device. A
     * fourth iteration (`OneShotPreDrawListener`) made that
     * frame-ordering deterministic. The history below is here so a
     * future maintainer doesn't relitigate the rejected ones.
     *
     * ## The wallpaper-flicker problem this method mitigates
     *
     * GLOBAL_ACTION_LOCK_SCREEN is NOT equivalent to the hardware
     * power button. The power button calls PowerManager.goToSleep()
     * at the HAL — display goes off directly, the keyguard window
     * only appears on the next wake. The accessibility action runs
     * the standard "go to lock" flow instead: the system slides the
     * keyguard window OVER the activity FIRST, then dims the display.
     *
     * On Pixel 9a (and most stock Android 14+ devices), the keyguard
     * slide-in is animated as a fade. During the fade, the keyguard
     * wallpaper visibly appears over the activity window. If the user
     * has a different wallpaper on the lockscreen than on the home
     * screen — the common case — the eye sees an unmistakable
     * wallpaper change before the display goes off. The original
     * Kolibri behavior triggered this on every double-tap.
     *
     * ## The mitigation: a black overlay paint-synced with the IPC
     *
     * Before issuing the lock request, this method flags the overlay
     * to become visible AND emits [lockPaintTrigger]. HomeFragment
     * observes the trigger, sets the lockTransitionOverlay view
     * (a full-screen black View at the top of fragment_home.xml's
     * Z-order) visible synchronously, attaches a
     * `OneShotPreDrawListener`, and only fires the actual lock IPC
     * (via [executeLockAfterOverlayPaint]) from inside that
     * listener.
     *
     * Why the listener: setting `view.isVisible = true` schedules a
     * frame; it does not paint pixels. The frame goes through
     * measure → layout → draw → RenderThread → GPU → SurfaceFlinger.
     * The Pre-Draw listener fires *inside* the frame pipeline, after
     * layout, just before the draw is recorded — at that point the
     * overlay is guaranteed to be in the next composed frame. The
     * lock IPC then races to the keyguard's surface through
     * accessibility → system_server → KeyguardController →
     * WindowManager → SurfaceFlinger, a much longer chain. The
     * overlay frame wins, deterministically.
     *
     * Without the Pre-Draw sync, the overlay frame and the keyguard
     * frame raced and the outcome depended on device speed, refresh
     * rate, and IPC scheduling — empirically OK on Pixel 9a, not
     * guaranteed everywhere.
     *
     * The overlay can mask the activity content (homescreen
     * wallpaper, favorites, clock) but it CANNOT mask the keyguard
     * window itself — the keyguard is a system-level window above
     * all app windows, so once it starts fading in, the user will
     * see the lockscreen wallpaper appear regardless of what the
     * activity below is showing. What the overlay can do is change
     * WHAT the keyguard is fading IN OVER — and that turns out to
     * be perceptually decisive.
     *
     * ## The two-channel split
     *
     * The overlay's visibility flows on two channels deliberately:
     *
     *   - [showLockOverlay] (StateFlow). Authoritative for the
     *     **dismiss** path. HomeFragment Observer 9 toggles
     *     visibility off this. `dismissLockOverlay()` flips it false
     *     on onPause and on every error branch in
     *     [executeLockAfterOverlayPaint].
     *
     *   - [lockPaintTrigger] (SharedFlow). Authoritative for the
     *     **paint-before-lock** path. HomeFragment's trigger
     *     collector sets visibility synchronously (without waiting
     *     for the StateFlow collector to run), then attaches the
     *     OneShotPreDrawListener.
     *
     * Asymmetric, but bewusst. If both channels drove visibility
     * symmetrically, the trigger collector would have to assume
     * Observer 9 ran first within the same dispatch tick — which is
     * not guaranteed. By having the trigger collector set
     * visibility itself, the order doesn't matter.
     *
     * ## Two flags for two concerns
     *
     *   - [_isLockingInProgress] — "should subsequent gestures
     *     (swipes, long-press) be ignored?". Re-firing the lock or
     *     opening the app drawer in the half-second the system
     *     spends animating to locked is unwanted. Resets after
     *     [AppConstants.LOCK_GESTURE_BLOCK_DURATION_MS] (1 s) on the
     *     success path; reset immediately on every error path. Set
     *     here in [onDoubleTapToLock], NOT in
     *     [executeLockAfterOverlayPaint], because the gate must
     *     close immediately — not a frame later — so a quick
     *     follow-up gesture can't slip through the Pre-Draw window.
     *
     *   - [_showLockOverlay] — "should the lock-transition black
     *     overlay be on screen?". Set true here for the StateFlow
     *     channel; the actual visibility on the View is set by
     *     HomeFragment's trigger collector (see above). Lifecycle-
     *     bound on the way out: stays true until HomeFragment
     *     dismisses it on onPause, when the keyguard has taken
     *     focus. A watchdog
     *     (`LOCK_OVERLAY_WATCHDOG_DURATION_MS` after the gesture-
     *     block delay on the success path) acts as a floor for the
     *     case where onPause never fires — see the closing section
     *     below for the failure mode it covers.
     *
     * ## Three dismissal timings, one survives
     *
     * Choosing when to dismiss the overlay was the hard part. Three
     * approaches were tried on the user's Pixel 9a; only the third
     * produced a defensible result.
     *
     *   1. **Timer-only (single flag, reset after
     *      LOCK_GESTURE_BLOCK_DURATION_MS).** The 1 s gesture-block
     *      timer expired before the system's keyguard slide-in had
     *      even started, so the overlay disappeared while the
     *      homescreen was still on screen. Visible result: black
     *      → homescreen → lockscreen flash → display off. Worse on
     *      the homescreen-flash axis than the no-mitigation
     *      baseline, because the overlay introduced a black-then-
     *      homescreen pair where there used to be just homescreen.
     *
     *   2. **onResume dismissal (overlay held through
     *      lock/off/wake/unlock).** The overlay stayed visible
     *      during the keyguard slide-in, so the keyguard faded in
     *      OVER solid black. Wallpaper-over-black is a sharp
     *      brightness/contrast jump and the keyguard wallpaper
     *      "popped" into view very noticeably. Visible result:
     *      black → lockscreen-wallpaper-pop → display off. Worse
     *      on the lockscreen-flash axis than every other variant.
     *
     *   3. **onPause dismissal (the choice in this file).** onPause
     *      fires on Pixel right around the time the keyguard takes
     *      focus — early in the slide-in. The overlay disappears
     *      just before the slide-in becomes prominent, so the
     *      keyguard crossfades from the homescreen wallpaper to the
     *      lockscreen wallpaper. Wallpaper-to-wallpaper is a soft
     *      transition that sits at or below the perception
     *      threshold for most taps.
     *
     * The unsatisfying part: there is no purely correct answer for
     * the keyguard fade-in itself. The Pre-Draw sync now removes
     * the variability of "did the overlay frame arrive in time" —
     * but the keyguard slide-in remains system-controlled. We are
     * choosing which transition the eye sees (wallpaper-to-
     * wallpaper soft) over which it does not (black-to-wallpaper
     * hard). A future Android release that changes the keyguard
     * animation timing could shift the equilibrium and require the
     * dismissal point to move — re-evaluate this section if a
     * future Pixel build alters the perceived flow.
     *
     * ## Body sequencing
     *
     * Both flags flip to true BEFORE the trigger emits, so on the
     * lifecycle-bound StateFlow side `showLockOverlay` is already
     * `true` if a subscriber observes between flag-set and trigger-
     * collector. The trigger emits last, so HomeFragment's collector
     * sees both flags already set. On the success branch in
     * [executeLockAfterOverlayPaint] the flags then diverge:
     * [_isLockingInProgress] resets after the gesture-block delay,
     * [_showLockOverlay] is intentionally NOT reset together with it
     * and waits for the lifecycle hook (with the watchdog as a
     * floor; see [executeLockAfterOverlayPaint]).
     *
     * Note that flipping [_isLockingInProgress] true BEFORE the use
     * case (which now lives in [executeLockAfterOverlayPaint]) is a
     * behavior change vs. the pre-overlay version, which gated only
     * the success branch. Other gesture handlers (`onFlingUp`,
     * `onFlingDown`, `onSwipe*`) now short-circuit during the use-
     * case validation window AND during the Pre-Draw frame too. In
     * practice this is sub-frame plus sub-ms, so it only affects
     * truly back-to-back gestures.
     *
     * ## What this method does NOT solve
     *
     *   - The keyguard fade-in itself. System-level, unmaskable. We
     *     pick the least-jarring backdrop (homescreen wallpaper).
     *   - Live-wallpaper initialization on the lockscreen. If the
     *     user's lockscreen runs a live wallpaper that takes time to
     *     bind, the first frame the keyguard renders may be its
     *     default-color bitmap before the live content kicks in.
     *     Outside the app's reach.
     *   - Edge case: a lock request that returns Success but the
     *     system then silently doesn't lock (e.g., service-binding
     *     loss between the emit and the performGlobalAction call).
     *     Without a fallback the overlay would stay until the next
     *     onPause, which never comes if the activity stays in
     *     foreground. Mitigated by the watchdog `delay
     *     (LOCK_OVERLAY_WATCHDOG_DURATION_MS)` after the
     *     gesture-block delay on the success branch in
     *     [executeLockAfterOverlayPaint] — worst case becomes ~3 s
     *     of black instead of "black until next app switch". On the
     *     normal path onPause has already dismissed the overlay and
     *     the watchdog assignment is a no-op.
     *
     * ## Related sites
     *
     *   - [executeLockAfterOverlayPaint] — the use-case half, fired
     *     from the Pre-Draw listener.
     *   - [showLockOverlay] / [_showLockOverlay] — StateFlow
     *     surface, dismiss-path authoritative.
     *   - [lockPaintTrigger] / [_lockPaintTrigger] — SharedFlow,
     *     paint-before-lock-path authoritative.
     *   - [dismissLockOverlay] — reset hook from
     *     `HomeFragment.onPause`.
     *   - HomeFragment.observeViewModel — "Observer 9: Lock-
     *     transition overlay" (dismiss path) and
     *     "Observer 10: Lock paint trigger" (paint-before-lock
     *     path).
     *   - HomeFragment.scheduleLockAfterOverlayPaint — the
     *     OneShotPreDrawListener call site.
     *   - HomeFragment.onPause — invokes [dismissLockOverlay].
     *   - res/layout/fragment_home.xml — the lockTransitionOverlay
     *     View.
     *   - ScreenLockAccessibilityService — the consumer of the
     *     SharedFlow-emitted lock request.
     *   - ACCEPTED_LIMITATIONS.md §1 — the residual single-frame
     *     wallpaper pop-in this method does not (and cannot) fully
     *     close, with the "why we stopped here" rationale.
     */
    fun onDoubleTapToLock() = scope.launchSafe("Error on double tap to lock") {
        _isLockingInProgress.value = true
        _showLockOverlay.value = true
        _lockPaintTrigger.emit(Unit)
    }

    /**
     * The use-case half of the double-tap-to-lock gesture. Fired
     * from `HomeFragment.scheduleLockAfterOverlayPaint`'s
     * OneShotPreDrawListener (or its postDelayed fallback) so that
     * the overlay's first frame is guaranteed to reach SurfaceFlinger
     * before the keyguard's. See [onDoubleTapToLock]'s KDoc for the
     * full design rationale.
     *
     * ## Synchronization contract
     *
     * The Pre-Draw guarantee is that the overlay is *in the current
     * frame's display list* when this method is invoked. It is NOT
     * a guarantee that the overlay's pixels are already on the GPU
     * or on the screen. The frame still has to finish rendering on
     * RenderThread before SurfaceFlinger composes it. So this
     * method must call [requestLockUseCase] without introducing any
     * suspension boundary that delays the lock IPC by more than a
     * sub-frame; otherwise the keyguard's IPC chain — which started
     * the moment we returned from the listener — could win the race
     * to SurfaceFlinger after all.
     *
     * In practice [requestLockUseCase] suspends at most on a
     * cache-warm DataStore read; the true IPC (`SharedFlow.emit` →
     * Service collector → `performGlobalAction`) goes out
     * effectively immediately on the same dispatch tick. If a
     * future refactor adds a `withContext(IO)` or a longer
     * suspension before [requestLockUseCase], the Pre-Draw
     * guarantee starts leaking — re-evaluate the sync model first.
     */
    fun executeLockAfterOverlayPaint() =
        scope.launchSafe("Error executing lock after overlay paint") {
            when (requestLockUseCase()) {
                is RequestLockUseCase.Result.Success -> {
                    delay(AppConstants.LOCK_GESTURE_BLOCK_DURATION_MS)
                    _isLockingInProgress.value = false
                    // Watchdog. Normally the overlay is dismissed by
                    // HomeFragment.onPause when the keyguard takes
                    // focus. If the system silently fails to lock
                    // (Success returned but performGlobalAction lost
                    // the call, service unbinding race, etc.), onPause
                    // never fires and the overlay would stay black
                    // until the next foreground change. Fall back to
                    // dismissing it here after a generous delay so the
                    // worst case becomes "~3 s of black" instead of
                    // "black until app switch". On the normal path
                    // onPause has already set the flag to false; this
                    // assignment is a no-op.
                    delay(AppConstants.LOCK_OVERLAY_WATCHDOG_DURATION_MS)
                    _showLockOverlay.value = false
                }

                is RequestLockUseCase.Result.ErrorAccessibility -> {
                    _isLockingInProgress.value = false
                    _showLockOverlay.value = false
                    scope.sendEvent(UiEvent.ShowAccessibilityDialog)
                }

                is RequestLockUseCase.Result.ErrorDisabled -> {
                    _isLockingInProgress.value = false
                    _showLockOverlay.value = false
                    if (!enableLockToastShown) {
                        enableLockToastShown = true
                        scope.sendEvent(UiEvent.ShowToast(R.string.toast_enable_double_tap_to_lock))
                    }
                }

                is RequestLockUseCase.Result.ErrorGeneric -> {
                    _isLockingInProgress.value = false
                    _showLockOverlay.value = false
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