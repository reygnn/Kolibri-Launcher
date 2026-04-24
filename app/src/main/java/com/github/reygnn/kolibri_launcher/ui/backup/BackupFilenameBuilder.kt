package com.github.reygnn.kolibri_launcher.ui.backup

import com.github.reygnn.kolibri_launcher.core.AppConstants
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * PURE LOGIC - Backup Filename Builder
 *
 * Erzeugt den Dateinamen für den Backup-Export aus einem Timestamp.
 * Format: kolibri_backup_yyyyMMdd_HHmmss.zip
 *
 * Clock und Locale sind injectable, damit Tests Zeit & Locale
 * deterministisch setzen können. Die Prod-Verwendung bleibt parameterlos:
 *
 *   val filename = BackupFilenameBuilder().build()
 *   exportLauncher.launch(filename)
 */
class BackupFilenameBuilder(
    private val clock: () -> Long = System::currentTimeMillis,
    private val locale: Locale = Locale.getDefault(),
) {
    fun build(): String {
        val timestamp = SimpleDateFormat(
            AppConstants.DATE_FORMAT_BACKUP_FILENAME,
            locale,
        ).format(Date(clock()))
        return "${AppConstants.BACKUP_FILE_PREFIX}$timestamp${AppConstants.BACKUP_FILE_EXTENSION}"
    }
}
