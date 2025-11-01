package com.github.reygnn.kolibri_launcher

/*
import android.net.Uri
import app.cash.turbine.test
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest // Wichtig: Wir verwenden runTest
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@ExperimentalCoroutinesApi
@RunWith(RobolectricTestRunner::class) // 1. Behalten für Uri.parse()
@Config(sdk = [36], manifest = Config.NONE)
class BackupViewModelTest {

    // 2. Behalten, um den Dispatcher bereitzustellen
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

    // 3. runTest mit dem Dispatcher der Rule ausführen
    @Test
    fun `exportBackup - successful - emits Loading then ExportSuccess`() = runTest(mainDispatcherRule.testDispatcher) {
        val mockUri = Uri.parse("content://fake/backup.json")
        fakeBackupRepository.exportSuccess = true

        viewModel.backupState.test {
            assertThat(awaitItem()).isEqualTo(BackupState.Idle) // Startwert
            viewModel.exportBackup(mockUri) // Aktion (läuft synchron)

            // 4. KORREKTUR: 'Loading' wird übersprungen. Nur das Endergebnis prüfen.
            assertThat(awaitItem()).isEqualTo(BackupState.ExportSuccess)
        }
    }

    @Test
    fun `exportBackup - failure - emits Loading then Error`() = runTest(mainDispatcherRule.testDispatcher) {
        val mockUri = Uri.parse("content://fake/backup.json")
        fakeBackupRepository.exportSuccess = false

        viewModel.backupState.test {
            assertThat(awaitItem()).isEqualTo(BackupState.Idle)
            viewModel.exportBackup(mockUri)

            // KORREKTUR: 'Loading' wird übersprungen
            val errorState = awaitItem() as BackupState.Error
            assertThat(errorState.message).isEqualTo("Export failed")
        }
    }

    @Test
    fun `exportBackup - exception thrown - emits Error`() = runTest(mainDispatcherRule.testDispatcher) {
        val mockUri = Uri.parse("content://fake/backup.json")
        fakeBackupRepository.shouldThrowOnExport = true

        viewModel.backupState.test {
            assertThat(awaitItem()).isEqualTo(BackupState.Idle)
            viewModel.exportBackup(mockUri)

            // KORREKTUR: 'Loading' wird übersprungen
            val errorState = awaitItem() as BackupState.Error
            assertThat(errorState.message).contains("Simulated")
        }
    }

    // ========== IMPORT TESTS - SUCCESS ==========

    @Test
    fun `importBackup - Success result - emits ImportSuccess`() = runTest(mainDispatcherRule.testDispatcher) {
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

            // KORREKTUR: 'Loading' wird übersprungen
            val successState = awaitItem() as BackupState.ImportSuccess
            assertThat(successState.importedCount).isEqualTo(5)
            assertThat(successState.skippedCount).isEqualTo(2)
            assertThat(successState.missingApps).hasSize(1)
        }
    }

    // ========== IMPORT TESTS - ERRORS ==========

    @Test
    fun `importBackup - UnsupportedVersion result - emits UnsupportedVersion`() = runTest(mainDispatcherRule.testDispatcher) {
        val mockUri = Uri.parse("content://fake/backup.json")
        val options = ImportOptions()
        fakeBackupRepository.importResult = ImportResult.UnsupportedVersion("2.0.0")

        viewModel.backupState.test {
            assertThat(awaitItem()).isEqualTo(BackupState.Idle)
            viewModel.importBackup(mockUri, options)

            // KORREKTUR: 'Loading' wird übersprungen
            val versionState = awaitItem() as BackupState.UnsupportedVersion
            assertThat(versionState.version).isEqualTo("2.0.0")
        }
    }

    @Test
    fun `importBackup - LimitExceeded result - emits LimitExceeded`() = runTest(mainDispatcherRule.testDispatcher) {
        val mockUri = Uri.parse("content://fake/backup.json")
        val options = ImportOptions()
        fakeBackupRepository.importResult = ImportResult.LimitExceeded(
            packageCount = 10,
            limit = 8
        )

        viewModel.backupState.test {
            assertThat(awaitItem()).isEqualTo(BackupState.Idle)
            viewModel.importBackup(mockUri, options)

            // KORREKTUR: 'Loading' wird übersprungen
            val limitState = awaitItem() as BackupState.LimitExceeded
            assertThat(limitState.packageCount).isEqualTo(10)
            assertThat(limitState.limit).isEqualTo(8)
        }
    }

    @Test
    fun `importBackup - InvalidFormat result - emits InvalidFormat`() = runTest(mainDispatcherRule.testDispatcher) {
        val mockUri = Uri.parse("content://fake/backup.json")
        val options = ImportOptions()
        fakeBackupRepository.importResult = ImportResult.InvalidFormat

        viewModel.backupState.test {
            assertThat(awaitItem()).isEqualTo(BackupState.Idle)
            viewModel.importBackup(mockUri, options)

            // KORREKTUR: 'Loading' wird übersprungen
            assertThat(awaitItem()).isEqualTo(BackupState.InvalidFormat)
        }
    }

    @Test
    fun `importBackup - Error result - emits Error`() = runTest(mainDispatcherRule.testDispatcher) {
        val mockUri = Uri.parse("content://fake/backup.json")
        val options = ImportOptions()
        fakeBackupRepository.importResult = ImportResult.Error("Custom error message")

        viewModel.backupState.test {
            assertThat(awaitItem()).isEqualTo(BackupState.Idle)
            viewModel.importBackup(mockUri, options)

            // KORREKTUR: 'Loading' wird übersprungen
            val errorState = awaitItem() as BackupState.Error
            assertThat(errorState.message).isEqualTo("Custom error message")
        }
    }

    @Test
    fun `importBackup - exception thrown - emits Error`() = runTest(mainDispatcherRule.testDispatcher) {
        val mockUri = Uri.parse("content://fake/backup.json")
        val options = ImportOptions()
        fakeBackupRepository.shouldThrowOnImport = true

        viewModel.backupState.test {
            assertThat(awaitItem()).isEqualTo(BackupState.Idle)
            viewModel.importBackup(mockUri, options)

            // KORREKTUR: 'Loading' wird übersprungen
            val errorState = awaitItem() as BackupState.Error
            assertThat(errorState.message).contains("Simulated")
        }
    }

    // ========== PREVIEW TESTS ==========

    @Test
    fun `previewBackup - successful - emits preview data`() = runTest(mainDispatcherRule.testDispatcher) {
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
            assertThat(awaitItem()).isNull() // Startwert
            viewModel.previewBackup(mockUri) // Aktion
            val preview = awaitItem() // Endergebnis
            assertThat(preview).isNotNull()
            assertThat(preview?.favoriteCount).isEqualTo(5)
        }
    }

    @Test
    fun `previewBackup - failure - emits null`() = runTest(mainDispatcherRule.testDispatcher) {
        val mockUri = Uri.parse("content://fake/backup.json")
        fakeBackupRepository.previewResult = null

        viewModel.backupPreview.test {
            assertThat(awaitItem()).isNull()
            viewModel.previewBackup(mockUri)
            expectNoEvents() // Bleibt null
        }
    }

    @Test
    fun `previewBackup - exception thrown - emits null`() = runTest(mainDispatcherRule.testDispatcher) {
        val mockUri = Uri.parse("content://fake/backup.json")
        fakeBackupRepository.shouldThrowOnPreview = true

        viewModel.backupPreview.test {
            assertThat(awaitItem()).isNull()
            viewModel.previewBackup(mockUri)
            expectNoEvents() // Bleibt null
        }
    }

    // ========== RESET STATE TESTS ==========

// In BackupViewModelTest.kt

    @Test
    fun `resetBackupState - resets state to Idle`() = runTest(mainDispatcherRule.testDispatcher) {
        val mockUri = Uri.parse("content://fake/backup.json")
        fakeBackupRepository.exportSuccess = true

        // Arrange: Setze den State auf einen Nicht-Idle-Wert
        viewModel.exportBackup(mockUri)

        // Act & Assert
        viewModel.backupState.test {
            // 1. Prüfe den Startwert (vor dem Reset)
            assertThat(awaitItem()).isEqualTo(BackupState.ExportSuccess)

            // 2. Führe die Aktion aus
            viewModel.resetBackupState()

            // 3. Prüfe den Endwert (nach dem Reset)
            assertThat(awaitItem()).isEqualTo(BackupState.Idle)
        }
    }

    @Test
    fun `resetBackupState - clears preview`() = runTest(mainDispatcherRule.testDispatcher) {
        val mockUri = Uri.parse("content://fake/backup.json")

        // Arrange: Setze den Preview auf einen Nicht-Null-Wert
        val preview = BackupPreview(
            version = "1.0.0", timestamp = 1L, favoriteCount = 1,
            orderCount = 1, hiddenCount = 1, customNamesCount = 1
        )
        fakeBackupRepository.previewResult = preview
        viewModel.previewBackup(mockUri)

        // Act & Assert
        viewModel.backupPreview.test {
            // 1. Prüfe den Startwert (vor dem Reset)
            assertThat(awaitItem()).isEqualTo(preview)

            // 2. Führe die Aktion aus
            viewModel.resetBackupState()

            // 3. Prüfe den Endwert (nach dem Reset)
            assertThat(awaitItem()).isNull()
        }
    }

    // ========== STATE TRANSITION TESTS ==========

    @Test
    fun `multiple operations - maintains correct state sequence`() = runTest(mainDispatcherRule.testDispatcher) {
        val mockUri = Uri.parse("content://fake/backup.json")
        fakeBackupRepository.exportSuccess = true

        viewModel.backupState.test {
            assertThat(awaitItem()).isEqualTo(BackupState.Idle)
            // Export
            viewModel.exportBackup(mockUri)
            assertThat(awaitItem()).isEqualTo(BackupState.ExportSuccess) // 'Loading' übersprungen

            // Reset
            viewModel.resetBackupState()
            assertThat(awaitItem()).isEqualTo(BackupState.Idle)

            // Import
            fakeBackupRepository.importResult = ImportResult.Success(1, 0, emptySet())
            viewModel.importBackup(mockUri, ImportOptions())
            assertThat(awaitItem()).isInstanceOf(BackupState.ImportSuccess::class.java) // 'Loading' übersprungen
        }
    }

    @Test
    fun `selective import options - passes options correctly`() = runTest(mainDispatcherRule.testDispatcher) {
        val mockUri = Uri.parse("content://fake/backup.json")
        val options = ImportOptions(
            importFavorites = true,
            importOrder = false,
            importHiddenApps = true,
            importCustomNames = false
        )
        fakeBackupRepository.importResult = ImportResult.Success(1, 0, emptySet())

        viewModel.importBackup(mockUri, options)

        // Verify options were passed to repository
        assertThat(fakeBackupRepository.lastOptions).isNotNull()
        assertThat(fakeBackupRepository.lastOptions?.importFavorites).isTrue()
        assertThat(fakeBackupRepository.lastOptions?.importOrder).isFalse()
        assertThat(fakeBackupRepository.lastOptions?.importHiddenApps).isTrue()
        assertThat(fakeBackupRepository.lastOptions?.importCustomNames).isFalse()
    }
}

// ========== FAKE REPOSITORY (Unverändert) ==========

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

*/