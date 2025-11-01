package com.github.reygnn.kolibri_launcher

import android.net.Uri
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject

@HiltViewModel
class BackupViewModel @Inject constructor(
    private val backupManager: BackupRepository,
    @MainDispatcher mainDispatcher: CoroutineDispatcher
) : BaseViewModel<UiEvent>(mainDispatcher) {

    private val _backupState = MutableStateFlow<BackupState>(BackupState.Idle)
    val backupState: StateFlow<BackupState> = _backupState.asStateFlow()

    private val _backupPreview = MutableStateFlow<BackupPreview?>(null)
    val backupPreview: StateFlow<BackupPreview?> = _backupPreview.asStateFlow()

    fun exportBackup(uri: Uri) {
        launchSafe {
            try {
                _backupState.value = BackupState.Loading
                val success = backupManager.saveBackupToFile(uri)

                _backupState.value = if (success) {
                    BackupState.ExportSuccess
                } else {
                    BackupState.Error("Export failed")
                }
            } catch (e: Throwable) {
                TimberWrapper.silentError(e, "Error exporting backup")
                _backupState.value = BackupState.Error(e.message ?: "Unknown error")
            }
        }
    }

    fun previewBackup(uri: Uri) {
        launchSafe {
            try {
                val preview = backupManager.previewBackup(uri)
                _backupPreview.value = preview
            } catch (e: Throwable) {
                TimberWrapper.silentError(e, "Error previewing backup")
                _backupPreview.value = null
            }
        }
    }

    fun importBackup(uri: Uri, options: ImportOptions) {
        launchSafe {
            try {
                _backupState.value = BackupState.Loading

                when (val result = backupManager.loadBackupFromFile(uri, options)) {
                    is ImportResult.Success -> {
                        _backupState.value = BackupState.ImportSuccess(
                            importedCount = result.importedCount,
                            skippedCount = result.skippedCount,
                            missingApps = result.missingApps
                        )
                    }
                    is ImportResult.UnsupportedVersion -> {
                        _backupState.value = BackupState.UnsupportedVersion(result.version)
                    }
                    is ImportResult.LimitExceeded -> {
                        _backupState.value = BackupState.LimitExceeded(
                            packageCount = result.packageCount,
                            limit = result.limit
                        )
                    }
                    is ImportResult.InvalidFormat -> {
                        _backupState.value = BackupState.InvalidFormat
                    }
                    is ImportResult.Error -> {
                        _backupState.value = BackupState.Error(result.message)
                    }
                }
            } catch (e: Throwable) {
                TimberWrapper.silentError(e, "Error importing backup")
                _backupState.value = BackupState.Error(e.message ?: "Import failed")
            }
        }
    }

    fun resetBackupState() {
        executeSafe {
            _backupState.value = BackupState.Idle
            _backupPreview.value = null
        }
    }
}

sealed class BackupState {
    object Idle : BackupState()
    object Loading : BackupState()
    object ExportSuccess : BackupState()
    data class ImportSuccess(
        val importedCount: Int,
        val skippedCount: Int,
        val missingApps: Set<String>
    ) : BackupState()
    data class LimitExceeded(
        val packageCount: Int,
        val limit: Int
    ) : BackupState()
    data class UnsupportedVersion(val version: String) : BackupState()
    object InvalidFormat : BackupState()
    data class Error(val message: String) : BackupState()
}