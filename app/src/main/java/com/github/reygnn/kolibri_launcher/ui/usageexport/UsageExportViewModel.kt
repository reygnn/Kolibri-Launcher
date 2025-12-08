package com.github.reygnn.kolibri_launcher.ui.usageexport

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.github.reygnn.kolibri_launcher.core.TimberWrapper
import com.github.reygnn.kolibri_launcher.domain.model.UsageImportResult
import com.github.reygnn.kolibri_launcher.domain.usecase.ExportUsageToFileUseCase
import com.github.reygnn.kolibri_launcher.domain.usecase.ImportUsageFromFileUseCase
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class UsageExportViewModel @Inject constructor(
    private val exportUsageToFileUseCase: ExportUsageToFileUseCase,
    private val importUsageFromFileUseCase: ImportUsageFromFileUseCase
) : ViewModel() {

    private val _uiEvent = MutableSharedFlow<UsageExportUiEvent>()
    val uiEvent: SharedFlow<UsageExportUiEvent> = _uiEvent.asSharedFlow()

    private val _isLoading = MutableStateFlow(false)
    val isLoading: StateFlow<Boolean> = _isLoading.asStateFlow()

    fun exportToFile(uriString: String) {
        viewModelScope.launch {
            _isLoading.value = true

            exportUsageToFileUseCase(uriString)
                .onSuccess {
                    _uiEvent.emit(UsageExportUiEvent.ExportSuccess)
                }
                .onFailure { error ->
                    TimberWrapper.silentError(error, "Export failed in ViewModel")
                    _uiEvent.emit(UsageExportUiEvent.ExportError(error.message ?: "Unknown error"))
                }

            _isLoading.value = false
        }
    }

    fun importFromFile(uriString: String, mergeWithExisting: Boolean) {
        viewModelScope.launch {
            _isLoading.value = true

            val result = importUsageFromFileUseCase(uriString, mergeWithExisting)
            handleImportResult(result)

            _isLoading.value = false
        }
    }

    /**
     * Ausgelagert in private Helper-Funktion für bessere Lesbarkeit von importFromFile
     */
    private suspend fun handleImportResult(result: UsageImportResult) {
        when (result) {
            is UsageImportResult.Success -> {
                _uiEvent.emit(
                    UsageExportUiEvent.ImportSuccess(
                        packagesImported = result.packagesImported,
                        timestampsImported = result.timestampsImported
                    )
                )
            }
            is UsageImportResult.InvalidFormat -> {
                _uiEvent.emit(UsageExportUiEvent.InvalidFormat)
            }
            is UsageImportResult.UnsupportedVersion -> {
                _uiEvent.emit(UsageExportUiEvent.UnsupportedVersion(result.version))
            }
            is UsageImportResult.Error -> {
                TimberWrapper.silentError("Import error: ${result.message}")
                _uiEvent.emit(UsageExportUiEvent.ImportError(result.message))
            }
        }
    }
}