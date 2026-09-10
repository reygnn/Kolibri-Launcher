package com.github.reygnn.kolibri_launcher.ui.util

/**
 * Monotonic time source in milliseconds, injected so time-dependent UI logic
 * stays JVM-testable with a fake clock instead of the Android `SystemClock`.
 *
 * "Monotonic" is the point: the production impl is `SystemClock.elapsedRealtime()`,
 * which never jumps backward — unlike wall-clock time (`currentTimeMillis`) — so a
 * throttle window built on it can neither be defeated nor stuck by an NTP or
 * timezone change. Only differences between [now] readings are meaningful.
 */
fun interface MonotonicClock {
    fun now(): Long
}
