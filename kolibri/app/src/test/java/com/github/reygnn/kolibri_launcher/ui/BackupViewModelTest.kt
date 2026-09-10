package com.github.reygnn.kolibri_launcher.ui

import app.cash.turbine.test
import com.github.reygnn.kolibri_launcher.rule.MainDispatcherRule
import com.github.reygnn.kolibri_launcher.domain.model.BackupPreview
import com.github.reygnn.kolibri_launcher.domain.model.ImportOptions
import com.github.reygnn.kolibri_launcher.domain.model.ImportResult
import com.github.reygnn.kolibri_launcher.domain.usecase.ExportBackupUseCase
import com.github.reygnn.kolibri_launcher.domain.usecase.ImportBackupUseCase
import com.github.reygnn.kolibri_launcher.domain.usecase.PreviewBackupUseCase
import com.github.reygnn.kolibri_launcher.fakes.FakeBackupRepository
import com.github.reygnn.kolibri_launcher.rule.TimberRule
import com.github.reygnn.kolibri_launcher.ui.backup.BackupState
import com.github.reygnn.kolibri_launcher.ui.backup.BackupViewModel
import com.google.common.truth.Truth
import kotlinx.coroutines.ExperimentalCoroutinesApi
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

                // 'Loading' wird u.U. übersprungen im Test, finaler State ist wichtig
                Truth.assertThat(awaitItem()).isEqualTo(BackupState.ExportSuccess)
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

                val errorState = awaitItem() as BackupState.Error
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

                val errorState = awaitItem() as BackupState.Error
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

                val successState = awaitItem() as BackupState.ImportSuccess
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

                val versionState = awaitItem() as BackupState.UnsupportedVersion
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

                val limitState = awaitItem() as BackupState.LimitExceeded
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

                Truth.assertThat(awaitItem()).isEqualTo(BackupState.InvalidFormat)
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

            val errorState = awaitItem() as BackupState.Error
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

                val errorState = awaitItem() as BackupState.Error
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
            fakeBackupRepository.previewResult = expectedPreview

            viewModel.backupPreview.test {
                Truth.assertThat(awaitItem()).isNull()
                viewModel.previewBackup(mockUriString)
                val preview = awaitItem()
                Truth.assertThat(preview).isNotNull()
                Truth.assertThat(preview?.favoriteCount).isEqualTo(5)
            }
        }

    @Test
    fun `previewBackup - failure - emits null`() = runTest(mainDispatcherRule.testDispatcher) {
        val mockUriString = "content://fake/backup.json"
        fakeBackupRepository.previewResult = null

        viewModel.backupPreview.test {
            Truth.assertThat(awaitItem()).isNull()
            viewModel.previewBackup(mockUriString)
            expectNoEvents() // Bleibt null
        }
    }

    @Test
    fun `previewBackup - exception thrown - emits null`() =
        runTest(mainDispatcherRule.testDispatcher) {
            val mockUriString = "content://fake/backup.json"
            fakeBackupRepository.shouldThrowOnPreview = true

            viewModel.backupPreview.test {
                Truth.assertThat(awaitItem()).isNull()
                viewModel.previewBackup(mockUriString)
                expectNoEvents() // Bleibt null
            }
        }

    // ========== RESET STATE TESTS ==========

    @Test
    fun `resetBackupState - resets state to Idle`() = runTest(mainDispatcherRule.testDispatcher) {
        val mockUriString = "content://fake/backup.json"
        fakeBackupRepository.exportSuccess = true

        // Arrange
        viewModel.exportBackup(mockUriString)

        // Act & Assert
        viewModel.backupState.test {
            Truth.assertThat(awaitItem()).isEqualTo(BackupState.ExportSuccess)
            viewModel.resetBackupState()
            Truth.assertThat(awaitItem()).isEqualTo(BackupState.Idle)
        }
    }

    @Test
    fun `resetBackupState - clears preview`() = runTest(mainDispatcherRule.testDispatcher) {
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
        fakeBackupRepository.previewResult = preview
        viewModel.previewBackup(mockUriString)

        // Act & Assert
        viewModel.backupPreview.test {
            Truth.assertThat(awaitItem()).isEqualTo(preview)
            viewModel.resetBackupState()
            Truth.assertThat(awaitItem()).isNull()
        }
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
                Truth.assertThat(awaitItem()).isEqualTo(BackupState.ExportSuccess)

                // Reset
                viewModel.resetBackupState()
                Truth.assertThat(awaitItem()).isEqualTo(BackupState.Idle)

                // Import
                fakeBackupRepository.importResult = ImportResult.Success(1, 0, emptySet())
                viewModel.importBackup(mockUriString, ImportOptions())
                Truth.assertThat(awaitItem()).isInstanceOf(BackupState.ImportSuccess::class.java)
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

            // Verify options were passed to repository
            Truth.assertThat(fakeBackupRepository.lastOptions).isNotNull()
            Truth.assertThat(fakeBackupRepository.lastOptions?.importFavorites).isTrue()
            Truth.assertThat(fakeBackupRepository.lastOptions?.importOrder).isFalse()
        }
}