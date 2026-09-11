package com.github.reygnn.launcher.common.ui.gesture

/**
 * Shared UX-tuning knobs for the project's `dispatchTouchEvent` gesture
 * wrappers (see [GestureDispatchCore]). Every wrapper exists for the same
 * reason — RecyclerView/ScrollView silently disabling parent intercept
 * mid-gesture — and shares the same "decisive flick vs. slow drag"
 * discrimination contract, so they all read the same calibration here.
 *
 * The values are an empirically validated set: the only triplet proven to
 * discriminate fast swipes from slow drags on a vertical scroll axis
 * without perceptible lag or false triggers (real-device validation,
 * homescroll.md §6 Step 5).
 *
 * The unit-bound distance value (`scaledTouchSlop * MULTIPLIER`) cannot
 * live here because it needs `Context`; [GestureDispatchCore] multiplies
 * locally with [TOUCH_SLOP_DISTANCE_MULTIPLIER]. The pure-numeric
 * thresholds — velocity (px/ms) and the axis-dominance ratio — are
 * unit-less or device-independent and live as `const val` here.
 *
 * Lowering [VELOCITY_PX_PER_MS] without re-running the swipe regression
 * tests is asking for a "swipes feel too sensitive" regression to slip
 * past CI and only surface on real devices.
 */
object GestureThresholds {

    /**
     * Multiplier applied to `ViewConfiguration.scaledTouchSlop` to get
     * the minimum-distance threshold a gesture must travel before it
     * counts as a swipe. Higher -> user must drag further.
     */
    const val TOUCH_SLOP_DISTANCE_MULTIPLIER = 4

    /**
     * Minimum vertical/horizontal velocity (px per ms) for a gesture to
     * count as a flick. Higher -> only fast flicks fire; slow drags can
     * never trigger.
     */
    const val VELOCITY_PX_PER_MS = 1.2f

    /**
     * Ratio the dominant axis must beat the other axis by for the gesture
     * to count as axis-aligned. Higher -> gesture must be more strictly
     * horizontal/vertical (less diagonal tolerance).
     */
    const val DOMINANCE_FACTOR = 1.5f

    /**
     * Height (in **dp**) of the top exclusion band reserved for the
     * system notification shade. A swipe-down whose ACTION_DOWN lands
     * within this band from the top edge is ceded to the system, so the
     * user's pull-down for notifications no longer collides with it.
     *
     * dp, not px: at ~status-bar height plus a small buffer this stays
     * consistent across densities. Only the swipe-DOWN result is gated —
     * up / left / right starting in this band are unaffected. A consumer
     * that wants the band disabled (e.g. an app-drawer dismiss, where the
     * downward swipe is intentional) sets [GestureDispatchCore.topExclusionPx]
     * to 0f.
     */
    const val TOP_NOTIFICATION_EXCLUSION_DP = 48f
}
