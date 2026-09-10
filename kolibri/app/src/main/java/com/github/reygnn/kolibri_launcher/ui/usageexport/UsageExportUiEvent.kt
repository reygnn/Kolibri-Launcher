package com.github.reygnn.kolibri_launcher.ui.usageexport

sealed class UsageExportUiEvent {
    data object ExportSuccess : UsageExportUiEvent()
    data class ExportError(val message: String) : UsageExportUiEvent()
    data class ImportSuccess(val packagesImported: Int, val timestampsImported: Int) : UsageExportUiEvent()
    data class ImportError(val message: String) : UsageExportUiEvent()
    data object InvalidFormat : UsageExportUiEvent()
    data class UnsupportedVersion(val version: String) : UsageExportUiEvent()
}