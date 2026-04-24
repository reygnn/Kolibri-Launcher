package com.github.reygnn.kolibri_launcher.ui.backup

import com.github.reygnn.kolibri_launcher.core.AppConstants

/**
 * PURE LOGIC - Missing Apps Formatter
 *
 * Formatiert eine Menge von "packageName/className"-Strings zu einer
 * untereinander-gelisteten Darstellung. Überzählige Einträge werden abgeschnitten
 * und per [Result.overflowCount] gemeldet, damit das Fragment den
 * "... und X weitere"-Suffix aus einer StringResource (inkl. Pluralisierung)
 * zusammenbauen kann.
 *
 * Behält nur den packageName-Teil vor dem "/".
 *
 * Verwendung im Fragment:
 *
 *   val fmt = formatter.format(missingApps)
 *   val display = buildString {
 *       append(message)
 *       if (fmt.listText.isNotEmpty()) {
 *           append("\n\n")
 *           append(getString(R.string.backup_missing_apps))
 *           append(":\n")
 *           append(fmt.listText)
 *           if (fmt.hasOverflow) {
 *               append("\n... ")
 *               append(getString(R.string.backup_and_more, fmt.overflowCount))
 *           }
 *       }
 *   }
 */
class MissingAppsFormatter(
    private val maxDisplayed: Int = AppConstants.MAX_MISSING_APPS_IN_SNACKBAR,
) {
    fun format(missingApps: Set<String>): Result {
        if (missingApps.isEmpty()) return Result(listText = "", overflowCount = 0)
        val truncated = missingApps.take(maxDisplayed)
        val listText = truncated.joinToString("\n") { it.substringBefore("/") }
        val overflowCount = (missingApps.size - maxDisplayed).coerceAtLeast(0)
        return Result(listText, overflowCount)
    }

    data class Result(
        val listText: String,
        val overflowCount: Int,
    ) {
        val hasOverflow: Boolean get() = overflowCount > 0
    }
}
