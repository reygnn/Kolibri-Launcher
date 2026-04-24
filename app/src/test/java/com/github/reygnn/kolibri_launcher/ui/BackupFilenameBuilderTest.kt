package com.github.reygnn.kolibri_launcher.ui

import com.github.reygnn.kolibri_launcher.rule.TimberRule
import com.github.reygnn.kolibri_launcher.ui.backup.BackupFilenameBuilder
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import java.util.Calendar
import java.util.Locale
import java.util.TimeZone

class BackupFilenameBuilderTest {

    @get:Rule
    val timberRule = TimberRule()

    // SimpleDateFormat nutzt die Default-TimeZone des JVM. Für deterministische
    // Tests setzen wir sie pro Test auf UTC. Ohne das würde yyyyMMdd_HHmmss je
    // nach CI-Host/Dev-Maschine variieren.
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
        val builder = BackupFilenameBuilder(clock = { millis }, locale = Locale.US)
        val filename = builder.build()
        assertTrue(
            "Erwartet prefix 'kolibri_backup_', erhielt: $filename",
            filename.startsWith("kolibri_backup_"),
        )
    }

    @Test
    fun `filename ends with zip extension`() = withUtcTimeZone {
        val millis = utcMillis(2024, 3, 15, 10, 30, 45)
        val builder = BackupFilenameBuilder(clock = { millis }, locale = Locale.US)
        assertTrue(builder.build().endsWith(".zip"))
    }

    @Test
    fun `filename embeds formatted timestamp yyyyMMdd_HHmmss`() = withUtcTimeZone {
        val millis = utcMillis(2024, 3, 15, 10, 30, 45)
        val builder = BackupFilenameBuilder(clock = { millis }, locale = Locale.US)
        assertEquals("kolibri_backup_20240315_103045.zip", builder.build())
    }

    @Test
    fun `filename zero-pads single-digit month day hour minute second`() = withUtcTimeZone {
        val millis = utcMillis(2024, 1, 2, 3, 4, 5)
        val builder = BackupFilenameBuilder(clock = { millis }, locale = Locale.US)
        assertEquals("kolibri_backup_20240102_030405.zip", builder.build())
    }

    @Test
    fun `filename is stable across locales (digits only, no locale numerals)`() = withUtcTimeZone {
        // yyyyMMdd_HHmmss enthält keine sprachlichen Bestandteile -> Locale darf egal sein.
        val millis = utcMillis(2024, 3, 15, 10, 30, 45)
        val us = BackupFilenameBuilder(clock = { millis }, locale = Locale.US).build()
        val de = BackupFilenameBuilder(clock = { millis }, locale = Locale.GERMAN).build()
        assertEquals(us, de)
    }

    @Test
    fun `builder reads clock on every build (does not cache)`() = withUtcTimeZone {
        var current = utcMillis(2024, 1, 1, 0, 0, 0)
        val builder = BackupFilenameBuilder(clock = { current }, locale = Locale.US)
        val first = builder.build()

        current = utcMillis(2025, 6, 15, 12, 34, 56)
        val second = builder.build()

        assertEquals("kolibri_backup_20240101_000000.zip", first)
        assertEquals("kolibri_backup_20250615_123456.zip", second)
    }
}
