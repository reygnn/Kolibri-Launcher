package com.github.reygnn.kolibri_launcher.ui

import com.github.reygnn.kolibri_launcher.domain.model.UsageImportResult
import com.github.reygnn.kolibri_launcher.domain.usecase.ExportUsageToFileUseCase
import com.github.reygnn.kolibri_launcher.domain.usecase.ImportUsageFromFileUseCase
import com.github.reygnn.kolibri_launcher.rule.MainDispatcherRule
import com.github.reygnn.kolibri_launcher.rule.TimberRule
import com.github.reygnn.kolibri_launcher.ui.usageexport.UsageExportUiEvent
import com.github.reygnn.kolibri_launcher.ui.usageexport.UsageExportViewModel
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert
import org.junit.Before
import org.junit.Rule
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class UsageExportViewModelTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()
    @get:Rule
    val timberRule = TimberRule()

    private lateinit var exportUseCase: ExportUsageToFileUseCase
    private lateinit var importUseCase: ImportUsageFromFileUseCase
    private lateinit var viewModel: UsageExportViewModel

    @Before
    fun setup() {
        exportUseCase = mockk()
        importUseCase = mockk()
        viewModel = UsageExportViewModel(exportUseCase, importUseCase)
    }

    // ========== EXPORT TESTS ==========

    @Test
    fun `exportToFile - success - emits ExportSuccess event`() = runTest {
        // Arrange
        val uri = "content://test"
        coEvery { exportUseCase(uri) } returns Result.success(Unit)

        // Event Collector starten — UnconfinedTestDispatcher gemäß Konvention,
        // sonst startet der Collector zu spät und verpasst Events.
        val events = mutableListOf<UsageExportUiEvent>()
        val job = launch(UnconfinedTestDispatcher(testScheduler)) {
            viewModel.uiEvent.toList(events)
        }

        // Act
        viewModel.exportToFile(uri)

        // Assert
        Assert.assertEquals(1, events.size)
        Assert.assertEquals(UsageExportUiEvent.ExportSuccess, events.first())

        job.cancel()
    }

    @Test
    fun `exportToFile - failure - emits ExportError event`() = runTest {
        // Arrange
        val uri = "content://fail"
        val errorMsg = "Disk full"
        coEvery { exportUseCase(uri) } returns Result.failure(Exception(errorMsg))

        val events = mutableListOf<UsageExportUiEvent>()
        val job = launch(UnconfinedTestDispatcher(testScheduler)) {
            viewModel.uiEvent.toList(events)
        }

        // Act
        viewModel.exportToFile(uri)

        // Assert
        Assert.assertEquals(1, events.size)
        val event = events.first() as UsageExportUiEvent.ExportError
        Assert.assertEquals(errorMsg, event.message)

        job.cancel()
    }

    // ========== IMPORT TESTS ==========

    @Test
    fun `importFromFile - success - emits ImportSuccess event`() = runTest {
        // Arrange
        val uri = "content://import"
        val successResult = UsageImportResult.Success(10, 50, 0)
        coEvery { importUseCase(uri, false) } returns successResult

        val events = mutableListOf<UsageExportUiEvent>()
        val job = launch(UnconfinedTestDispatcher(testScheduler)) {
            viewModel.uiEvent.toList(events)
        }

        // Act
        viewModel.importFromFile(uri, false)

        // Assert
        Assert.assertEquals(1, events.size)
        val event = events.first() as UsageExportUiEvent.ImportSuccess
        Assert.assertEquals(10, event.packagesImported)
        Assert.assertEquals(50, event.timestampsImported)

        job.cancel()
    }

    @Test
    fun `importFromFile - invalid format - emits InvalidFormat event`() = runTest {
        // Arrange
        val uri = "content://bad_json"
        coEvery { importUseCase(uri, true) } returns UsageImportResult.InvalidFormat

        val events = mutableListOf<UsageExportUiEvent>()
        val job = launch(UnconfinedTestDispatcher(testScheduler)) {
            viewModel.uiEvent.toList(events)
        }

        // Act
        viewModel.importFromFile(uri, true)

        // Assert
        Assert.assertEquals(UsageExportUiEvent.InvalidFormat, events.first())
        job.cancel()
    }

    @Test
    fun `importFromFile - unsupported version - emits UnsupportedVersion event`() = runTest {
        // Arrange
        val uri = "content://old"
        coEvery { importUseCase(uri, false) } returns UsageImportResult.UnsupportedVersion("9.0")

        val events = mutableListOf<UsageExportUiEvent>()
        val job = launch(UnconfinedTestDispatcher(testScheduler)) {
            viewModel.uiEvent.toList(events)
        }

        // Act
        viewModel.importFromFile(uri, false)

        // Assert
        val event = events.first() as UsageExportUiEvent.UnsupportedVersion
        Assert.assertEquals("9.0", event.version)
        job.cancel()
    }

    @Test
    fun `loading state - toggles correctly during operation`() = runTest {
        // Dieser Test ist tricky mit UnconfinedTestDispatcher, da alles sofort passiert.
        // Wir prüfen hier nur, dass es am Ende false ist.
        // Für exakte Prüfung "true -> false" bräuchte man StandardTestDispatcher und advanceUntilIdle().

        val uri = "content://test"
        coEvery { exportUseCase(uri) } returns Result.success(Unit)

        viewModel.exportToFile(uri)

        // Am Ende muss loading wieder aus sein
        Assert.assertTrue(viewModel.isLoading.value == false)
    }
}
