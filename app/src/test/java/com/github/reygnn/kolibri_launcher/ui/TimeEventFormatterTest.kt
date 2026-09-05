package com.github.reygnn.kolibri_launcher.ui.home

import com.github.reygnn.kolibri_launcher.domain.model.TimeBasedEvent
import com.github.reygnn.kolibri_launcher.domain.model.TimeBasedEventType
import com.github.reygnn.kolibri_launcher.rule.TimberRule
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneId
import java.util.Calendar
import java.util.Locale

class TimeEventFormatterTest {

    @get:Rule
    val timberRule = TimberRule()

    private val formatter = TimeEventFormatter()

    // Wir nutzen Locale.US für vorhersehbare "AM/PM" Tests
    private val testLocale = Locale.US

    // Stand-in for the localized R.string.event_all_day the Activity resolves.
    private val ALL_DAY = "All day"

    @Test
    fun `alarm - exact minute stays exact`() {
        // 14:30:00.000
        val time = createTime(14, 30, 0, 0)

        // 24h
        assertEquals("14:30", formatter.formatAlarmTime(time, true, testLocale))
        // 12h
        assertEquals("2:30 PM", formatter.formatAlarmTime(time, false, testLocale))
    }

    @Test
    fun `alarm - rounds up seconds logic`() {
        // Deine Logik: Wenn Sekunde > 0, dann +1 Minute
        // 14:30:01
        val time = createTime(14, 30, 1, 0)

        // Erwartung: 14:31
        assertEquals("14:31", formatter.formatAlarmTime(time, true, testLocale))
    }

    @Test
    fun `alarm - rounds up milliseconds logic`() {
        // 14:30:00.005
        val time = createTime(14, 30, 0, 5)

        // Erwartung: 14:31
        assertEquals("14:31", formatter.formatAlarmTime(time, true, testLocale))
    }

    @Test
    fun `alarm - hour rollover logic`() {
        // 14:59:30 -> Sollte 15:00 werden
        val time = createTime(14, 59, 30, 0)

        assertEquals("15:00", formatter.formatAlarmTime(time, true, testLocale))
    }

    @Test
    fun `alarm - day rollover logic`() {
        // 23:59:30 -> Sollte 00:00 (am nächsten Tag) werden
        val time = createTime(23, 59, 30, 0)

        assertEquals("00:00", formatter.formatAlarmTime(time, true, testLocale))
    }

    @Test
    fun `calendar - does NOT round up`() {
        // Kalender-Events sind präzise. 14:30:30 bleibt 14:30 (SimpleDateFormatter schneidet Sekunden ab)
        // Anders als dein Alarm-Logic, addieren wir hier NICHTS manuell.
        val time = createTime(14, 30, 30, 0)

        assertEquals("14:30", formatter.formatCalendarTime(time, true, testLocale))
    }

    @Test
    fun `12h format checks AM PM`() {
        val morning = createTime(9, 0, 0, 0)
        val evening = createTime(21, 0, 0, 0)

        assertEquals("9:00 AM", formatter.formatCalendarTime(morning, false, testLocale))
        assertEquals("9:00 PM", formatter.formatCalendarTime(evening, false, testLocale))
    }

    @Test
    fun `formatEventRow - alarm applies alarm rounding and carries no emoji glyph`() {
        // 07:00:30 → alarm rounding bumps to 07:01
        val time = createTime(7, 0, 30, 0)
        val event = TimeBasedEvent(time, "Alarm", TimeBasedEventType.ALARM)

        val row = formatter.formatEventRow(event, is24Hour = true, allDayLabel = ALL_DAY, locale = testLocale)

        // The type is now conveyed by a leading vector icon in the dialog adapter,
        // not by an inline glyph — the row text must start with the time.
        assertTrue("expected no leading glyph, was: $row", row.startsWith("07:01"))
        assertFalse("expected no bell glyph, was: $row", row.contains("⏰"))
        assertTrue("expected title, was: $row", row.contains("Alarm"))
    }

    @Test
    fun `formatEventRow - calendar does not round and carries no emoji glyph`() {
        // 14:30:30 → calendar keeps 14:30 (no rounding)
        val time = createTime(14, 30, 30, 0)
        val event = TimeBasedEvent(time, "Standup", TimeBasedEventType.CALENDAR)

        val row = formatter.formatEventRow(event, is24Hour = true, allDayLabel = ALL_DAY, locale = testLocale)

        assertTrue("expected no leading glyph, was: $row", row.startsWith("14:30"))
        assertFalse("expected no calendar glyph, was: $row", row.contains("📅"))
        assertTrue("expected title, was: $row", row.contains("Standup"))
    }

    @Test
    fun `formatEventRow - all-day calendar event shows the all-day label, not a time`() {
        // An all-day event's triggerTime is a midnight timestamp; the row must NOT
        // format it as a clock time but show the localized all-day label instead.
        val midnight = createTime(0, 0, 0, 0)
        val event = TimeBasedEvent(midnight, "Birthday", TimeBasedEventType.CALENDAR, isAllDay = true)

        val row = formatter.formatEventRow(event, is24Hour = true, allDayLabel = ALL_DAY, locale = testLocale)

        assertTrue("expected all-day label, was: $row", row.startsWith(ALL_DAY))
        assertFalse("expected no midnight time, was: $row", row.contains("00:00"))
        assertTrue("expected title, was: $row", row.contains("Birthday"))
    }

    @Test
    fun `formatEventRow - all-day flag on an alarm is ignored (alarms always show a time)`() {
        // isAllDay only applies to calendar events; an alarm always renders its time.
        val time = createTime(6, 30, 0, 0)
        val event = TimeBasedEvent(time, "Alarm", TimeBasedEventType.ALARM, isAllDay = true)

        val row = formatter.formatEventRow(event, is24Hour = true, allDayLabel = ALL_DAY, locale = testLocale)

        assertTrue("expected the alarm time, was: $row", row.startsWith("06:30"))
        assertFalse("expected no all-day label, was: $row", row.contains(ALL_DAY))
    }

    // =========================================================================
    // buildEventRows — today/tomorrow grouping + separator
    // =========================================================================

    private val zone: ZoneId = ZoneId.of("Europe/Berlin")
    private val today: LocalDate = LocalDate.of(2026, 8, 30)
    private val tomorrow: LocalDate = today.plusDays(1)

    private fun timedOn(
        date: LocalDate,
        hour: Int,
        title: String,
        type: TimeBasedEventType = TimeBasedEventType.CALENDAR
    ): TimeBasedEvent {
        val millis = date.atTime(hour, 0).atZone(zone).toInstant().toEpochMilli()
        return TimeBasedEvent(millis, title, type)
    }

    private fun allDayOn(date: LocalDate, title: String): TimeBasedEvent {
        // The repository normalises an all-day trigger to LOCAL midnight of its
        // day, so grouping reads it in the local zone (not UTC). Matching that
        // here also guards the regression: under the old UTC read, a Berlin
        // (UTC+2) local midnight would have grouped one day early.
        val millis = date.atStartOfDay(zone).toInstant().toEpochMilli()
        return TimeBasedEvent(millis, title, TimeBasedEventType.CALENDAR, isAllDay = true)
    }

    @Test
    fun `buildEventRows - empty input yields no rows`() {
        assertTrue(formatter.buildEventRows(emptyList(), today, zone).isEmpty())
    }

    @Test
    fun `buildEventRows - only today events has no separator`() {
        val events = listOf(timedOn(today, 9, "A"), timedOn(today, 14, "B"))
        val rows = formatter.buildEventRows(events, today, zone)

        assertEquals(2, rows.size)
        assertTrue(rows.none { it is TimeEventFormatter.EventRow.TomorrowSeparator })
    }

    @Test
    fun `buildEventRows - only tomorrow events get a leading separator`() {
        val events = listOf(timedOn(tomorrow, 9, "A"), timedOn(tomorrow, 14, "B"))
        val rows = formatter.buildEventRows(events, today, zone)

        // <separator>, A, B — the divider leads the list as a "tomorrow" marker.
        assertEquals(3, rows.size)
        assertTrue(rows[0] is TimeEventFormatter.EventRow.TomorrowSeparator)
        assertTrue(rows[1] is TimeEventFormatter.EventRow.Item)
        assertTrue(rows[2] is TimeEventFormatter.EventRow.Item)
    }

    @Test
    fun `buildEventRows - today and tomorrow get exactly one separator at the boundary`() {
        val events = listOf(
            timedOn(today, 9, "T1"),
            timedOn(today, 18, "T2"),
            timedOn(tomorrow, 8, "M1")
        )
        val rows = formatter.buildEventRows(events, today, zone)

        // T1, T2, <separator>, M1
        assertEquals(4, rows.size)
        assertTrue(rows[0] is TimeEventFormatter.EventRow.Item)
        assertTrue(rows[1] is TimeEventFormatter.EventRow.Item)
        assertTrue(rows[2] is TimeEventFormatter.EventRow.TomorrowSeparator)
        assertTrue(rows[3] is TimeEventFormatter.EventRow.Item)
        assertEquals(
            "M1",
            (rows[3] as TimeEventFormatter.EventRow.Item).event.title
        )
    }

    @Test
    fun `buildEventRows - all-day events are grouped by their local date`() {
        // all-day today + all-day tomorrow → one on each side of the separator.
        val events = listOf(allDayOn(today, "AllToday"), allDayOn(tomorrow, "AllTomorrow"))
        val rows = formatter.buildEventRows(events, today, zone)

        assertEquals(3, rows.size)
        assertEquals("AllToday", (rows[0] as TimeEventFormatter.EventRow.Item).event.title)
        assertTrue(rows[1] is TimeEventFormatter.EventRow.TomorrowSeparator)
        assertEquals("AllTomorrow", (rows[2] as TimeEventFormatter.EventRow.Item).event.title)
    }

    @Test
    fun `buildEventRows - a tomorrow alarm lands in the tomorrow group`() {
        val events = listOf(
            timedOn(today, 12, "TodayEvent"),
            timedOn(tomorrow, 7, "Wakeup", type = TimeBasedEventType.ALARM)
        )
        val rows = formatter.buildEventRows(events, today, zone)

        assertEquals(3, rows.size)
        assertTrue(rows[1] is TimeEventFormatter.EventRow.TomorrowSeparator)
        val last = rows[2] as TimeEventFormatter.EventRow.Item
        assertEquals("Wakeup", last.event.title)
        assertEquals(TimeBasedEventType.ALARM, last.event.type)
    }

    @Test
    fun `buildEventRows - a 02_30 event on the DST fall-back day groups as today`() {
        // Regression + intent guard for the one case the summer `today` (2026-08-30)
        // above cannot cover: `today` IS a DST-transition day. On 2026-10-25 Berlin
        // falls back 03:00 -> 02:00, so local 02:30 exists twice. buildEventRows is
        // DST-safe by design — `atZone(zone).toLocalDate()` is offset-correct, so
        // either 02:30 is unambiguously 2026-10-25. This pins that against anyone
        // reintroducing manual offset math (e.g. deriving the day from a raw UTC
        // instant), which would misgroup the event; it also proves nothing throws on
        // an ambiguous local time.
        val dstZone = ZoneId.of("Europe/Berlin")
        val fallBackDay = LocalDate.of(2026, 10, 25)
        val nextDay = fallBackDay.plusDays(1)

        val ambiguousMillis =
            fallBackDay.atTime(2, 30).atZone(dstZone).toInstant().toEpochMilli()
        val fallBackEvent = TimeBasedEvent(ambiguousMillis, "FallBack", TimeBasedEventType.CALENDAR)
        val nextDayEvent = TimeBasedEvent(
            nextDay.atTime(9, 0).atZone(dstZone).toInstant().toEpochMilli(),
            "NextDay",
            TimeBasedEventType.CALENDAR,
        )

        val rows = formatter.buildEventRows(listOf(fallBackEvent, nextDayEvent), fallBackDay, dstZone)

        // FallBack (today), <separator>, NextDay — the 02:30 event stays in today.
        assertEquals(3, rows.size)
        assertEquals("FallBack", (rows[0] as TimeEventFormatter.EventRow.Item).event.title)
        assertTrue(rows[1] is TimeEventFormatter.EventRow.TomorrowSeparator)
        assertEquals("NextDay", (rows[2] as TimeEventFormatter.EventRow.Item).event.title)
    }

    @Test
    fun `buildEventRows - a 02_30 event on the DST spring-forward day groups as today`() {
        // Sibling of the fall-back test for the other DST direction: `today` is the
        // spring-forward day. On 2026-03-29 Berlin springs 02:00 -> 03:00, so local
        // 02:30 does NOT exist — `atZone` resolves the gap forward (02:30 -> 03:30).
        // buildEventRows reads that instant back via `atZone(zone).toLocalDate()`,
        // which is still 2026-03-29, so the event stays in today. Same design
        // guarantee, opposite transition: the day is unambiguous whether the local
        // time is doubled (fall-back) or missing (spring-forward), and nothing throws.
        val dstZone = ZoneId.of("Europe/Berlin")
        val springForwardDay = LocalDate.of(2026, 3, 29)
        val nextDay = springForwardDay.plusDays(1)

        val gapMillis =
            springForwardDay.atTime(2, 30).atZone(dstZone).toInstant().toEpochMilli()
        val gapEvent = TimeBasedEvent(gapMillis, "SpringForward", TimeBasedEventType.CALENDAR)
        val nextDayEvent = TimeBasedEvent(
            nextDay.atTime(9, 0).atZone(dstZone).toInstant().toEpochMilli(),
            "NextDay",
            TimeBasedEventType.CALENDAR,
        )

        val rows = formatter.buildEventRows(listOf(gapEvent, nextDayEvent), springForwardDay, dstZone)

        // SpringForward (today), <separator>, NextDay — the gap event stays in today.
        assertEquals(3, rows.size)
        assertEquals("SpringForward", (rows[0] as TimeEventFormatter.EventRow.Item).event.title)
        assertTrue(rows[1] is TimeEventFormatter.EventRow.TomorrowSeparator)
        assertEquals("NextDay", (rows[2] as TimeEventFormatter.EventRow.Item).event.title)
    }

    // --- Helper ---
    private fun createTime(hour: Int, minute: Int, second: Int, millis: Int): Long {
        val c = Calendar.getInstance()
        c.set(Calendar.HOUR_OF_DAY, hour)
        c.set(Calendar.MINUTE, minute)
        c.set(Calendar.SECOND, second)
        c.set(Calendar.MILLISECOND, millis)
        return c.timeInMillis
    }
}