package com.github.reygnn.kolibri_launcher.ui.util

import com.github.reygnn.kolibri_launcher.core.AppConstants
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * PURE LOGIC - Timestamped export filename builder.
 *
 * Single source of truth for the `<prefix><timestamp><extension>` filenames
 * used by the backup and usage-data exports. [clock] is injectable so tests
 * can pin time deterministically; production uses the [forBackup] /
 * [forUsageExport] factories and stays parameterless:
 *
 *   val filename = FilenameBuilder.forUsageExport().build()
 *   exportLauncher.launch(filename)
 *
 * The timestamp is always formatted with [Locale.ROOT], so the filename is
 * fully locale-invariant regardless of the device's default locale:
 *  - ASCII digits, never Arabic-Indic (ar) / Persian (fa) numerals; and
 *  - a Gregorian calendar, so the numeric `yyyy` field never switches to a
 *    locale-specific era (Thai Buddhist th_TH -> 2569, Japanese imperial
 *    ja_JP_JP).
 * A machine filename must not depend on the user's locale. Only the local time
 * zone is honoured (the timestamp reflects device-local wall-clock time).
 */
class FilenameBuilder(
    private val prefix: String,
    private val datePattern: String,
    private val extension: String,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    fun build(): String {
        val timestamp = SimpleDateFormat(datePattern, Locale.ROOT).format(Date(clock()))
        return "$prefix$timestamp$extension"
    }

    companion object {
        fun forBackup(
            clock: () -> Long = System::currentTimeMillis,
        ): FilenameBuilder = FilenameBuilder(
            prefix = AppConstants.BACKUP_FILE_PREFIX,
            datePattern = AppConstants.DATE_FORMAT_BACKUP_FILENAME,
            extension = AppConstants.BACKUP_FILE_EXTENSION,
            clock = clock,
        )

        fun forUsageExport(
            clock: () -> Long = System::currentTimeMillis,
        ): FilenameBuilder = FilenameBuilder(
            prefix = AppConstants.USAGE_EXPORT_FILE_PREFIX,
            datePattern = AppConstants.DATE_FORMAT_USAGE_EXPORT_FILENAME,
            extension = AppConstants.USAGE_EXPORT_FILE_EXTENSION,
            clock = clock,
        )
    }
}
