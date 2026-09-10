package com.github.reygnn.kolibri_launcher.support

import java.util.Calendar
import java.util.Locale
import java.util.TimeZone

/**
 * Shared helpers for tests that assert formatted timestamps.
 *
 * `SimpleDateFormat` and `Calendar` read the JVM default time zone AND default
 * locale — the locale even selects the calendar system (e.g. a Buddhist
 * calendar for th_TH) and the digit script (Arabic-Indic for ar). Any test
 * asserting an exact formatted string must therefore pin both. Kept here
 * (alongside the JUnit rules) so time-sensitive tests across modules don't each
 * re-copy the same helpers.
 */
object FormattingTestSupport {

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

    /** Runs [block] with the JVM default locale pinned to [locale], restoring it afterwards. */
    fun withDefaultLocale(locale: Locale, block: () -> Unit) {
        val previous = Locale.getDefault()
        try {
            Locale.setDefault(locale)
            block()
        } finally {
            Locale.setDefault(previous)
        }
    }

    /**
     * Epoch millis for the given UTC wall-clock components ([month] is 1-based).
     *
     * Pinned to [Locale.ROOT] so the calendar is Gregorian regardless of the
     * JVM default locale — otherwise a th_TH default would yield a Buddhist
     * calendar and reinterpret [year].
     */
    fun utcMillis(
        year: Int, month: Int, day: Int,
        hour: Int, minute: Int, second: Int,
    ): Long {
        val cal = Calendar.getInstance(TimeZone.getTimeZone("UTC"), Locale.ROOT).apply {
            clear()
            set(year, month - 1, day, hour, minute, second)
        }
        return cal.timeInMillis
    }
}
