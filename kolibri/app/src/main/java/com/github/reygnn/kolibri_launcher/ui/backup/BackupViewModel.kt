package com.github.reygnn.kolibri_launcher.ui.backup

import com.github.reygnn.kolibri_launcher.R
import com.github.reygnn.launcher.core.TimberWrapper
import com.github.reygnn.launcher.core.MainDispatcher
import com.github.reygnn.kolibri_launcher.domain.model.ImportOptions
import com.github.reygnn.kolibri_launcher.domain.model.ImportResult
import com.github.reygnn.kolibri_launcher.domain.model.PreviewResult
import com.github.reygnn.kolibri_launcher.domain.usecase.ExportBackupUseCase
import com.github.reygnn.kolibri_launcher.domain.usecase.ImportBackupUseCase
import com.github.reygnn.kolibri_launcher.domain.usecase.PreviewBackupUseCase
import com.github.reygnn.launcher.common.ui.base.BaseViewModel
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

    override val errorEvent = UiEvent.ShowToast(R.string.error_generic)

    private val _backupState = MutableStateFlow<BackupState>(BackupState.Idle)
    val backupState: StateFlow<BackupState> = _backupState.asStateFlow()

    /**
     * The preview of the last picked file: null until it is read. A refusal is reported
     * through [backupState] at once (2a-7b), so the options dialog only ever opens for
     * [PreviewResult.Readable].
     */
    private val _previewResult = MutableStateFlow<PreviewResult?>(null)
    val previewResult: StateFlow<PreviewResult?> = _previewResult.asStateFlow()

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
            } catch (e: Throwable) {
                TimberWrapper.silentError(e, "Error exporting backup")
                _backupState.value = BackupState.Error(e.message ?: "Unknown error")
            }
        }
    }

    fun importBackup(uriString: String, options: ImportOptions) {
        launchSafe {
            try {
                _backupState.value = BackupState.Loading

                _backupState.value = stateFor(importBackupUseCase(uriString, options))
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                TimberWrapper.silentError(e, "Error importing backup")
                _backupState.value = BackupState.Error(e.message ?: "Import failed")
            }
        }
    }

    fun previewBackup(uriString: String) {
        // Synchronously, before the fragment starts waiting: never hand it the previous file's result.
        _previewResult.value = null
        launchSafe {
            val result = try {
                previewBackupUseCase(uriString)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                TimberWrapper.silentError(e, "Error previewing backup")
                PreviewResult.Refused(ImportResult.Error(e.message ?: "Preview failed"))
            }
            _previewResult.value = result
            // 2a-7b: a refused file gets its own message right away — no dialog, no timeout.
            if (result is PreviewResult.Refused) _backupState.value = stateFor(result.result)
        }
    }

    /** One mapping from an import outcome to what the screen shows; also used for a refused preview. */
    private fun stateFor(result: ImportResult): BackupState = when (result) {
        is ImportResult.Success -> BackupState.ImportSuccess(
            importedCount = result.importedCount,
            skippedCount = result.skippedCount,
            missingApps = result.missingApps,
            droppedWallpaperLayers = result.droppedWallpaperLayers,
        )
        is ImportResult.UnsupportedVersion -> BackupState.UnsupportedVersion(result.version)
        is ImportResult.LimitExceeded -> BackupState.LimitExceeded(packageCount = result.packageCount, limit = result.limit)
        is ImportResult.InvalidFormat -> BackupState.InvalidFormat
        is ImportResult.ForeignBackup -> BackupState.ForeignBackup(result.appId)
        is ImportResult.OutdatedBackup -> BackupState.OutdatedBackup
        is ImportResult.Error -> BackupState.Error(result.message)
    }

    fun resetBackupState() {
        executeSafe {
            // Not the preview: the fragment may still be waiting for it while the state of a
            // refusal is shown and reset. previewBackup clears it for the next file.
            _backupState.value = BackupState.Idle
        }
    }
}
