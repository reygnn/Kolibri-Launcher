package com.github.reygnn.launcher.common.ui

import android.content.ActivityNotFoundException
import android.content.ContentUris
import android.content.Context
import android.content.Intent
import android.provider.AlarmClock
import android.provider.CalendarContract
import androidx.annotation.StringRes

/**
 * Shared "open an optional system app" helpers, used by both launchers' home
 * screens (double-tap clock/date/battery, and the upcoming-events dialog rows).
 * The intent building is factored into pure builders so it is trivially reusable,
 * and the launch-failure decision is a pure function so it is JVM-testable.
 */

/**
 * PURE DECISION — the expected, non-crash failures when launching an OPTIONAL
 * system intent: a ROM with no handler ([ActivityNotFoundException]) or one that
 * guards the action ([SecurityException], e.g. an OEM alarm activity behind a
 * permission). Anything else is a programmer error and MUST propagate (Rule 11),
 * so [startActivitySafely] rethrows it rather than swallowing it into a toast.
 */
fun isExpectedSystemLaunchFailure(t: Throwable): Boolean =
    t is ActivityNotFoundException || t is SecurityException

/**
 * Launch an optional system [intent]; a launcher must never crash on a tap. On an
 * expected failure ([isExpectedSystemLaunchFailure]) the user gets a [noAppMessage]
 * toast instead of a crash; an unexpected throwable propagates. The caller passes
 * its own [noAppMessage] so each app keeps its own wording.
 */
fun Context.startActivitySafely(intent: Intent, @StringRes noAppMessage: Int) {
    // No suspension point — synchronous startActivity.
    try {
        startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    } catch (e: Throwable) {
        // Rethrow programmer errors (incl. OutOfMemoryError); only the two
        // expected optional-app failures collapse to a toast.
        if (!isExpectedSystemLaunchFailure(e)) throw e
        showToastSafe(noAppMessage)
    }
}

/** Intent to the system clock's alarms screen. */
fun clockAlarmsIntent(): Intent = Intent(AlarmClock.ACTION_SHOW_ALARMS)

/** Intent to the calendar app at [atMillis] (the calendar `time` deep-link). */
fun calendarAtTimeIntent(atMillis: Long): Intent {
    val builder = CalendarContract.CONTENT_URI.buildUpon().appendPath("time")
    ContentUris.appendId(builder, atMillis)
    return Intent(Intent.ACTION_VIEW).setData(builder.build())
}

/** Intent to the system battery / power-usage screen. */
fun batteryUsageIntent(): Intent = Intent(Intent.ACTION_POWER_USAGE_SUMMARY)

/** Open the system clock's alarms screen; toasts [noAppMessage] if none exists. */
fun Context.openClockApp(@StringRes noAppMessage: Int) =
    startActivitySafely(clockAlarmsIntent(), noAppMessage)

/** Open the calendar at "now"; toasts [noAppMessage] if none exists. */
fun Context.openCalendarApp(@StringRes noAppMessage: Int) =
    startActivitySafely(calendarAtTimeIntent(System.currentTimeMillis()), noAppMessage)

/** Open the system battery / power-usage screen; toasts [noAppMessage] if none exists. */
fun Context.openBatterySettings(@StringRes noAppMessage: Int) =
    startActivitySafely(batteryUsageIntent(), noAppMessage)
