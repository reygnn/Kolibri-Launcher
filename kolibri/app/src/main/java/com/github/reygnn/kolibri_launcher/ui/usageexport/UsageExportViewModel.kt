package com.github.reygnn.kolibri_launcher.ui.usageexport

import com.github.reygnn.kolibri_launcher.core.MainDispatcher
import com.github.reygnn.kolibri_launcher.core.TimberWrapper
import com.github.reygnn.kolibri_launcher.domain.model.UsageImportResult
import com.github.reygnn.kolibri_launcher.domain.usecase.ExportUsageToFileUseCase
import com.github.reygnn.kolibri_launcher.domain.usecase.ImportUsageFromFileUseCase
import com.github.reygnn.kolibri_launcher.ui.base.BaseViewModel
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject

@HiltViewModel
class UsageExportViewModel @Inject constructor(
    private val exportUsageToFileUseCase: ExportUsageToFileUseCase,
    private val importUsageFromFileUseCase: ImportUsageFromFileUseCase,
    @MainDispatcher mainDispatcher: CoroutineDispatcher
) : BaseViewModel<UsageExportUiEvent>(mainDispatcher) {

    private val _isLoading = MutableStateFlow(false)
    val isLoading: StateFlow<Boolean> = _isLoading.asStateFlow()

    fun exportToFile(uriString: String) {
        launchSafe {
            _isLoading.value = true
            try {
                exportUsageToFileUseCase(uriString)
                    .onSuccess {
                        sendEvent(UsageExportUiEvent.ExportSuccess)
                    }
                    .onFailure { error ->
                        TimberWrapper.silentError(error, "Export failed in ViewModel")
                        sendEvent(UsageExportUiEvent.ExportError(error.message ?: "Unknown error"))
                    }
            } finally {
                _isLoading.value = false
            }
        }
    }

    fun importFromFile(uriString: String, mergeWithExisting: Boolean) {
        launchSafe {
            _isLoading.value = true
            try {
                val result = importUsageFromFileUseCase(uriString, mergeWithExisting)
                handleImportResult(result)
            } finally {
                _isLoading.value = false
            }
        }
    }

    /**
     * Extracted into a private helper for the readability of importFromFile.
     */
    private suspend fun handleImportResult(result: UsageImportResult) {
        when (result) {
            is UsageImportResult.Success -> {
                sendEvent(
                    UsageExportUiEvent.ImportSuccess(
                        packagesImported = result.packagesImported,
                        timestampsImported = result.timestampsImported
                    )
                )
            }
            is UsageImportResult.InvalidFormat -> {
                sendEvent(UsageExportUiEvent.InvalidFormat)
            }
            is UsageImportResult.UnsupportedVersion -> {
                sendEvent(UsageExportUiEvent.UnsupportedVersion(result.version))
            }
            is UsageImportResult.Error -> {
                TimberWrapper.silentError("Import error: ${result.message}")
                sendEvent(UsageExportUiEvent.ImportError(result.message))
            }
        }
    }
}
