package com.github.reygnn.kolibri_launcher.domain.model

/**
 * Ergebnis eines Usage-Imports.
 */
sealed class UsageImportResult {
    data class Success(
        val packagesImported: Int,
        val timestampsImported: Int,
        val packagesSkipped: Int = 0
    ) : UsageImportResult()

    data object InvalidFormat : UsageImportResult()
    data class UnsupportedVersion(val version: String) : UsageImportResult()
    data class Error(val message: String) : UsageImportResult()
}