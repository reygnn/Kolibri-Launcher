package com.github.reygnn.kolibri_launcher.domain.model

/**
 * Eine einfache Datenklasse, die einen einzelnen Kalendertermin repräsentiert.
 * Diese wird vom Repository an das ViewModel übergeben.
 *
 * [isAllDay] marks a calendar all-day event (CalendarContract ALL_DAY = 1);
 * such events have no meaningful clock time, so the UI renders an "all day"
 * label instead of formatting [startTimeMillis].
 */
data class CalendarEvent(
    val title: String,
    val startTimeMillis: Long,
    val endTimeMillis: Long,
    val isAllDay: Boolean = false
)
