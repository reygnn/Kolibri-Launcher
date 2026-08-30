package com.github.reygnn.kolibri_launcher.ui.home

import com.github.reygnn.kolibri_launcher.domain.model.TimeBasedEvent
import com.github.reygnn.kolibri_launcher.domain.model.TimeBasedEventType
import java.text.SimpleDateFormat
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZoneOffset
import java.util.Calendar
import java.util.Date
import java.util.Locale

/**
 * PURE LOGIC - Time Formatting Engine
 *
 * Kapselt die Logik für das Runden von Alarmzeiten und das Formatieren
 * von Zeitstempeln. Keine Android-Dependencies (außer java.*).
 */
class TimeEventFormatter {

    /**
     * Formatiert einen Alarm-Zeitstempel.
     *
     * LOGIK: Android Alarme feuern oft Millisekunden nach der vollen Minute.
     * Ein Alarm für 07:00 Uhr könnte intern 07:00:00.052 sein.
     * Manche APIs liefern auch "Trigger Time", die leicht in der Vergangenheit liegt.
     *
     * Um dem User "07:01" anzuzeigen, wenn er eigentlich "07:00" meint,
     * runden wir Sekunden/Millis > 0 auf die nächste Minute auf (Standard Android Verhalten),
     * ODER wir schneiden sie ab (je nach gewünschtem Verhalten).
     *
     * Deine ursprüngliche Implementierung hat AUFGERUNDET (+1 Minute),
     * wenn Sekunden > 0 waren. Ich habe diese Logik hier exakt übernommen.
     */
    fun formatAlarmTime(triggerTimeMillis: Long, is24Hour: Boolean, locale: Locale = Locale.getDefault()): String {
        val calendar = Calendar.getInstance()
        calendar.timeInMillis = triggerTimeMillis

        // Spezielle Alarm-Logik: Wenn Sekunden oder Millis existieren, addiere eine Minute.
        // Das ist oft nötig, weil "nächster Alarm" APIs manchmal die Zeit des *gerade klingelnden* Alarms liefern.
        if (calendar.get(Calendar.SECOND) > 0 || calendar.get(Calendar.MILLISECOND) > 0) {
            calendar.add(Calendar.MINUTE, 1)
        }

        // Sekunden für die Anzeige säubern
        calendar.set(Calendar.SECOND, 0)
        calendar.set(Calendar.MILLISECOND, 0)

        return formatTimeInternal(calendar.timeInMillis, is24Hour, locale)
    }

    /**
     * Formatiert Kalender-Events (keine Rundungs-Logik nötig).
     */
    fun formatCalendarTime(triggerTimeMillis: Long, is24Hour: Boolean, locale: Locale = Locale.getDefault()): String {
        return formatTimeInternal(triggerTimeMillis, is24Hour, locale)
    }

    /**
     * Builds a single row label for the time-based-events dialog, e.g.
     * "07:00  Alarm" or "14:30  Standup". The event type is conveyed by a
     * leading monochrome vector icon set on the row by the dialog adapter
     * (see MainActivity.showTimeBasedEventsDialog) — it replaced the old
     * inline emoji glyph, which did not tint with the wallpaper-aware text
     * colour. The time is formatted with the type-appropriate rule (alarms
     * round up seconds, calendar times do not). Pure JVM logic — no Android
     * dependencies.
     *
     * An all-day calendar event has no meaningful clock time, so [allDayLabel]
     * (a localized string resolved by the caller — :app owns display strings)
     * is shown in place of the time. The caller passes it unconditionally; it
     * is only used when [TimeBasedEvent.isAllDay] is set.
     */
    fun formatEventRow(
        event: TimeBasedEvent,
        is24Hour: Boolean,
        allDayLabel: String,
        locale: Locale = Locale.getDefault()
    ): String {
        val lead = when {
            event.type == TimeBasedEventType.CALENDAR && event.isAllDay -> allDayLabel
            event.type == TimeBasedEventType.ALARM ->
                formatAlarmTime(event.triggerTimeMillis, is24Hour, locale)
            else -> formatCalendarTime(event.triggerTimeMillis, is24Hour, locale)
        }
        return "$lead  ${event.title}"
    }

    private fun formatTimeInternal(timeMillis: Long, is24Hour: Boolean, locale: Locale): String {
        val pattern = if (is24Hour) "HH:mm" else "h:mm a"
        val formatter = SimpleDateFormat(pattern, locale)
        return formatter.format(Date(timeMillis))
    }

    /**
     * A row in the upcoming-events dialog: either an event, or the separator
     * that splits today's events from tomorrow's.
     */
    sealed interface EventRow {
        data class Item(val event: TimeBasedEvent) : EventRow
        data object TomorrowSeparator : EventRow
    }

    /**
     * Splits a chronologically-sorted [events] list into today's and tomorrow's
     * groups and inserts a single [EventRow.TomorrowSeparator] between them (only
     * when BOTH groups are non-empty). The repository window is today + tomorrow,
     * so there are at most two groups.
     *
     * Grouping is by the event's LOCAL calendar day, computed the same way the
     * repository filters: an all-day event's [TimeBasedEvent.triggerTimeMillis] is
     * a UTC midnight, read back in UTC; a timed event / alarm is read in [zone].
     * Partitioning (rather than a single split index) keeps the groups correct
     * even if an all-day timestamp does not sort monotonically against timed ones.
     * Order within each group is preserved.
     */
    fun buildEventRows(
        events: List<TimeBasedEvent>,
        today: LocalDate,
        zone: ZoneId
    ): List<EventRow> {
        if (events.isEmpty()) return emptyList()
        val tomorrow = today.plusDays(1)

        fun localDateOf(event: TimeBasedEvent): LocalDate {
            val readZone = if (event.isAllDay) ZoneOffset.UTC else zone
            return Instant.ofEpochMilli(event.triggerTimeMillis).atZone(readZone).toLocalDate()
        }

        val (tomorrowEvents, todayEvents) = events.partition { localDateOf(it) == tomorrow }

        val rows = ArrayList<EventRow>(events.size + 1)
        todayEvents.forEach { rows.add(EventRow.Item(it)) }
        if (todayEvents.isNotEmpty() && tomorrowEvents.isNotEmpty()) {
            rows.add(EventRow.TomorrowSeparator)
        }
        tomorrowEvents.forEach { rows.add(EventRow.Item(it)) }
        return rows
    }
}