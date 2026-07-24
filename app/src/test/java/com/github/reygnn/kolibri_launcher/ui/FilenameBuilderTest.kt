package com.github.reygnn.kolibri_launcher.ui

import com.github.reygnn.kolibri_launcher.rule.TimberRule
import com.github.reygnn.kolibri_launcher.support.TimeZoneTestSupport.utcMillis
import com.github.reygnn.kolibri_launcher.support.TimeZoneTestSupport.withUtcTimeZone
import com.github.reygnn.kolibri_launcher.ui.util.FilenameBuilder
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import java.util.Locale

class FilenameBuilderTest {

    @get:Rule
    val timberRule = TimberRule()

    // A fixed reference instant reused across cases: 2024-03-15 10:30:45 UTC.
    private val referenceMillis = utcMillis(2024, 3, 15, 10, 30, 45)

    @Test
    fun `generic builder assembles prefix + timestamp + extension`() = withUtcTimeZone {
        val builder = FilenameBuilder(
            prefix = "pre_",
            datePattern = "yyyyMMdd",
            extension = ".ext",
            clock = { referenceMillis },
            locale = Locale.US,
        )
        assertEquals("pre_20240315.ext", builder.build())
    }

    @Test
    fun `forBackup produces documented kolibri_backup name`() = withUtcTimeZone {
        val builder = FilenameBuilder.forBackup(clock = { referenceMillis }, locale = Locale.US)
        assertEquals("kolibri_backup_20240315_103045.zip", builder.build())
    }

    @Test
    fun `forUsageExport produces documented kolibri_usage name`() = withUtcTimeZone {
        val builder = FilenameBuilder.forUsageExport(clock = { referenceMillis }, locale = Locale.US)
        assertEquals("kolibri_usage_2024-03-15_103045.json", builder.build())
    }

    @Test
    fun `filename zero-pads single-digit month day hour minute second`() = withUtcTimeZone {
        val millis = utcMillis(2024, 1, 2, 3, 4, 5)
        assertEquals(
            "kolibri_backup_20240102_030405.zip",
            FilenameBuilder.forBackup(clock = { millis }, locale = Locale.US).build(),
        )
        assertEquals(
            "kolibri_usage_2024-01-02_030405.json",
            FilenameBuilder.forUsageExport(clock = { millis }, locale = Locale.US).build(),
        )
    }

    @Test
    fun `year stays Gregorian under a Buddhist-calendar locale (th_TH)`() = withUtcTimeZone {
        // th_TH defaults to the Buddhist calendar on the JDK (2024 -> 2567). The
        // builder must force Gregorian so the machine filename never carries an
        // era year. Without the calendar override these assert "2567".
        val thTH = Locale.of("th", "TH")
        assertEquals(
            "kolibri_backup_20240315_103045.zip",
            FilenameBuilder.forBackup(clock = { referenceMillis }, locale = thTH).build(),
        )
        assertEquals(
            "kolibri_usage_2024-03-15_103045.json",
            FilenameBuilder.forUsageExport(clock = { referenceMillis }, locale = thTH).build(),
        )
    }

    @Test
    fun `filename is stable across Gregorian locales (digits only)`() = withUtcTimeZone {
        val us = FilenameBuilder.forUsageExport(clock = { referenceMillis }, locale = Locale.US).build()
        val de = FilenameBuilder.forUsageExport(clock = { referenceMillis }, locale = Locale.GERMAN).build()
        assertEquals(us, de)
        assertTrue(us.startsWith("kolibri_usage_"))
        assertTrue(us.endsWith(".json"))
    }

    @Test
    fun `builder reads clock on every build (does not cache)`() = withUtcTimeZone {
        var current = utcMillis(2024, 1, 1, 0, 0, 0)
        val builder = FilenameBuilder.forBackup(clock = { current }, locale = Locale.US)
        val first = builder.build()

        current = utcMillis(2025, 6, 15, 12, 34, 56)
        val second = builder.build()

        assertEquals("kolibri_backup_20240101_000000.zip", first)
        assertEquals("kolibri_backup_20250615_123456.zip", second)
    }
}
