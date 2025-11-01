package com.github.reygnn.kolibri_launcher

import android.net.Uri
import app.cash.turbine.test
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test

@ExperimentalCoroutinesApi
class BackupViewModelTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    private val testDispatcher = StandardTestDispatcher()
    private lateinit var fakeBackupRepository: FakeBackupRepository
    private lateinit var viewModel: BackupViewModel

    @Before
    fun setUp() {
        Dispatchers.setMain(testDispatcher)
        fakeBackupRepository = FakeBackupRepository()
        viewModel = BackupViewModel(
            fakeBackupRepository,
            mainDispatcher = mainDispatcherRule.testDispatcher
        )
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    // ========== EXPORT TESTS ==========

    @Test
    fun `exportBackup - successful - emits Loading then ExportSuccess`() = runTest {
        val mockUri = Uri.parse("content://fake/backup.json")
        fakeBackupRepository.exportSuccess = true

        viewModel.backupState.test {
            assertThat(awaitItem()).isEqualTo(BackupState.Idle)

            viewModel.exportBackup(mockUri)

            assertThat(awaitItem()).isEqualTo(BackupState.Loading)
            assertThat(awaitItem()).isEqualTo(BackupState.ExportSuccess)
        }
    }

    @Test
    fun `exportBackup - failure - emits Loading then Error`() = runTest {
        val mockUri = Uri.parse("content://fake/backup.json")
        fakeBackupRepository.exportSuccess = false

        viewModel.backupState.test {
            assertThat(awaitItem()).isEqualTo(BackupState.Idle)

            viewModel.exportBackup(mockUri)

            assertThat(awaitItem()).isEqualTo(BackupState.Loading)
            val errorState = awaitItem() as BackupState.Error
            assertThat(errorState.message).isEqualTo("Export failed")
        }
    }

    @Test
    fun `exportBackup - exception thrown - emits Error`() = runTest {
        val mockUri = Uri.parse("content://fake/backup.json")
        fakeBackupRepository.shouldThrowOnExport = true

        viewModel.backupState.test {
            assertThat(awaitItem()).isEqualTo(BackupState.Idle)

            viewModel.exportBackup(mockUri)

            assertThat(awaitItem()).isEqualTo(BackupState.Loading)
            val errorState = awaitItem() as BackupState.Error
            assertThat(errorState.message).contains("Simulated")
        }
    }

    // ========== IMPORT TESTS - SUCCESS ==========

    @Test
    fun `importBackup - Success result - emits ImportSuccess`() = runTest {
        val mockUri = Uri.parse("content://fake/backup.json")
        val options = ImportOptions()
        fakeBackupRepository.importResult = ImportResult.Success(
            importedCount = 5,
            skippedCount = 2,
            missingApps = setOf("com.missing/.MainActivity")
        )

        viewModel.backupState.test {
            assertThat(awaitItem()).isEqualTo(BackupState.Idle)

            viewModel.importBackup(mockUri, options)

            assertThat(awaitItem()).isEqualTo(BackupState.Loading)
            val successState = awaitItem() as BackupState.ImportSuccess
            assertThat(successState.importedCount).isEqualTo(5)
            assertThat(successState.skippedCount).isEqualTo(2)
            assertThat(successState.missingApps).hasSize(1)
        }
    }

    // ========== IMPORT TESTS - ERRORS ==========

    @Test
    fun `importBackup - UnsupportedVersion result - emits UnsupportedVersion`() = runTest {
        val mockUri = Uri.parse("content://fake/backup.json")
        val options = ImportOptions()
        fakeBackupRepository.importResult = ImportResult.UnsupportedVersion("2.0.0")

        viewModel.backupState.test {
            assertThat(awaitItem()).isEqualTo(BackupState.Idle)

            viewModel.importBackup(mockUri, options)

            assertThat(awaitItem()).isEqualTo(BackupState.Loading)
            val versionState = awaitItem() as BackupState.UnsupportedVersion
            assertThat(versionState.version).isEqualTo("2.0.0")
        }
    }

    @Test
    fun `importBackup - LimitExceeded result - emits LimitExceeded`() = runTest {
        val mockUri = Uri.parse("content://fake/backup.json")
        val options = ImportOptions()
        fakeBackupRepository.importResult = ImportResult.LimitExceeded(
            packageCount = 10,
            limit = 8
        )

        viewModel.backupState.test {
            assertThat(awaitItem()).isEqualTo(BackupState.Idle)

            viewModel.importBackup(mockUri, options)

            assertThat(awaitItem()).isEqualTo(BackupState.Loading)
            val limitState = awaitItem() as BackupState.LimitExceeded
            assertThat(limitState.packageCount).isEqualTo(10)
            assertThat(limitState.limit).isEqualTo(8)
        }
    }

    @Test
    fun `importBackup - InvalidFormat result - emits InvalidFormat`() = runTest {
        val mockUri = Uri.parse("content://fake/backup.json")
        val options = ImportOptions()
        fakeBackupRepository.importResult = ImportResult.InvalidFormat

        viewModel.backupState.test {
            assertThat(awaitItem()).isEqualTo(BackupState.Idle)

            viewModel.importBackup(mockUri, options)

            assertThat(awaitItem()).isEqualTo(BackupState.Loading)
            assertThat(awaitItem()).isEqualTo(BackupState.InvalidFormat)
        }
    }

    @Test
    fun `importBackup - Error result - emits Error`() = runTest {
        val mockUri = Uri.parse("content://fake/backup.json")
        val options = ImportOptions()
        fakeBackupRepository.importResult = ImportResult.Error("Custom error message")

        viewModel.backupState.test {
            assertThat(awaitItem()).isEqualTo(BackupState.Idle)

            viewModel.importBackup(mockUri, options)

            assertThat(awaitItem()).isEqualTo(BackupState.Loading)
            val errorState = awaitItem() as BackupState.Error
            assertThat(errorState.message).isEqualTo("Custom error message")
        }
    }

    @Test
    fun `importBackup - exception thrown - emits Error`() = runTest {
        val mockUri = Uri.parse("content://fake/backup.json")
        val options = ImportOptions()
        fakeBackupRepository.shouldThrowOnImport = true

        viewModel.backupState.test {
            assertThat(awaitItem()).isEqualTo(BackupState.Idle)

            viewModel.importBackup(mockUri, options)

            assertThat(awaitItem()).isEqualTo(BackupState.Loading)
            val errorState = awaitItem() as BackupState.Error
            assertThat(errorState.message).contains("Simulated")
        }
    }

    // ========== PREVIEW TESTS ==========

    @Test
    fun `previewBackup - successful - emits preview data`() = runTest {
        val mockUri = Uri.parse("content://fake/backup.json")
        val expectedPreview = BackupPreview(
            version = "1.0.0",
            timestamp = 1234567890L,
            favoriteCount = 5,
            orderCount = 5,
            hiddenCount = 2,
            customNamesCount = 3
        )
        fakeBackupRepository.previewResult = expectedPreview

        viewModel.backupPreview.test {
            assertThat(awaitItem()).isNull()

            viewModel.previewBackup(mockUri)

            val preview = awaitItem()
            assertThat(preview).isNotNull()
            assertThat(preview?.favoriteCount).isEqualTo(5)
            assertThat(preview?.hiddenCount).isEqualTo(2)
            assertThat(preview?.customNamesCount).isEqualTo(3)
        }
    }

    @Test
    fun `previewBackup - failure - emits null`() = runTest {
        val mockUri = Uri.parse("content://fake/backup.json")
        fakeBackupRepository.previewResult = null

        viewModel.backupPreview.test {
            assertThat(awaitItem()).isNull()

            viewModel.previewBackup(mockUri)

            expectNoEvents() // Should stay null
        }
    }

    @Test
    fun `previewBackup - exception thrown - emits null`() = runTest {
        val mockUri = Uri.parse("content://fake/backup.json")
        fakeBackupRepository.shouldThrowOnPreview = true

        viewModel.backupPreview.test {
            assertThat(awaitItem()).isNull()

            viewModel.previewBackup(mockUri)

            expectNoEvents() // Should stay null on error
        }
    }

    // ========== RESET STATE TESTS ==========

    @Test
    fun `resetBackupState - resets to Idle and clears preview`() = runTest {
        val mockUri = Uri.parse("content://fake/backup.json")
        fakeBackupRepository.exportSuccess = true

        // First trigger an export to change state
        viewModel.exportBackup(mockUri)
        testDispatcher.scheduler.advanceUntilIdle()

        viewModel.backupState.test {
            // Should be ExportSuccess now
            assertThat(awaitItem()).isEqualTo(BackupState.ExportSuccess)

            viewModel.resetBackupState()

            assertThat(awaitItem()).isEqualTo(BackupState.Idle)
        }

        viewModel.backupPreview.test {
            assertThat(awaitItem()).isNull()
        }
    }

    // ========== STATE TRANSITION TESTS ==========

    @Test
    fun `multiple operations - maintains correct state sequence`() = runTest {
        val mockUri = Uri.parse("content://fake/backup.json")
        fakeBackupRepository.exportSuccess = true

        viewModel.backupState.test {
            assertThat(awaitItem()).isEqualTo(BackupState.Idle)

            // Export
            viewModel.exportBackup(mockUri)
            assertThat(awaitItem()).isEqualTo(BackupState.Loading)
            assertThat(awaitItem()).isEqualTo(BackupState.ExportSuccess)

            // Reset
            viewModel.resetBackupState()
            assertThat(awaitItem()).isEqualTo(BackupState.Idle)

            // Import
            fakeBackupRepository.importResult = ImportResult.Success(1, 0, emptySet())
            viewModel.importBackup(mockUri, ImportOptions())
            assertThat(awaitItem()).isEqualTo(BackupState.Loading)
            assertThat(awaitItem()).isInstanceOf(BackupState.ImportSuccess::class.java)
        }
    }

    @Test
    fun `selective import options - passes options correctly`() = runTest {
        val mockUri = Uri.parse("content://fake/backup.json")
        val options = ImportOptions(
            importFavorites = true,
            importOrder = false,
            importHiddenApps = true,
            importCustomNames = false
        )
        fakeBackupRepository.importResult = ImportResult.Success(1, 0, emptySet())

        viewModel.importBackup(mockUri, options)
        testDispatcher.scheduler.advanceUntilIdle()

        // Verify options were passed to repository
        assertThat(fakeBackupRepository.lastOptions).isNotNull()
        assertThat(fakeBackupRepository.lastOptions?.importFavorites).isTrue()
        assertThat(fakeBackupRepository.lastOptions?.importOrder).isFalse()
        assertThat(fakeBackupRepository.lastOptions?.importHiddenApps).isTrue()
        assertThat(fakeBackupRepository.lastOptions?.importCustomNames).isFalse()
    }
}

// ========== FAKE REPOSITORY ==========

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

    override suspend fun saveBackupToFile(uri: Uri): Boolean {
        if (shouldThrowOnExport) {
            throw Exception("Simulated export exception")
        }
        return exportSuccess
    }

    override suspend fun loadBackupFromFile(uri: Uri, options: ImportOptions): ImportResult {
        lastOptions = options
        if (shouldThrowOnImport) {
            throw Exception("Simulated import exception")
        }
        return importResult
    }

    override suspend fun previewBackup(uri: Uri): BackupPreview? {
        if (shouldThrowOnPreview) {
            throw Exception("Simulated preview exception")
        }
        return previewResult
    }
}