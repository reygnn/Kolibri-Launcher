package com.github.reygnn.kolibri_launcher.ui.util

/**
 * Shared UX-tuning knobs for the project's two `dispatchTouchEvent`
 * gesture wrappers — [com.github.reygnn.kolibri_launcher.ui.home.HomeGestureLayout]
 * and [com.github.reygnn.kolibri_launcher.ui.appdrawer.SwipeDownDismissLayout].
 * Both wrappers exist for the same reason (RecyclerView/ScrollView
 * silently disabling parent intercept mid-gesture) and share the same
 * "decisive flick vs. slow drag" discrimination contract, so they read
 * the same calibration from one place.
 *
 * The values are an empirically validated set: they're the only triplet
 * proven to discriminate fast swipes from slow drags on a vertical
 * scroll axis without the user perceiving lag or false triggers. Real-
 * device validation (homescroll.md §6 Step 5) signed them off.
 *
 * The unit-bound distance value (`scaledTouchSlop * MULTIPLIER`) cannot
 * live here because it needs `Context`; each layout multiplies locally
 * with [TOUCH_SLOP_DISTANCE_MULTIPLIER]. The pure-numeric thresholds —
 * velocity (px/ms) and the axis-dominance ratio — are unit-less or
 * device-independent and live as `const val` here.
 *
 * The two instrumented tests that pin the velocity-threshold contract:
 *  - `HomeGestureLayoutTest.slowDragUpOnHome_doesNotOpenAppDrawer`
 *  - `AppDrawerSlowDragNoDismissTest.slowDragDownOnDrawerRoot_doesNotDismiss`
 *
 * Lowering [VELOCITY_PX_PER_MS] without re-running both is asking for a
 * regression to silently slip past CI and only surface as "swipes feel
 * too sensitive" on real devices.
 */
object GestureThresholds {

    /**
     * Multiplier applied to `ViewConfiguration.scaledTouchSlop` to get
     * the minimum-distance threshold a gesture must travel before it
     * counts as a swipe. Higher → user must drag further.
     */
    const val TOUCH_SLOP_DISTANCE_MULTIPLIER = 4

    /**
     * Minimum vertical/horizontal velocity (px per ms) for a gesture to
     * count as a flick. Higher → only fast flicks fire; slow drags can
     * never trigger.
     */
    const val VELOCITY_PX_PER_MS = 1.2f

    /**
     * Ratio the dominant axis must beat the other axis by for the
     * gesture to count as axis-aligned. Higher → gesture must be more
     * strictly horizontal/vertical (less diagonal tolerance).
     */
    const val DOMINANCE_FACTOR = 1.5f
}
