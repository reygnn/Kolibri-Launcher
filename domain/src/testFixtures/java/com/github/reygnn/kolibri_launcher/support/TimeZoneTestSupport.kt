package com.github.reygnn.kolibri_launcher.support

import java.util.Calendar
import java.util.TimeZone

/**
 * Shared helpers for tests that assert formatted timestamps.
 *
 * `SimpleDateFormat` and `Calendar` read the JVM default time zone, so any
 * test asserting an exact formatted string must pin it — otherwise the result
 * varies by CI host / dev machine. Kept here (alongside the JUnit rules) so
 * time-sensitive tests across modules don't each re-copy the same helpers.
 */
object TimeZoneTestSupport {

    /** Runs [block] with the JVM default time zone pinned to UTC, restoring it afterwards. */
    fun withUtcTimeZone(block: () -> Unit) {
        val previous = TimeZone.getDefault()
        try {
            TimeZone.setDefault(TimeZone.getTimeZone("UTC"))
            block()
        } finally {
            TimeZone.setDefault(previous)
        }
    }

    /** Epoch millis for the given UTC wall-clock components ([month] is 1-based). */
    fun utcMillis(
        year: Int, month: Int, day: Int,
        hour: Int, minute: Int, second: Int,
    ): Long {
        val cal = Calendar.getInstance(TimeZone.getTimeZone("UTC")).apply {
            clear()
            set(year, month - 1, day, hour, minute, second)
        }
        return cal.timeInMillis
    }
}
