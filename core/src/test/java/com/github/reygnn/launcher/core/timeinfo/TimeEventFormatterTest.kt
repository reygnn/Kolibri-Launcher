package com.github.reygnn.launcher.core.timeinfo

import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZonedDateTime
import java.util.Calendar
import java.util.Locale
import java.util.TimeZone

class TimeEventFormatterTest {


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
        assertThat(formatter.formatAlarmTime(time, true, testLocale)).isEqualTo("14:30")
        // 12h
        assertThat(formatter.formatAlarmTime(time, false, testLocale)).isEqualTo("2:30 PM")
    }

    @Test
    fun `alarm - rounds up seconds logic`() {
        // Deine Logik: Wenn Sekunde > 0, dann +1 Minute
        // 14:30:01
        val time = createTime(14, 30, 1, 0)

        // Erwartung: 14:31
        assertThat(formatter.formatAlarmTime(time, true, testLocale)).isEqualTo("14:31")
    }

    @Test
    fun `alarm - rounds up milliseconds logic`() {
        // 14:30:00.005
        val time = createTime(14, 30, 0, 5)

        // Erwartung: 14:31
        assertThat(formatter.formatAlarmTime(time, true, testLocale)).isEqualTo("14:31")
    }

    @Test
    fun `alarm - hour rollover logic`() {
        // 14:59:30 -> Sollte 15:00 werden
        val time = createTime(14, 59, 30, 0)

        assertThat(formatter.formatAlarmTime(time, true, testLocale)).isEqualTo("15:00")
    }

    @Test
    fun `alarm - day rollover logic`() {
        // 23:59:30 -> Sollte 00:00 (am nächsten Tag) werden
        val time = createTime(23, 59, 30, 0)

        assertThat(formatter.formatAlarmTime(time, true, testLocale)).isEqualTo("00:00")
    }

    @Test
    fun `calendar - does NOT round up`() {
        // Kalender-Events sind präzise. 14:30:30 bleibt 14:30 (SimpleDateFormatter schneidet Sekunden ab)
        // Anders als dein Alarm-Logic, addieren wir hier NICHTS manuell.
        val time = createTime(14, 30, 30, 0)

        assertThat(formatter.formatCalendarTime(time, true, testLocale)).isEqualTo("14:30")
    }

    @Test
    fun `12h format checks AM PM`() {
        val morning = createTime(9, 0, 0, 0)
        val evening = createTime(21, 0, 0, 0)

        assertThat(formatter.formatCalendarTime(morning, false, testLocale)).isEqualTo("9:00 AM")
        assertThat(formatter.formatCalendarTime(evening, false, testLocale)).isEqualTo("9:00 PM")
    }

    @Test
    fun `formatEventRow - alarm applies alarm rounding and carries no emoji glyph`() {
        // 07:00:30 → alarm rounding bumps to 07:01
        val time = createTime(7, 0, 30, 0)
        val event = TimeBasedEvent(time, "Alarm", TimeBasedEventType.ALARM)

        val row = formatter.formatEventRow(event, is24Hour = true, allDayLabel = ALL_DAY, locale = testLocale)

        // The type is now conveyed by a leading vector icon in the dialog adapter,
        // not by an inline glyph — the row text must start with the time.
        assertWithMessage("expected no leading glyph, was: $row").that(row.startsWith("07:01")).isTrue()
        assertWithMessage("expected no bell glyph, was: $row").that(row.contains("⏰")).isFalse()
        assertWithMessage("expected title, was: $row").that(row.contains("Alarm")).isTrue()
    }

    @Test
    fun `formatEventRow - calendar does not round and carries no emoji glyph`() {
        // 14:30:30 → calendar keeps 14:30 (no rounding)
        val time = createTime(14, 30, 30, 0)
        val event = TimeBasedEvent(time, "Standup", TimeBasedEventType.CALENDAR)

        val row = formatter.formatEventRow(event, is24Hour = true, allDayLabel = ALL_DAY, locale = testLocale)

        assertWithMessage("expected no leading glyph, was: $row").that(row.startsWith("14:30")).isTrue()
        assertWithMessage("expected no calendar glyph, was: $row").that(row.contains("📅")).isFalse()
        assertWithMessage("expected title, was: $row").that(row.contains("Standup")).isTrue()
    }

    @Test
    fun `formatEventRow - all-day calendar event shows the all-day label, not a time`() {
        // An all-day event's triggerTime is a midnight timestamp; the row must NOT
        // format it as a clock time but show the localized all-day label instead.
        val midnight = createTime(0, 0, 0, 0)
        val event = TimeBasedEvent(midnight, "Birthday", TimeBasedEventType.CALENDAR, isAllDay = true)

        val row = formatter.formatEventRow(event, is24Hour = true, allDayLabel = ALL_DAY, locale = testLocale)

        assertWithMessage("expected all-day label, was: $row").that(row.startsWith(ALL_DAY)).isTrue()
        assertWithMessage("expected no midnight time, was: $row").that(row.contains("00:00")).isFalse()
        assertWithMessage("expected title, was: $row").that(row.contains("Birthday")).isTrue()
    }

    @Test
    fun `formatEventRow - all-day flag on an alarm is ignored (alarms always show a time)`() {
        // isAllDay only applies to calendar events; an alarm always renders its time.
        val time = createTime(6, 30, 0, 0)
        val event = TimeBasedEvent(time, "Alarm", TimeBasedEventType.ALARM, isAllDay = true)

        val row = formatter.formatEventRow(event, is24Hour = true, allDayLabel = ALL_DAY, locale = testLocale)

        assertWithMessage("expected the alarm time, was: $row").that(row.startsWith("06:30")).isTrue()
        assertWithMessage("expected no all-day label, was: $row").that(row.contains(ALL_DAY)).isFalse()
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
        assertThat(formatter.buildEventRows(emptyList(), today, zone).isEmpty()).isTrue()
    }

    @Test
    fun `buildEventRows - only today events has no separator`() {
        val events = listOf(timedOn(today, 9, "A"), timedOn(today, 14, "B"))
        val rows = formatter.buildEventRows(events, today, zone)

        assertThat(rows.size).isEqualTo(2)
        assertThat(rows.none { it is TimeEventFormatter.EventRow.TomorrowSeparator }).isTrue()
    }

    @Test
    fun `buildEventRows - only tomorrow events get a leading separator`() {
        val events = listOf(timedOn(tomorrow, 9, "A"), timedOn(tomorrow, 14, "B"))
        val rows = formatter.buildEventRows(events, today, zone)

        // <separator>, A, B — the divider leads the list as a "tomorrow" marker.
        assertThat(rows.size).isEqualTo(3)
        assertThat(rows[0]).isInstanceOf(TimeEventFormatter.EventRow.TomorrowSeparator::class.java)
        assertThat(rows[1]).isInstanceOf(TimeEventFormatter.EventRow.Item::class.java)
        assertThat(rows[2]).isInstanceOf(TimeEventFormatter.EventRow.Item::class.java)
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
        assertThat(rows.size).isEqualTo(4)
        assertThat(rows[0]).isInstanceOf(TimeEventFormatter.EventRow.Item::class.java)
        assertThat(rows[1]).isInstanceOf(TimeEventFormatter.EventRow.Item::class.java)
        assertThat(rows[2]).isInstanceOf(TimeEventFormatter.EventRow.TomorrowSeparator::class.java)
        assertThat(rows[3]).isInstanceOf(TimeEventFormatter.EventRow.Item::class.java)
        assertThat((rows[3] as TimeEventFormatter.EventRow.Item).event.title).isEqualTo("M1")
    }

    @Test
    fun `buildEventRows - all-day events are grouped by their local date`() {
        // all-day today + all-day tomorrow → one on each side of the separator.
        val events = listOf(allDayOn(today, "AllToday"), allDayOn(tomorrow, "AllTomorrow"))
        val rows = formatter.buildEventRows(events, today, zone)

        assertThat(rows.size).isEqualTo(3)
        assertThat((rows[0] as TimeEventFormatter.EventRow.Item).event.title).isEqualTo("AllToday")
        assertThat(rows[1]).isInstanceOf(TimeEventFormatter.EventRow.TomorrowSeparator::class.java)
        assertThat((rows[2] as TimeEventFormatter.EventRow.Item).event.title).isEqualTo("AllTomorrow")
    }

    @Test
    fun `buildEventRows - a tomorrow alarm lands in the tomorrow group`() {
        val events = listOf(
            timedOn(today, 12, "TodayEvent"),
            timedOn(tomorrow, 7, "Wakeup", type = TimeBasedEventType.ALARM)
        )
        val rows = formatter.buildEventRows(events, today, zone)

        assertThat(rows.size).isEqualTo(3)
        assertThat(rows[1]).isInstanceOf(TimeEventFormatter.EventRow.TomorrowSeparator::class.java)
        val last = rows[2] as TimeEventFormatter.EventRow.Item
        assertThat(last.event.title).isEqualTo("Wakeup")
        assertThat(last.event.type).isEqualTo(TimeBasedEventType.ALARM)
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
        assertThat(rows.size).isEqualTo(3)
        assertThat((rows[0] as TimeEventFormatter.EventRow.Item).event.title).isEqualTo("FallBack")
        assertThat(rows[1]).isInstanceOf(TimeEventFormatter.EventRow.TomorrowSeparator::class.java)
        assertThat((rows[2] as TimeEventFormatter.EventRow.Item).event.title).isEqualTo("NextDay")
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
        assertThat(rows.size).isEqualTo(3)
        assertThat((rows[0] as TimeEventFormatter.EventRow.Item).event.title).isEqualTo("SpringForward")
        assertThat(rows[1]).isInstanceOf(TimeEventFormatter.EventRow.TomorrowSeparator::class.java)
        assertThat((rows[2] as TimeEventFormatter.EventRow.Item).event.title).isEqualTo("NextDay")
    }

    // =========================================================================
    // formatAlarmTime across DST transitions — characterization of ACCEPTED
    // LIMITATION #10 (spring-forward gap-minute display jump)
    //
    // The buildEventRows DST tests above cover the DECISION logic, which is
    // offset-correct by design. formatAlarmTime is different: it is the only
    // function that does wall-clock ARITHMETIC (Calendar.add(MINUTE, 1)) in the
    // DEVICE-DEFAULT zone, so it — and only it — is exposed to the spring-forward
    // gap. That behaviour is documented as accepted, but nothing pinned it. These
    // tests lock the exact accepted behaviour so a rework that moves the rounding
    // into the display zone (the limitation's re-evaluation trigger) turns them
    // red instead of changing the label silently. Expected values were verified
    // empirically before pinning.
    // =========================================================================

    /** Runs [block] with the JVM default time zone pinned to [zoneId], restoring
     *  it afterwards — formatAlarmTime reads the default zone for its arithmetic. */
    private inline fun withDefaultZone(zoneId: String, block: () -> Unit) {
        val original = TimeZone.getDefault()
        try {
            TimeZone.setDefault(TimeZone.getTimeZone(zoneId))
            block()
        } finally {
            TimeZone.setDefault(original)
        }
    }

    private fun berlinInstant(
        year: Int, month: Int, day: Int, hour: Int, minute: Int, second: Int, millis: Int = 0
    ): Long = ZonedDateTime
        .of(year, month, day, hour, minute, second, millis * 1_000_000, ZoneId.of("Europe/Berlin"))
        .toInstant()
        .toEpochMilli()

    @Test
    fun `formatAlarmTime - spring-forward gap minute rounds across the gap to 03_00 (accepted limitation 10)`() {
        // Berlin 2026-03-29: the wall clock skips 02:00 -> 03:00, so 02:xx does not
        // exist. An alarm at 01:59:30 (seconds > 0, so the +1-minute round fires)
        // rounds to 02:00, which Calendar normalises across the gap to 03:00 — the
        // documented one-minute-a-year display jump. formatAlarmTime does its
        // arithmetic in the device-default zone, so the default is pinned to Berlin.
        withDefaultZone("Europe/Berlin") {
            val gap = berlinInstant(2026, 3, 29, 1, 59, 30)
            assertThat(formatter.formatAlarmTime(gap, true, testLocale)).isEqualTo("03:00")
        }
    }

    @Test
    fun `formatAlarmTime - an exact-minute alarm on the spring-forward night does not round or jump`() {
        // The jump needs BOTH the gap minute AND seconds/millis > 0. At exactly
        // 01:59:00.000 there is no sub-minute part, so the +1-minute round never
        // runs and the time renders as-is — no jump. Pins the precondition the
        // limitation names ("an alarm set to a normal HH:00 has no sub-minute part").
        withDefaultZone("Europe/Berlin") {
            val onMinute = berlinInstant(2026, 3, 29, 1, 59, 0)
            assertThat(formatter.formatAlarmTime(onMinute, true, testLocale)).isEqualTo("01:59")
        }
    }

    @Test
    fun `formatAlarmTime - the fall-back night does not jump, mirroring spring-forward`() {
        // Sibling of the spring-forward pin, opposite transition. Berlin 2026-10-25
        // falls back 03:00 -> 02:00, so 02:00 EXISTS. An alarm at 01:59:30 rounds to
        // 02:00 and stays there — no jump. This is the asymmetry the limitation rests
        // on: only the forward gap (a missing minute) jumps; the backward overlap
        // does not.
        withDefaultZone("Europe/Berlin") {
            val fallBack = berlinInstant(2026, 10, 25, 1, 59, 30)
            assertThat(formatter.formatAlarmTime(fallBack, true, testLocale)).isEqualTo("02:00")
        }
    }

    // --- buildRowLabels ---

    @Test
    fun `buildRowLabels - blank alarm title falls back to the alarm label`() {
        val rows = listOf<TimeEventFormatter.EventRow>(
            TimeEventFormatter.EventRow.Item(
                TimeBasedEvent(createTime(7, 0, 0, 0), "", TimeBasedEventType.ALARM),
            ),
        )
        val labels = formatter.buildRowLabels(
            rows, is24Hour = true, allDayLabel = "All day",
            alarmFallbackLabel = "Alarm", calendarFallbackLabel = "Event", locale = testLocale,
        )
        assertThat(labels[0]!!.endsWith("Alarm")).isTrue()
    }

    @Test
    fun `buildRowLabels - blank calendar title falls back to the calendar label`() {
        val rows = listOf<TimeEventFormatter.EventRow>(
            TimeEventFormatter.EventRow.Item(
                TimeBasedEvent(createTime(9, 30, 0, 0), "", TimeBasedEventType.CALENDAR),
            ),
        )
        val labels = formatter.buildRowLabels(rows, true, "All day", "Alarm", "Event", testLocale)
        assertThat(labels[0]!!.endsWith("Event")).isTrue()
    }

    @Test
    fun `buildRowLabels - a real title is kept over the fallback`() {
        val rows = listOf<TimeEventFormatter.EventRow>(
            TimeEventFormatter.EventRow.Item(
                TimeBasedEvent(createTime(9, 30, 0, 0), "Dentist", TimeBasedEventType.CALENDAR),
            ),
        )
        val labels = formatter.buildRowLabels(rows, true, "All day", "Alarm", "Event", testLocale)
        assertThat(labels[0]!!.endsWith("Dentist")).isTrue()
    }

    @Test
    fun `buildRowLabels - the tomorrow separator maps to null`() {
        val rows = listOf<TimeEventFormatter.EventRow>(TimeEventFormatter.EventRow.TomorrowSeparator)
        val labels = formatter.buildRowLabels(rows, true, "All day", "Alarm", "Event", testLocale)
        assertThat(labels[0]).isEqualTo(null)
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