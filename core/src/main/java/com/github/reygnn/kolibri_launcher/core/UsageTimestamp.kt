package com.github.reygnn.kolibri_launcher.core

/**
 * Validates a stored usage timestamp for plausibility: strictly positive, not
 * in the future relative to [currentTime], and no older than
 * [AppConstants.MAX_TIMESTAMP_AGE_MS].
 *
 * Shared by [com.github.reygnn.kolibri_launcher.data.AppUsageRepositoryImpl] and
 * [com.github.reygnn.kolibri_launcher.data.UsageExportRepositoryImpl], which both
 * filter raw stored timestamps against exactly this rule — previously as a
 * byte-identical private copy in each impl (a silent-drift hazard). Pure Long
 * comparisons and subtraction, so it never throws.
 */
fun isValidUsageTimestamp(timestamp: Long, currentTime: Long): Boolean {
    return timestamp > 0 &&
            timestamp <= currentTime &&
            (currentTime - timestamp) <= AppConstants.MAX_TIMESTAMP_AGE_MS
}
