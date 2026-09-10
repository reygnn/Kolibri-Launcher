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

    /**
     * Height (in **dp**) of the top exclusion band reserved for the
     * system notification shade. A home-screen swipe-down whose
     * ACTION_DOWN lands within this band from the top edge is ceded to
     * the system (the recent-apps gesture is suppressed there), so the
     * user's pull-down for notifications no longer collides with it.
     *
     * dp, not px: at ~status-bar height plus a small buffer this stays
     * consistent across screen densities (≈ 144 px on a 3x device,
     * ≈ 96 px on 2x). Only the swipe-DOWN result is gated — up / left /
     * right swipes starting in this band are unaffected.
     *
     * The band is consumed only by
     * [com.github.reygnn.kolibri_launcher.ui.home.HomeGestureLayout],
     * which converts it to px via the display density. The AppDrawer's
     * [com.github.reygnn.kolibri_launcher.ui.appdrawer.SwipeDownDismissLayout]
     * does not use it — its swipe-down dismiss is intentional there.
     */
    const val TOP_NOTIFICATION_EXCLUSION_DP = 48f
}
