package com.github.reygnn.launcher.core.timeinfo

/**
 * Unified model für zeitbasierte Events (Kalender + Alarme).
 * Ermöglicht chronologische Sortierung und einheitliche UI-Darstellung.
 *
 * [isAllDay] is only ever true for [TimeBasedEventType.CALENDAR] all-day
 * events; the UI shows an "all day" label for those instead of a clock time.
 */
data class TimeBasedEvent(
    val triggerTimeMillis: Long,
    val title: String,
    val type: TimeBasedEventType,
    val isAllDay: Boolean = false
)
