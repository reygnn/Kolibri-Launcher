package com.github.reygnn.kolibri_launcher.ui.home

import com.github.reygnn.kolibri_launcher.domain.model.TimeBasedEvent
import com.github.reygnn.kolibri_launcher.domain.model.TimeBasedEventType
import com.github.reygnn.kolibri_launcher.rule.TimberRule
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import java.util.Calendar
import java.util.Locale

class TimeEventFormatterTest {

    @get:Rule
    val timberRule = TimberRule()

    private val formatter = TimeEventFormatter()

    // Wir nutzen Locale.US für vorhersehbare "AM/PM" Tests
    private val testLocale = Locale.US

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

        val row = formatter.formatEventRow(event, is24Hour = true, locale = testLocale)

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

        val row = formatter.formatEventRow(event, is24Hour = true, locale = testLocale)

        assertTrue("expected no leading glyph, was: $row", row.startsWith("14:30"))
        assertFalse("expected no calendar glyph, was: $row", row.contains("📅"))
        assertTrue("expected title, was: $row", row.contains("Standup"))
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