package com.github.reygnn.kolibri_launcher.ui.util

import com.github.reygnn.kolibri_launcher.core.AppConstants
import java.text.SimpleDateFormat
import java.util.Date
import java.util.GregorianCalendar
import java.util.Locale
import java.util.TimeZone

/**
 * PURE LOGIC - Timestamped export filename builder.
 *
 * Single source of truth for the `<prefix><timestamp><extension>` filenames
 * used by the backup and usage-data exports. [clock] and [locale] are
 * injectable so tests can pin time and locale deterministically; production
 * uses the [forBackup] / [forUsageExport] factories and stays parameterless:
 *
 *   val filename = FilenameBuilder.forUsageExport().build()
 *   exportLauncher.launch(filename)
 *
 * The timestamp is always formatted with a proleptic-Gregorian calendar, so
 * the numeric `yyyy` field never switches to a locale-specific era (Thai
 * Buddhist th_TH -> 2569, Japanese imperial ja_JP_JP) regardless of the
 * device's default locale. A machine filename must stay calendar-invariant.
 */
class FilenameBuilder(
    private val prefix: String,
    private val datePattern: String,
    private val extension: String,
    private val clock: () -> Long = System::currentTimeMillis,
    private val locale: Locale = Locale.getDefault(),
) {
    fun build(): String {
        val formatter = SimpleDateFormat(datePattern, locale).apply {
            calendar = GregorianCalendar(TimeZone.getDefault(), Locale.ROOT)
        }
        return "$prefix${formatter.format(Date(clock()))}$extension"
    }

    companion object {
        fun forBackup(
            clock: () -> Long = System::currentTimeMillis,
            locale: Locale = Locale.getDefault(),
        ): FilenameBuilder = FilenameBuilder(
            prefix = AppConstants.BACKUP_FILE_PREFIX,
            datePattern = AppConstants.DATE_FORMAT_BACKUP_FILENAME,
            extension = AppConstants.BACKUP_FILE_EXTENSION,
            clock = clock,
            locale = locale,
        )

        fun forUsageExport(
            clock: () -> Long = System::currentTimeMillis,
            locale: Locale = Locale.getDefault(),
        ): FilenameBuilder = FilenameBuilder(
            prefix = AppConstants.USAGE_EXPORT_FILE_PREFIX,
            datePattern = AppConstants.DATE_FORMAT_USAGE_EXPORT_FILENAME,
            extension = AppConstants.USAGE_EXPORT_FILE_EXTENSION,
            clock = clock,
            locale = locale,
        )
    }
}
