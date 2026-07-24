package com.github.reygnn.kolibri_launcher.ui.usageexport

import com.github.reygnn.kolibri_launcher.core.AppConstants
import java.text.SimpleDateFormat
import java.util.Date
import java.util.GregorianCalendar
import java.util.Locale
import java.util.TimeZone

/**
 * PURE LOGIC - Usage Export Filename Builder
 *
 * Builds the filename for the usage-data export from a timestamp.
 * Format: kolibri_usage_yyyy-MM-dd_HHmmss.json
 *
 * Sibling of [com.github.reygnn.kolibri_launcher.ui.backup.BackupFilenameBuilder];
 * same injectable-clock/locale shape so tests can pin time & locale
 * deterministically. Production use stays parameterless:
 *
 *   val filename = UsageExportFilenameBuilder().build()
 *   exportLauncher.launch(filename)
 */
class UsageExportFilenameBuilder(
    private val clock: () -> Long = System::currentTimeMillis,
    private val locale: Locale = Locale.getDefault(),
) {
    fun build(): String {
        val formatter = SimpleDateFormat(
            AppConstants.DATE_FORMAT_USAGE_EXPORT_FILENAME,
            locale,
        ).apply {
            // Force a proleptic-Gregorian calendar so the numeric `yyyy` field
            // never switches to a locale-specific era (Thai Buddhist th_TH ->
            // 2569, Japanese imperial ja_JP_JP). A machine filename must stay
            // calendar-invariant regardless of the device's default locale.
            calendar = GregorianCalendar(TimeZone.getDefault(), Locale.ROOT)
        }
        val timestamp = formatter.format(Date(clock()))
        return "${AppConstants.USAGE_EXPORT_FILE_PREFIX}$timestamp${AppConstants.USAGE_EXPORT_FILE_EXTENSION}"
    }
}
