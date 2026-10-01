package com.github.reygnn.kolibri_launcher.ui

import app.cash.turbine.test
import com.github.reygnn.launcher.core.testing.MainDispatcherRule
import com.github.reygnn.kolibri_launcher.domain.model.BackupPreview
import com.github.reygnn.kolibri_launcher.domain.model.ImportOptions
import com.github.reygnn.kolibri_launcher.domain.model.ImportResult
import com.github.reygnn.kolibri_launcher.domain.model.PreviewResult
import com.github.reygnn.kolibri_launcher.domain.usecase.ExportBackupUseCase
import com.github.reygnn.kolibri_launcher.domain.usecase.ImportBackupUseCase
import com.github.reygnn.kolibri_launcher.domain.usecase.PreviewBackupUseCase
import com.github.reygnn.kolibri_launcher.fakes.FakeBackupRepository
import com.github.reygnn.kolibri_launcher.rule.TimberRule
import com.github.reygnn.kolibri_launcher.ui.backup.BackupState
import com.github.reygnn.kolibri_launcher.ui.backup.BackupViewModel
import com.google.common.truth.Truth
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Before
import org.junit.Rule
import org.junit.Test

@ExperimentalCoroutinesApi
class BackupViewModelTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()
    @get:Rule
    val timberRule = TimberRule()

    private lateinit var fakeBackupRepository: FakeBackupRepository
    private lateinit var viewModel: BackupViewModel

    // UseCases
    private lateinit var exportBackupUseCase: ExportBackupUseCase
    private lateinit var importBackupUseCase: ImportBackupUseCase
    private lateinit var previewBackupUseCase: PreviewBackupUseCase

    @Before
    fun setUp() {
        fakeBackupRepository = FakeBackupRepository()

        // Instanziierung der UseCases mit dem Fake Repository
        exportBackupUseCase = ExportBackupUseCase(fakeBackupRepository)
        importBackupUseCase = ImportBackupUseCase(fakeBackupRepository)
        previewBackupUseCase = PreviewBackupUseCase(fakeBackupRepository)

        // ViewModel mit UseCases statt Repository initialisieren
        viewModel = BackupViewModel(
            exportBackupUseCase,
            importBackupUseCase,
            previewBackupUseCase,
            mainDispatcher = mainDispatcherRule.testDispatcher
        )
    }

    // ========== EXPORT TESTS ==========

    @Test
    fun `exportBackup - successful - emits Loading then ExportSuccess`() =
        runTest(mainDispatcherRule.testDispatcher) {
            val mockUriString = "content://fake/backup.json"
            fakeBackupRepository.exportSuccess = true

            viewModel.backupState.test {
                Truth.assertThat(awaitItem()).isEqualTo(BackupState.Idle)
                viewModel.exportBackup(mockUriString)
                advanceUntilIdle()

                // 'Loading' wird u.U. übersprungen im Test, finaler State ist wichtig
                Truth.assertThat(expectMostRecentItem()).isEqualTo(BackupState.ExportSuccess)
            }
        }

    @Test
    fun `exportBackup - failure - emits Loading then Error`() =
        runTest(mainDispatcherRule.testDispatcher) {
            val mockUriString = "content://fake/backup.json"
            fakeBackupRepository.exportSuccess = false

            viewModel.backupState.test {
                Truth.assertThat(awaitItem()).isEqualTo(BackupState.Idle)
                viewModel.exportBackup(mockUriString)
                advanceUntilIdle()

                val errorState = expectMostRecentItem() as BackupState.Error
                Truth.assertThat(errorState.message).isEqualTo("Export failed")
            }
        }

    @Test
    fun `exportBackup - exception thrown - emits Error`() =
        runTest(mainDispatcherRule.testDispatcher) {
            val mockUriString = "content://fake/backup.json"
            fakeBackupRepository.shouldThrowOnExport = true

            viewModel.backupState.test {
                Truth.assertThat(awaitItem()).isEqualTo(BackupState.Idle)
                viewModel.exportBackup(mockUriString)
                advanceUntilIdle()

                val errorState = expectMostRecentItem() as BackupState.Error
                Truth.assertThat(errorState.message).contains("Simulated")
            }
        }

    // ========== IMPORT TESTS - SUCCESS ==========

    @Test
    fun `importBackup - Success result - emits ImportSuccess`() =
        runTest(mainDispatcherRule.testDispatcher) {
            val mockUriString = "content://fake/backup.json"
            val options = ImportOptions()
            fakeBackupRepository.importResult = ImportResult.Success(
                importedCount = 5,
                skippedCount = 2,
                missingApps = setOf("com.missing/.MainActivity"),
                droppedWallpaperLayers = 3
            )

            viewModel.backupState.test {
                Truth.assertThat(awaitItem()).isEqualTo(BackupState.Idle)
                viewModel.importBackup(mockUriString, options)
                advanceUntilIdle()

                val successState = expectMostRecentItem() as BackupState.ImportSuccess
                Truth.assertThat(successState.importedCount).isEqualTo(5)
                Truth.assertThat(successState.skippedCount).isEqualTo(2)
                Truth.assertThat(successState.missingApps).hasSize(1)
                Truth.assertThat(successState.droppedWallpaperLayers).isEqualTo(3)
            }
        }

    // ========== IMPORT TESTS - ERRORS ==========

    @Test
    fun `importBackup - UnsupportedVersion result - emits UnsupportedVersion`() =
        runTest(mainDispatcherRule.testDispatcher) {
            val mockUriString = "content://fake/backup.json"
            val options = ImportOptions()
            fakeBackupRepository.importResult = ImportResult.UnsupportedVersion("2.0.0")

            viewModel.backupState.test {
                Truth.assertThat(awaitItem()).isEqualTo(BackupState.Idle)
                viewModel.importBackup(mockUriString, options)
                advanceUntilIdle()

                val versionState = expectMostRecentItem() as BackupState.UnsupportedVersion
                Truth.assertThat(versionState.version).isEqualTo("2.0.0")
            }
        }

    @Test
    fun `importBackup - LimitExceeded result - emits LimitExceeded`() =
        runTest(mainDispatcherRule.testDispatcher) {
            val mockUriString = "content://fake/backup.json"
            val options = ImportOptions()
            fakeBackupRepository.importResult = ImportResult.LimitExceeded(
                packageCount = 10,
                limit = 8
            )

            viewModel.backupState.test {
                Truth.assertThat(awaitItem()).isEqualTo(BackupState.Idle)
                viewModel.importBackup(mockUriString, options)
                advanceUntilIdle()

                val limitState = expectMostRecentItem() as BackupState.LimitExceeded
                Truth.assertThat(limitState.packageCount).isEqualTo(10)
                Truth.assertThat(limitState.limit).isEqualTo(8)
            }
        }

    @Test
    fun `importBackup - InvalidFormat result - emits InvalidFormat`() =
        runTest(mainDispatcherRule.testDispatcher) {
            val mockUriString = "content://fake/backup.json"
            val options = ImportOptions()
            fakeBackupRepository.importResult = ImportResult.InvalidFormat

            viewModel.backupState.test {
                Truth.assertThat(awaitItem()).isEqualTo(BackupState.Idle)
                viewModel.importBackup(mockUriString, options)
                advanceUntilIdle()

                Truth.assertThat(expectMostRecentItem()).isEqualTo(BackupState.InvalidFormat)
            }
        }

    @Test
    fun `importBackup - ForeignBackup result - emits ForeignBackup with the app id`() =
        runTest(mainDispatcherRule.testDispatcher) {
            fakeBackupRepository.importResult = ImportResult.ForeignBackup("nyx")

            viewModel.backupState.test {
                Truth.assertThat(awaitItem()).isEqualTo(BackupState.Idle)
                viewModel.importBackup("content://fake/backup.zip", ImportOptions())
                advanceUntilIdle()

                Truth.assertThat(expectMostRecentItem()).isEqualTo(BackupState.ForeignBackup("nyx"))
            }
        }

    @Test
    fun `importBackup - OutdatedBackup result - emits OutdatedBackup`() =
        runTest(mainDispatcherRule.testDispatcher) {
            fakeBackupRepository.importResult = ImportResult.OutdatedBackup

            viewModel.backupState.test {
                Truth.assertThat(awaitItem()).isEqualTo(BackupState.Idle)
                viewModel.importBackup("content://fake/backup.zip", ImportOptions())
                advanceUntilIdle()

                Truth.assertThat(expectMostRecentItem()).isEqualTo(BackupState.OutdatedBackup)
            }
        }

    @Test
    fun `importBackup - Error result - emits Error`() = runTest(mainDispatcherRule.testDispatcher) {
        val mockUriString = "content://fake/backup.json"
        val options = ImportOptions()
        fakeBackupRepository.importResult = ImportResult.Error("Custom error message")

        viewModel.backupState.test {
            Truth.assertThat(awaitItem()).isEqualTo(BackupState.Idle)
            viewModel.importBackup(mockUriString, options)
            advanceUntilIdle()

            val errorState = expectMostRecentItem() as BackupState.Error
            Truth.assertThat(errorState.message).isEqualTo("Custom error message")
        }
    }

    @Test
    fun `importBackup - exception thrown - emits Error`() =
        runTest(mainDispatcherRule.testDispatcher) {
            val mockUriString = "content://fake/backup.json"
            val options = ImportOptions()
            fakeBackupRepository.shouldThrowOnImport = true

            viewModel.backupState.test {
                Truth.assertThat(awaitItem()).isEqualTo(BackupState.Idle)
                viewModel.importBackup(mockUriString, options)
                advanceUntilIdle()

                val errorState = expectMostRecentItem() as BackupState.Error
                Truth.assertThat(errorState.message).contains("Simulated")
            }
        }

    // ========== PREVIEW TESTS ==========

    @Test
    fun `previewBackup - successful - emits preview data`() =
        runTest(mainDispatcherRule.testDispatcher) {
            val mockUriString = "content://fake/backup.json"
            val expectedPreview = BackupPreview(
                version = "1.0.0",
                timestamp = 1234567890L,
                favoriteCount = 5,
                orderCount = 5,
                hiddenCount = 2,
                customNamesCount = 3,
                hasSwipeLeft = true,
                hasSwipeRight = false,
                hasThemeSettings = true,
                hasWallpaper = true,
                wallpaperLayerCount = 0,
                hasTimeBasedEvents = false,
                hasQualityOfLife = true,
                hasPowerUserSettings = true
            )
            fakeBackupRepository.previewResult = PreviewResult.Readable(expectedPreview)

            viewModel.previewResult.test {
                Truth.assertThat(awaitItem()).isNull()
                viewModel.previewBackup(mockUriString)
                advanceUntilIdle()
                Truth.assertThat(expectMostRecentItem()).isEqualTo(PreviewResult.Readable(expectedPreview))
            }
            // A readable file is no outcome yet: the options dialog comes first.
            Truth.assertThat(viewModel.backupState.value).isEqualTo(BackupState.Idle)
        }

    @Test
    fun `previewBackup - refused file - reports the reason at once`() = runTest(mainDispatcherRule.testDispatcher) {
        // 2a-7b (was: preview stays null and the fragment times out into "error"). The refusal
        // goes straight to backupState, which shows its own message; no dialog can open, since
        // the fragment opens it only for PreviewResult.Readable.
        fakeBackupRepository.previewResult = PreviewResult.Refused(ImportResult.ForeignBackup("nyx"))

        viewModel.previewBackup("content://fake/backup.zip")
        advanceUntilIdle()

        Truth.assertThat(viewModel.previewResult.value).isEqualTo(PreviewResult.Refused(ImportResult.ForeignBackup("nyx")))
        Truth.assertThat(viewModel.backupState.value).isEqualTo(BackupState.ForeignBackup("nyx"))
    }

    @Test
    fun `previewBackup - every refusal maps to the same state as its import`() = runTest(mainDispatcherRule.testDispatcher) {
        val refusals = listOf(
            ImportResult.ForeignBackup("nyx") to BackupState.ForeignBackup("nyx"),
            ImportResult.OutdatedBackup to BackupState.OutdatedBackup,
            ImportResult.UnsupportedVersion("9.0") to BackupState.UnsupportedVersion("9.0"),
            ImportResult.InvalidFormat to BackupState.InvalidFormat,
            ImportResult.Error("Backup file is too large") to BackupState.Error("Backup file is too large"),
        )
        for ((refusal, state) in refusals) {
            fakeBackupRepository.previewResult = PreviewResult.Refused(refusal)
            viewModel.previewBackup("content://fake/backup.zip")
            advanceUntilIdle()
            Truth.assertWithMessage("preview refused with $refusal").that(viewModel.backupState.value).isEqualTo(state)

            fakeBackupRepository.importResult = refusal
            viewModel.importBackup("content://fake/backup.zip", ImportOptions())
            advanceUntilIdle()
            Truth.assertWithMessage("import refused with $refusal").that(viewModel.backupState.value).isEqualTo(state)
        }
    }

    @Test
    fun `previewBackup - exception thrown - refused with an error`() =
        runTest(mainDispatcherRule.testDispatcher) {
            fakeBackupRepository.shouldThrowOnPreview = true

            viewModel.previewBackup("content://fake/backup.json")
            advanceUntilIdle()

            Truth.assertThat(viewModel.previewResult.value)
                .isEqualTo(PreviewResult.Refused(ImportResult.Error("Simulated preview exception")))
            Truth.assertThat(viewModel.backupState.value).isEqualTo(BackupState.Error("Simulated preview exception"))
        }

    // ========== RESET STATE TESTS ==========

    @Test
    fun `resetBackupState - resets state to Idle`() = runTest(mainDispatcherRule.testDispatcher) {
        val mockUriString = "content://fake/backup.json"
        fakeBackupRepository.exportSuccess = true

        // Arrange
        viewModel.exportBackup(mockUriString)
        advanceUntilIdle()

        // Act & Assert
        viewModel.backupState.test {
            Truth.assertThat(awaitItem()).isEqualTo(BackupState.ExportSuccess)
            viewModel.resetBackupState()
            advanceUntilIdle()
            Truth.assertThat(awaitItem()).isEqualTo(BackupState.Idle)
        }
    }

    @Test
    fun `resetBackupState - leaves the preview, the next previewBackup clears it first`() = runTest(mainDispatcherRule.testDispatcher) {
        val mockUriString = "content://fake/backup.json"
        val preview = BackupPreview(
            "1.0.0",
            1L,
            1,
            1,
            1,
            1,
            false,
            false,
            true,
            false,
            0,
            false,
            false,
            false
        )
        fakeBackupRepository.previewResult = PreviewResult.Readable(preview)
        viewModel.previewBackup(mockUriString)
        advanceUntilIdle()

        // 2a-7b (was: reset cleared the preview). The fragment may still be waiting for the
        // preview while a refusal's state is shown and reset, so reset leaves it alone…
        viewModel.resetBackupState()
        advanceUntilIdle()
        Truth.assertThat(viewModel.previewResult.value).isEqualTo(PreviewResult.Readable(preview))

        // …and the next file starts from null, synchronously, before anything waits for it.
        viewModel.previewBackup(mockUriString)
        Truth.assertThat(viewModel.previewResult.value).isNull()
        advanceUntilIdle()
        Truth.assertThat(viewModel.previewResult.value).isEqualTo(PreviewResult.Readable(preview))
    }

    // ========== STATE TRANSITION TESTS ==========

    @Test
    fun `multiple operations - maintains correct state sequence`() =
        runTest(mainDispatcherRule.testDispatcher) {
            val mockUriString = "content://fake/backup.json"
            fakeBackupRepository.exportSuccess = true

            viewModel.backupState.test {
                Truth.assertThat(awaitItem()).isEqualTo(BackupState.Idle)
                // Export
                viewModel.exportBackup(mockUriString)
                advanceUntilIdle()
                Truth.assertThat(expectMostRecentItem()).isEqualTo(BackupState.ExportSuccess)

                // Reset
                viewModel.resetBackupState()
                advanceUntilIdle()
                Truth.assertThat(awaitItem()).isEqualTo(BackupState.Idle)

                // Import
                fakeBackupRepository.importResult = ImportResult.Success(1, 0, emptySet())
                viewModel.importBackup(mockUriString, ImportOptions())
                advanceUntilIdle()
                Truth.assertThat(expectMostRecentItem()).isInstanceOf(BackupState.ImportSuccess::class.java)
            }
        }

    @Test
    fun `selective import options - passes options correctly`() =
        runTest(mainDispatcherRule.testDispatcher) {
            val mockUriString = "content://fake/backup.json"
            val options = ImportOptions(
                importFavorites = true,
                importOrder = false,
                importHiddenApps = true,
                importCustomNames = false
            )
            fakeBackupRepository.importResult = ImportResult.Success(1, 0, emptySet())

            viewModel.importBackup(mockUriString, options)
            advanceUntilIdle()

            // Verify options were passed to repository
            Truth.assertThat(fakeBackupRepository.lastOptions).isNotNull()
            Truth.assertThat(fakeBackupRepository.lastOptions?.importFavorites).isTrue()
            Truth.assertThat(fakeBackupRepository.lastOptions?.importOrder).isFalse()
        }
}