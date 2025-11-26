package com.github.reygnn.kolibri_launcher.ui.backup

import com.github.reygnn.kolibri_launcher.core.TimberWrapper
import com.github.reygnn.kolibri_launcher.di.MainDispatcher
import com.github.reygnn.kolibri_launcher.domain.model.BackupPreview
import com.github.reygnn.kolibri_launcher.domain.model.ImportOptions
import com.github.reygnn.kolibri_launcher.domain.model.ImportResult
import com.github.reygnn.kolibri_launcher.domain.usecase.ExportBackupUseCase
import com.github.reygnn.kolibri_launcher.domain.usecase.ImportBackupUseCase
import com.github.reygnn.kolibri_launcher.domain.usecase.PreviewBackupUseCase
import com.github.reygnn.kolibri_launcher.ui.base.BaseViewModel
import com.github.reygnn.kolibri_launcher.ui.base.UiEvent
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import kotlin.coroutines.cancellation.CancellationException

@HiltViewModel
class BackupViewModel @Inject constructor(
    private val exportBackupUseCase: ExportBackupUseCase,
    private val importBackupUseCase: ImportBackupUseCase,
    private val previewBackupUseCase: PreviewBackupUseCase,
    @MainDispatcher mainDispatcher: CoroutineDispatcher
) : BaseViewModel<UiEvent>(mainDispatcher) {

    private val _backupState = MutableStateFlow<BackupState>(BackupState.Idle)
    val backupState: StateFlow<BackupState> = _backupState.asStateFlow()

    private val _backupPreview = MutableStateFlow<BackupPreview?>(null)
    val backupPreview: StateFlow<BackupPreview?> = _backupPreview.asStateFlow()

    fun exportBackup(uriString: String) {
        launchSafe {
            try {
                _backupState.value = BackupState.Loading

                val success = exportBackupUseCase(uriString)

                _backupState.value = if (success) {
                    BackupState.ExportSuccess
                } else {
                    BackupState.Error("Export failed")
                }
            } catch (e: CancellationException) {
            throw e
        }catch (e: Throwable) {
                TimberWrapper.silentError(e, "Error exporting backup")
                _backupState.value = BackupState.Error(e.message ?: "Unknown error")
            }
        }
    }

    fun importBackup(uriString: String, options: ImportOptions) {
        launchSafe {
            try {
                _backupState.value = BackupState.Loading

                when (val result = importBackupUseCase(uriString, options)) {
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
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                TimberWrapper.silentError(e, "Error importing backup")
                _backupState.value = BackupState.Error(e.message ?: "Import failed")
            }
        }
    }

    fun previewBackup(uriString: String) {
        launchSafe {
            try {
                val preview = previewBackupUseCase(uriString)
                _backupPreview.value = preview
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                TimberWrapper.silentError(e, "Error previewing backup")
                _backupPreview.value = null
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
