package com.github.reygnn.kolibri_launcher.ui

import com.github.reygnn.kolibri_launcher.rule.TimberRule
import com.github.reygnn.kolibri_launcher.ui.usageexport.UsageExportFilenameBuilder
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import java.util.Calendar
import java.util.Locale
import java.util.TimeZone

class UsageExportFilenameBuilderTest {

    @get:Rule
    val timberRule = TimberRule()

    // SimpleDateFormat uses the JVM default time zone. For deterministic tests
    // we pin it to UTC per test — otherwise yyyy-MM-dd_HHmmss would vary by
    // CI host / dev machine.
    private fun withUtcTimeZone(block: () -> Unit) {
        val previous = TimeZone.getDefault()
        try {
            TimeZone.setDefault(TimeZone.getTimeZone("UTC"))
            block()
        } finally {
            TimeZone.setDefault(previous)
        }
    }

    private fun utcMillis(
        year: Int, month: Int, day: Int,
        hour: Int, minute: Int, second: Int,
    ): Long {
        val cal = Calendar.getInstance(TimeZone.getTimeZone("UTC")).apply {
            clear()
            set(year, month - 1, day, hour, minute, second)
        }
        return cal.timeInMillis
    }

    @Test
    fun `filename starts with correct prefix`() = withUtcTimeZone {
        val millis = utcMillis(2024, 3, 15, 10, 30, 45)
        val builder = UsageExportFilenameBuilder(clock = { millis }, locale = Locale.US)
        val filename = builder.build()
        assertTrue(
            "Expected prefix 'kolibri_usage_', got: $filename",
            filename.startsWith("kolibri_usage_"),
        )
    }

    @Test
    fun `filename ends with json extension`() = withUtcTimeZone {
        val millis = utcMillis(2024, 3, 15, 10, 30, 45)
        val builder = UsageExportFilenameBuilder(clock = { millis }, locale = Locale.US)
        assertTrue(builder.build().endsWith(".json"))
    }

    @Test
    fun `filename embeds formatted timestamp yyyy-MM-dd_HHmmss`() = withUtcTimeZone {
        val millis = utcMillis(2024, 3, 15, 10, 30, 45)
        val builder = UsageExportFilenameBuilder(clock = { millis }, locale = Locale.US)
        assertEquals("kolibri_usage_2024-03-15_103045.json", builder.build())
    }

    @Test
    fun `filename zero-pads single-digit month day hour minute second`() = withUtcTimeZone {
        val millis = utcMillis(2024, 1, 2, 3, 4, 5)
        val builder = UsageExportFilenameBuilder(clock = { millis }, locale = Locale.US)
        assertEquals("kolibri_usage_2024-01-02_030405.json", builder.build())
    }

    @Test
    fun `filename is stable across locales (digits only, no locale numerals)`() = withUtcTimeZone {
        // yyyy-MM-dd_HHmmss carries no linguistic parts -> locale must not matter.
        val millis = utcMillis(2024, 3, 15, 10, 30, 45)
        val us = UsageExportFilenameBuilder(clock = { millis }, locale = Locale.US).build()
        val de = UsageExportFilenameBuilder(clock = { millis }, locale = Locale.GERMAN).build()
        assertEquals(us, de)
    }

    @Test
    fun `builder reads clock on every build (does not cache)`() = withUtcTimeZone {
        var current = utcMillis(2024, 1, 1, 0, 0, 0)
        val builder = UsageExportFilenameBuilder(clock = { current }, locale = Locale.US)
        val first = builder.build()

        current = utcMillis(2025, 6, 15, 12, 34, 56)
        val second = builder.build()

        assertEquals("kolibri_usage_2024-01-01_000000.json", first)
        assertEquals("kolibri_usage_2025-06-15_123456.json", second)
    }
}
