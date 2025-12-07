package com.github.reygnn.kolibri_launcher.domain.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Exportdaten für App-Nutzungsstatistiken.
 */
@Serializable
data class UsageExportData(
    val version: String,
    @SerialName("export_timestamp")
    val exportTimestamp: Long,
    @SerialName("app_version")
    val appVersion: String,
    @SerialName("usage_data")
    val usageData: Map<String, List<Long>>  // packageName -> timestamps (neueste zuerst)
)

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