package com.github.reygnn.kolibri_launcher.ui

import com.github.reygnn.kolibri_launcher.rule.TimberRule
import com.github.reygnn.kolibri_launcher.support.FormattingTestSupport.utcMillis
import com.github.reygnn.kolibri_launcher.support.FormattingTestSupport.withDefaultLocale
import com.github.reygnn.kolibri_launcher.support.FormattingTestSupport.withUtcTimeZone
import com.github.reygnn.kolibri_launcher.ui.util.FilenameBuilder
import org.junit.Assert.assertEquals
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
        )
        assertEquals("pre_20240315.ext", builder.build())
    }

    @Test
    fun `forBackup produces documented kolibri_backup name`() = withUtcTimeZone {
        val builder = FilenameBuilder.forBackup(clock = { referenceMillis })
        assertEquals("kolibri_backup_2024-03-15_103045.zip", builder.build())
    }

    @Test
    fun `forUsageExport produces documented kolibri_usage name`() = withUtcTimeZone {
        val builder = FilenameBuilder.forUsageExport(clock = { referenceMillis })
        assertEquals("kolibri_usage_2024-03-15_103045.json", builder.build())
    }

    @Test
    fun `filename zero-pads single-digit month day hour minute second`() = withUtcTimeZone {
        val millis = utcMillis(2024, 1, 2, 3, 4, 5)
        assertEquals(
            "kolibri_backup_2024-01-02_030405.zip",
            FilenameBuilder.forBackup(clock = { millis }).build(),
        )
        assertEquals(
            "kolibri_usage_2024-01-02_030405.json",
            FilenameBuilder.forUsageExport(clock = { millis }).build(),
        )
    }

    @Test
    fun `filename stays ASCII-Gregorian under hostile default locales`() = withUtcTimeZone {
        // Production calls the parameterless factory, which formats with
        // Locale.ROOT independent of Locale.getDefault(). Pin the *default*
        // locale to hostile ones and assert the output is unaffected:
        //   ar_SA -> would emit Arabic-Indic digits (٢٠٢٤...)
        //   th_TH -> would emit a Buddhist year (2024 -> 2567)
        // This exercises the real production path, not an injected locale.
        withDefaultLocale(Locale.forLanguageTag("ar-SA")) {
            assertEquals(
                "kolibri_backup_2024-03-15_103045.zip",
                FilenameBuilder.forBackup(clock = { referenceMillis }).build(),
            )
        }
        withDefaultLocale(Locale.forLanguageTag("th-TH")) {
            assertEquals(
                "kolibri_usage_2024-03-15_103045.json",
                FilenameBuilder.forUsageExport(clock = { referenceMillis }).build(),
            )
        }
    }

    @Test
    fun `builder reads clock on every build (does not cache)`() = withUtcTimeZone {
        var current = utcMillis(2024, 1, 1, 0, 0, 0)
        val builder = FilenameBuilder.forBackup(clock = { current })
        val first = builder.build()

        current = utcMillis(2025, 6, 15, 12, 34, 56)
        val second = builder.build()

        assertEquals("kolibri_backup_2024-01-01_000000.zip", first)
        assertEquals("kolibri_backup_2025-06-15_123456.zip", second)
    }
}
