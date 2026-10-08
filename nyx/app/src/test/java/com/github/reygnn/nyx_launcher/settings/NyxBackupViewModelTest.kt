package com.github.reygnn.nyx_launcher.settings

import kotlinx.coroutines.ExperimentalCoroutinesApi
import com.github.reygnn.launcher.core.AppConstants
import com.github.reygnn.launcher.core.testing.MainDispatcherRule
import com.github.reygnn.launcher.core.testing.recordEmissions
import com.github.reygnn.nyx_launcher.home.model.BackupPreview
import com.github.reygnn.nyx_launcher.home.model.ImportOptions
import com.github.reygnn.nyx_launcher.home.model.ImportResult
import com.github.reygnn.nyx_launcher.home.model.PreviewResult
import com.github.reygnn.nyx_launcher.home.repository.FakeBackupRepository
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Rule
import org.junit.Test

/**
 * [NyxBackupViewModel] (2b-3b, the form of Kolibri's 2a-7b): a refused file is reported at once
 * and never opens the restore dialog; a readable one opens it; the import reports its outcome.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class NyxBackupViewModelTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    private val repository = FakeBackupRepository()
    private val viewModel = NyxBackupViewModel(repository, mainDispatcherRule.testDispatcher)
    private val uri = "content://test/backup.zip"
    private val preview = BackupPreview(
        appVersion = "0.2.0", timestamp = 1L, homeItemCount = 3, drawerFolderCount = 0,
        hiddenAppCount = 1, hasSettings = true, wallpaperLayerCount = 0,
    )

    @Test
    fun a_refused_file_shows_its_message_and_no_dialog() = runTest(mainDispatcherRule.testDispatcher) {
        val events = mutableListOf<BackupEvent>()
        val collectorJob = recordEmissions(viewModel.event, events)
        repository.previewResult = PreviewResult.Refused(ImportResult.ForeignBackup("kolibri"))

        viewModel.previewForImport(uri)
        advanceUntilIdle()

        assertThat(events).containsExactly(BackupEvent.Show(BackupMessage.ForeignBackup("kolibri")))
        collectorJob.cancel()
    }

    @Test
    fun every_refusal_shows_the_same_message_as_its_import() = runTest(mainDispatcherRule.testDispatcher) {
        val events = mutableListOf<BackupEvent>()
        val collectorJob = recordEmissions(viewModel.event, events)
        val refusals = listOf(
            ImportResult.ForeignBackup("kolibri"), ImportResult.OutdatedBackup, ImportResult.UnsupportedVersion("9.0"),
            ImportResult.InvalidFormat, ImportResult.Error("Backup file is too large"),
        )
        for (refusal in refusals) {
            events.clear()
            repository.previewResult = PreviewResult.Refused(refusal)
            viewModel.previewForImport(uri)
            advanceUntilIdle()
            val previewed = events.toList()

            events.clear()
            repository.importResult = refusal
            viewModel.import(uri, ImportOptions())
            advanceUntilIdle()

            assertThat(previewed).isEqualTo(events.toList())
        }
        collectorJob.cancel()
    }

    @Test
    fun a_readable_file_opens_the_dialog_with_its_choices() = runTest(mainDispatcherRule.testDispatcher) {
        val events = mutableListOf<BackupEvent>()
        val collectorJob = recordEmissions(viewModel.event, events)
        repository.previewResult = PreviewResult.Readable(preview)

        viewModel.previewForImport(uri)
        advanceUntilIdle()

        assertThat(events).containsExactly(BackupEvent.ChooseImportOptions(uri, preview, ImportOptionsUiState.from(preview)))
        collectorJob.cancel()
    }

    @Test
    fun a_provider_that_hangs_ends_in_the_failure_message_at_the_timeout() = runTest(mainDispatcherRule.testDispatcher) {
        // The fake ignores cancellation, like a provider blocked in read(): the message must come
        // at the timeout, not when the read finally ends ten timeouts later (2b-3b-c).
        val events = mutableListOf<BackupEvent>()
        val collectorJob = recordEmissions(viewModel.event, events)
        repository.previewHangs = true

        viewModel.previewForImport(uri)
        advanceTimeBy(AppConstants.BACKUP_PREVIEW_TIMEOUT_MS - 1)
        assertThat(events).isEmpty()
        advanceTimeBy(2)

        assertThat(events).containsExactly(BackupEvent.Show(BackupMessage.ImportFailed))
        advanceUntilIdle() // let the abandoned read run out
        assertThat(events).hasSize(1)
        collectorJob.cancel()
    }

    @Test
    fun a_successful_import_reports_and_closes_the_settings() = runTest(mainDispatcherRule.testDispatcher) {
        val events = mutableListOf<BackupEvent>()
        val collectorJob = recordEmissions(viewModel.event, events)
        repository.importResult = ImportResult.Success(droppedWallpaperLayers = 1)

        viewModel.import(uri, ImportOptions())
        advanceUntilIdle()

        assertThat(events).containsExactly(BackupEvent.Show(BackupMessage.ImportDone(1)), BackupEvent.CloseSettings).inOrder()
        assertThat(repository.lastOptions).isEqualTo(ImportOptions())
        collectorJob.cancel()
    }

    @Test
    fun nothing_selected_reads_nothing() = runTest(mainDispatcherRule.testDispatcher) {
        val events = mutableListOf<BackupEvent>()
        val collectorJob = recordEmissions(viewModel.event, events)

        viewModel.import(uri, ImportOptions(importLayout = false, importHiddenApps = false, importSettings = false, importWallpaper = false))
        advanceUntilIdle()

        assertThat(events).containsExactly(BackupEvent.Show(BackupMessage.NothingSelected))
        assertThat(repository.lastImportUri).isNull()
        collectorJob.cancel()
    }

    @Test
    fun export_reports_its_outcome() = runTest(mainDispatcherRule.testDispatcher) {
        val events = mutableListOf<BackupEvent>()
        val collectorJob = recordEmissions(viewModel.event, events)

        viewModel.export(uri)
        advanceUntilIdle()
        repository.exportSuccess = false
        viewModel.export(uri)
        advanceUntilIdle()

        assertThat(events).containsExactly(
            BackupEvent.Show(BackupMessage.ExportDone),
            BackupEvent.Show(BackupMessage.ExportFailed),
        ).inOrder()
        collectorJob.cancel()
    }
}
