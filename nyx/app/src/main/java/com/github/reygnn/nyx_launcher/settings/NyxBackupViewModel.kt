package com.github.reygnn.nyx_launcher.settings

import com.github.reygnn.launcher.common.ui.base.BaseViewModel
import com.github.reygnn.launcher.core.AppConstants
import com.github.reygnn.launcher.core.MainDispatcher
import com.github.reygnn.nyx_launcher.home.model.BackupPreview
import com.github.reygnn.nyx_launcher.home.model.ImportOptions
import com.github.reygnn.nyx_launcher.home.model.ImportResult
import com.github.reygnn.nyx_launcher.home.model.PreviewResult
import com.github.reygnn.nyx_launcher.home.repository.BackupRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withTimeoutOrNull
import javax.inject.Inject

/** One-time UI events of [NyxBackupViewModel]. */
sealed interface BackupEvent {
    data class Show(val message: BackupMessage) : BackupEvent

    /** A readable backup: the fragment opens the restore dialog with these choices. */
    data class ChooseImportOptions(
        val uriString: String,
        val preview: BackupPreview,
        val ui: ImportOptionsUiState,
    ) : BackupEvent

    /** After a successful import: back to home, which renders the restored state. */
    data object CloseSettings : BackupEvent
}

/**
 * Export, preview and import for Nyx's settings (2b-3b), in the form of Kolibri's 2a-7b: a
 * refused file is reported at once with its own message and never opens the dialog; only a
 * readable one does. The preview timeout guards against a provider that hangs, nothing else.
 */
@HiltViewModel
class NyxBackupViewModel @Inject constructor(
    private val backupRepository: BackupRepository,
    @MainDispatcher mainDispatcher: CoroutineDispatcher,
) : BaseViewModel<BackupEvent>(mainDispatcher) {

    override val errorEvent: BackupEvent = BackupEvent.Show(BackupMessage.ImportFailed)

    fun export(uriString: String) = launchSafe {
        val ok = backupRepository.saveBackupToFile(uriString)
        sendEvent(BackupEvent.Show(if (ok) BackupMessage.ExportDone else BackupMessage.ExportFailed))
    }

    fun previewForImport(uriString: String) = launchSafe {
        val result = withTimeoutOrNull(AppConstants.BACKUP_PREVIEW_TIMEOUT_MS) { backupRepository.previewBackup(uriString) }
            ?: PreviewResult.Refused(ImportResult.Error("Preview timed out"))
        when (result) {
            is PreviewResult.Readable ->
                sendEvent(BackupEvent.ChooseImportOptions(uriString, result.preview, ImportOptionsUiState.from(result.preview)))
            is PreviewResult.Refused -> sendEvent(BackupEvent.Show(BackupMessage.forImport(result.result)))
        }
    }

    fun import(uriString: String, options: ImportOptions) = launchSafe {
        if (options.importNothing) {
            sendEvent(BackupEvent.Show(BackupMessage.NothingSelected))
            return@launchSafe
        }
        val result = backupRepository.loadBackupFromFile(uriString, options)
        sendEvent(BackupEvent.Show(BackupMessage.forImport(result)))
        if (result is ImportResult.Success) sendEvent(BackupEvent.CloseSettings)
    }
}
