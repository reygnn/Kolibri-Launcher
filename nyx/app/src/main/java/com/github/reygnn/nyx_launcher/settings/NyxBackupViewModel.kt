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
import kotlinx.coroutines.async
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
 * readable one does. The preview timeout guards against a provider that hangs, nothing else —
 * also one that ignores cancellation (2b-3b-c).
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
        // The timeout sits on await(), not around the call: a provider blocked in read() does not
        // react to cancellation, so a timeout around the call itself only returns once the read
        // ends (measured: 2028 instead of 207 ms). The abandoned read is cancelled and dropped.
        val reading = async { backupRepository.previewBackup(uriString) }
        val result = withTimeoutOrNull(AppConstants.BACKUP_PREVIEW_TIMEOUT_MS) { reading.await() }
            ?: PreviewResult.Refused(ImportResult.Error("Preview timed out")).also { reading.cancel() }
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
