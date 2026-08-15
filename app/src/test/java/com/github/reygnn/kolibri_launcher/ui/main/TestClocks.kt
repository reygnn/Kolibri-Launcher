package com.github.reygnn.kolibri_launcher.ui.main

import com.github.reygnn.kolibri_launcher.ui.util.MonotonicClock

/**
 * A [MonotonicClock] that jumps far past the launch throttle window on every
 * read, so the app-launch double-tap guard in `AppManagementDelegate` never
 * fires. For ViewModel-level tests that exercise delegation, ordering, or
 * rapid-tap resilience — not the throttle itself (that is pinned directly in
 * `AppManagementDelegateTest`). Keeping these tests throttle-free preserves
 * their original intent (e.g. "10 rapid taps → 10 recorded launches").
 */
fun neverThrottlingClock(): MonotonicClock {
    var t = 0L
    return MonotonicClock {
        t += 10_000L
        t
    }
}
