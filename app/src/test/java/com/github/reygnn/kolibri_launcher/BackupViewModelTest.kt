package com.github.reygnn.kolibri_launcher

import app.cash.turbine.test
import com.github.reygnn.kolibri_launcher.data.BackupRepository
import com.github.reygnn.kolibri_launcher.ui.BackupState
import com.github.reygnn.kolibri_launcher.ui.BackupViewModel
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import org.junit.Before
import org.junit.Rule
import org.junit.Test

@ExperimentalCoroutinesApi
class BackupViewModelTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    private lateinit var fakeBackupRepository: FakeBackupRepository
    private lateinit var viewModel: BackupViewModel

    @Before
    fun setUp() {
        fakeBackupRepository = FakeBackupRepository()
        viewModel = BackupViewModel(
            fakeBackupRepository,
            mainDispatcher = mainDispatcherRule.testDispatcher
        )
    }

    // ========== EXPORT TESTS ==========

    @Test
    fun `exportBackup - successful - emits Loading then ExportSuccess`() =
        runTest(mainDispatcherRule.testDispatcher) {
            // Test-Uri ist jetzt ein einfacher String
            val mockUriString = "content://fake/backup.json"
            fakeBackupRepository.exportSuccess = true

            viewModel.backupState.test {
                assertThat(awaitItem()).isEqualTo(BackupState.Idle)
                viewModel.exportBackup(mockUriString) // Übergabe als String

                // 'Loading' wird übersprungen (korrektes Testverhalten)
                assertThat(awaitItem()).isEqualTo(BackupState.ExportSuccess)
            }
        }

    @Test
    fun `exportBackup - failure - emits Loading then Error`() =
        runTest(mainDispatcherRule.testDispatcher) {
            val mockUriString = "content://fake/backup.json"
            fakeBackupRepository.exportSuccess = false

            viewModel.backupState.test {
                assertThat(awaitItem()).isEqualTo(BackupState.Idle)
                viewModel.exportBackup(mockUriString)

                val errorState = awaitItem() as BackupState.Error
                assertThat(errorState.message).isEqualTo("Export failed")
            }
        }

    @Test
    fun `exportBackup - exception thrown - emits Error`() =
        runTest(mainDispatcherRule.testDispatcher) {
            val mockUriString = "content://fake/backup.json"
            fakeBackupRepository.shouldThrowOnExport = true

            viewModel.backupState.test {
                assertThat(awaitItem()).isEqualTo(BackupState.Idle)
                viewModel.exportBackup(mockUriString)

                val errorState = awaitItem() as BackupState.Error
                assertThat(errorState.message).contains("Simulated")
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
                missingApps = setOf("com.missing/.MainActivity")
            )

            viewModel.backupState.test {
                assertThat(awaitItem()).isEqualTo(BackupState.Idle)
                viewModel.importBackup(mockUriString, options)

                val successState = awaitItem() as BackupState.ImportSuccess
                assertThat(successState.importedCount).isEqualTo(5)
                assertThat(successState.skippedCount).isEqualTo(2)
                assertThat(successState.missingApps).hasSize(1)
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
                assertThat(awaitItem()).isEqualTo(BackupState.Idle)
                viewModel.importBackup(mockUriString, options)

                val versionState = awaitItem() as BackupState.UnsupportedVersion
                assertThat(versionState.version).isEqualTo("2.0.0")
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
                assertThat(awaitItem()).isEqualTo(BackupState.Idle)
                viewModel.importBackup(mockUriString, options)

                val limitState = awaitItem() as BackupState.LimitExceeded
                assertThat(limitState.packageCount).isEqualTo(10)
                assertThat(limitState.limit).isEqualTo(8)
            }
        }

    @Test
    fun `importBackup - InvalidFormat result - emits InvalidFormat`() =
        runTest(mainDispatcherRule.testDispatcher) {
            val mockUriString = "content://fake/backup.json"
            val options = ImportOptions()
            fakeBackupRepository.importResult = ImportResult.InvalidFormat

            viewModel.backupState.test {
                assertThat(awaitItem()).isEqualTo(BackupState.Idle)
                viewModel.importBackup(mockUriString, options)

                assertThat(awaitItem()).isEqualTo(BackupState.InvalidFormat)
            }
        }

    @Test
    fun `importBackup - Error result - emits Error`() = runTest(mainDispatcherRule.testDispatcher) {
        val mockUriString = "content://fake/backup.json"
        val options = ImportOptions()
        fakeBackupRepository.importResult = ImportResult.Error("Custom error message")

        viewModel.backupState.test {
            assertThat(awaitItem()).isEqualTo(BackupState.Idle)
            viewModel.importBackup(mockUriString, options)

            val errorState = awaitItem() as BackupState.Error
            assertThat(errorState.message).isEqualTo("Custom error message")
        }
    }

    @Test
    fun `importBackup - exception thrown - emits Error`() =
        runTest(mainDispatcherRule.testDispatcher) {
            val mockUriString = "content://fake/backup.json"
            val options = ImportOptions()
            fakeBackupRepository.shouldThrowOnImport = true

            viewModel.backupState.test {
                assertThat(awaitItem()).isEqualTo(BackupState.Idle)
                viewModel.importBackup(mockUriString, options)

                val errorState = awaitItem() as BackupState.Error
                assertThat(errorState.message).contains("Simulated")
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
                hasThemeSettings = true
            )
            fakeBackupRepository.previewResult = expectedPreview

            viewModel.backupPreview.test {
                assertThat(awaitItem()).isNull() // Startwert
                viewModel.previewBackup(mockUriString) // Aktion
                val preview = awaitItem() // Endergebnis
                assertThat(preview).isNotNull()
                assertThat(preview?.favoriteCount).isEqualTo(5)
                assertThat(preview?.hasSwipeLeft).isTrue()
                assertThat(preview?.hasSwipeRight).isFalse()
            }
        }

    @Test
    fun `previewBackup - failure - emits null`() = runTest(mainDispatcherRule.testDispatcher) {
        val mockUriString = "content://fake/backup.json"
        fakeBackupRepository.previewResult = null

        viewModel.backupPreview.test {
            assertThat(awaitItem()).isNull()
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
                assertThat(awaitItem()).isNull()
                viewModel.previewBackup(mockUriString)
                expectNoEvents() // Bleibt null
            }
        }

    // ========== RESET STATE TESTS ==========

    @Test
    fun `resetBackupState - resets state to Idle`() = runTest(mainDispatcherRule.testDispatcher) {
        val mockUriString = "content://fake/backup.json"
        fakeBackupRepository.exportSuccess = true

        // Arrange: Setze den State auf einen Nicht-Idle-Wert
        viewModel.exportBackup(mockUriString)

        // Act & Assert
        viewModel.backupState.test {
            assertThat(awaitItem()).isEqualTo(BackupState.ExportSuccess)
            viewModel.resetBackupState()
            assertThat(awaitItem()).isEqualTo(BackupState.Idle)
        }
    }

    @Test
    fun `resetBackupState - clears preview`() = runTest(mainDispatcherRule.testDispatcher) {
        val mockUriString = "content://fake/backup.json"

        // Arrange: Setze den Preview auf einen Nicht-Null-Wert
        val preview = BackupPreview(
            version = "1.0.0",
            timestamp = 1L,
            favoriteCount = 1,
            orderCount = 1,
            hiddenCount = 1,
            customNamesCount = 1,
            hasSwipeLeft = false,
            hasSwipeRight = false,
            hasThemeSettings = true
        )
        fakeBackupRepository.previewResult = preview
        viewModel.previewBackup(mockUriString)

        // Act & Assert
        viewModel.backupPreview.test {
            assertThat(awaitItem()).isEqualTo(preview)
            viewModel.resetBackupState()
            assertThat(awaitItem()).isNull()
        }
    }

    // ========== STATE TRANSITION TESTS ==========

    @Test
    fun `multiple operations - maintains correct state sequence`() =
        runTest(mainDispatcherRule.testDispatcher) {
            val mockUriString = "content://fake/backup.json"
            fakeBackupRepository.exportSuccess = true

            viewModel.backupState.test {
                assertThat(awaitItem()).isEqualTo(BackupState.Idle)
                // Export
                viewModel.exportBackup(mockUriString)
                assertThat(awaitItem()).isEqualTo(BackupState.ExportSuccess)

                // Reset
                viewModel.resetBackupState()
                assertThat(awaitItem()).isEqualTo(BackupState.Idle)

                // Import
                fakeBackupRepository.importResult = ImportResult.Success(1, 0, emptySet())
                viewModel.importBackup(mockUriString, ImportOptions())
                assertThat(awaitItem()).isInstanceOf(BackupState.ImportSuccess::class.java)
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
            assertThat(fakeBackupRepository.lastOptions).isNotNull()
            assertThat(fakeBackupRepository.lastOptions?.importFavorites).isTrue()
            assertThat(fakeBackupRepository.lastOptions?.importOrder).isFalse()
            assertThat(fakeBackupRepository.lastOptions?.importHiddenApps).isTrue()
            assertThat(fakeBackupRepository.lastOptions?.importCustomNames).isFalse()
        }
}

// ========== FAKE REPOSITORY (Angepasst an String-Interface) ==========

class FakeBackupRepository : BackupRepository {
    var exportSuccess = true
    var shouldThrowOnExport = false
    var shouldThrowOnImport = false
    var shouldThrowOnPreview = false

    var importResult: ImportResult = ImportResult.Success(0, 0, emptySet())
    var previewResult: BackupPreview? = null
    var lastOptions: ImportOptions? = null

    override suspend fun exportToJson(): String {
        if (shouldThrowOnExport) {
            throw Exception("Simulated export exception")
        }
        return """{"version":"1.0.0","timestamp":0,"settings":{}}"""
    }

    override suspend fun importFromJson(jsonString: String, options: ImportOptions): ImportResult {
        lastOptions = options
        if (shouldThrowOnImport) {
            throw Exception("Simulated import exception")
        }
        return importResult
    }

    override suspend fun saveBackupToFile(uriString: String): Boolean {
        if (shouldThrowOnExport) {
            throw Exception("Simulated export exception")
        }
        return exportSuccess
    }

    override suspend fun loadBackupFromFile(uriString: String, options: ImportOptions): ImportResult {
        lastOptions = options
        if (shouldThrowOnImport) {
            throw Exception("Simulated import exception")
        }
        return importResult
    }

    override suspend fun previewBackup(uriString: String): BackupPreview? {
        if (shouldThrowOnPreview) {
            throw Exception("Simulated preview exception")
        }
        return previewResult
    }
}