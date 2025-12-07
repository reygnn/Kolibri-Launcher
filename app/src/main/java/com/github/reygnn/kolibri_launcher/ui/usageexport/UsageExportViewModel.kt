package com.github.reygnn.kolibri_launcher.ui.usageexport

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.github.reygnn.kolibri_launcher.core.TimberWrapper
import com.github.reygnn.kolibri_launcher.domain.model.UsageImportResult
import com.github.reygnn.kolibri_launcher.domain.repository.UsageExportRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
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
    private val usageExportRepository: UsageExportRepository
) : ViewModel() {

    private val _uiEvent = MutableSharedFlow<UsageExportUiEvent>()
    val uiEvent: SharedFlow<UsageExportUiEvent> = _uiEvent.asSharedFlow()

    private val _isLoading = MutableStateFlow(false)
    val isLoading: StateFlow<Boolean> = _isLoading.asStateFlow()

    fun exportToFile(uriString: String) {
        viewModelScope.launch {
            _isLoading.value = true
            try {
                val success = usageExportRepository.saveToFile(uriString)
                if (success) {
                    _uiEvent.emit(UsageExportUiEvent.ExportSuccess)
                } else {
                    _uiEvent.emit(UsageExportUiEvent.ExportError("Could not write file"))
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                TimberWrapper.silentError(e, "Error exporting usage data")
                _uiEvent.emit(UsageExportUiEvent.ExportError(e.message ?: "Unknown error"))
            } finally {
                _isLoading.value = false
            }
        }
    }

    fun importFromFile(uriString: String, mergeWithExisting: Boolean) {
        viewModelScope.launch {
            _isLoading.value = true
            try {
                when (val result = usageExportRepository.loadFromFile(uriString, mergeWithExisting)) {
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
                        _uiEvent.emit(UsageExportUiEvent.ImportError(result.message))
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                TimberWrapper.silentError(e, "Error importing usage data")
                _uiEvent.emit(UsageExportUiEvent.ImportError(e.message ?: "Unknown error"))
            } finally {
                _isLoading.value = false
            }
        }
    }
}