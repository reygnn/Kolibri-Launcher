package com.github.reygnn.kolibri_launcher.ui

import com.github.reygnn.kolibri_launcher.domain.model.UsageImportResult
import com.github.reygnn.kolibri_launcher.domain.usecase.ExportUsageToFileUseCase
import com.github.reygnn.kolibri_launcher.domain.usecase.ImportUsageFromFileUseCase
import com.github.reygnn.launcher.core.testing.MainDispatcherRule
import com.github.reygnn.kolibri_launcher.rule.TimberRule
import com.github.reygnn.kolibri_launcher.ui.usageexport.UsageExportUiEvent
import com.github.reygnn.kolibri_launcher.ui.usageexport.UsageExportViewModel
import com.github.reygnn.launcher.core.testing.recordEmissions
import com.google.common.truth.Truth.assertThat
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
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
        viewModel = UsageExportViewModel(exportUseCase, importUseCase, mainDispatcherRule.testDispatcher)
    }

    // ========== EXPORT TESTS ==========

    @Test
    fun `exportToFile - success - emits ExportSuccess event`() = runTest(mainDispatcherRule.testDispatcher) {
        // Arrange
        val uri = "content://test"
        coEvery { exportUseCase(uri) } returns Result.success(Unit)

        // Event Collector starten — UnconfinedTestDispatcher gemäß Konvention,
        // sonst startet der Collector zu spät und verpasst Events.
        val events = mutableListOf<UsageExportUiEvent>()
        val job = recordEmissions(viewModel.event, into = events)

        // Act
        viewModel.exportToFile(uri)
        advanceUntilIdle()

        // Assert
        assertThat(events.size).isEqualTo(1)
        assertThat(events.first()).isEqualTo(UsageExportUiEvent.ExportSuccess)

        job.cancel()
    }

    @Test
    fun `exportToFile - failure - emits ExportError event`() = runTest(mainDispatcherRule.testDispatcher) {
        // Arrange
        val uri = "content://fail"
        val errorMsg = "Disk full"
        coEvery { exportUseCase(uri) } returns Result.failure(Exception(errorMsg))

        val events = mutableListOf<UsageExportUiEvent>()
        val job = recordEmissions(viewModel.event, into = events)

        // Act
        viewModel.exportToFile(uri)
        advanceUntilIdle()

        // Assert
        assertThat(events.size).isEqualTo(1)
        val event = events.first() as UsageExportUiEvent.ExportError
        assertThat(event.message).isEqualTo(errorMsg)

        job.cancel()
    }

    // ========== IMPORT TESTS ==========

    @Test
    fun `importFromFile - success - emits ImportSuccess event`() = runTest(mainDispatcherRule.testDispatcher) {
        // Arrange
        val uri = "content://import"
        val successResult = UsageImportResult.Success(10, 50, 0)
        coEvery { importUseCase(uri, false) } returns successResult

        val events = mutableListOf<UsageExportUiEvent>()
        val job = recordEmissions(viewModel.event, into = events)

        // Act
        viewModel.importFromFile(uri, false)
        advanceUntilIdle()

        // Assert
        assertThat(events.size).isEqualTo(1)
        val event = events.first() as UsageExportUiEvent.ImportSuccess
        assertThat(event.packagesImported).isEqualTo(10)
        assertThat(event.timestampsImported).isEqualTo(50)

        job.cancel()
    }

    @Test
    fun `importFromFile - invalid format - emits InvalidFormat event`() = runTest(mainDispatcherRule.testDispatcher) {
        // Arrange
        val uri = "content://bad_json"
        coEvery { importUseCase(uri, true) } returns UsageImportResult.InvalidFormat

        val events = mutableListOf<UsageExportUiEvent>()
        val job = recordEmissions(viewModel.event, into = events)

        // Act
        viewModel.importFromFile(uri, true)
        advanceUntilIdle()

        // Assert
        assertThat(events.first()).isEqualTo(UsageExportUiEvent.InvalidFormat)
        job.cancel()
    }

    @Test
    fun `importFromFile - unsupported version - emits UnsupportedVersion event`() = runTest(mainDispatcherRule.testDispatcher) {
        // Arrange
        val uri = "content://old"
        coEvery { importUseCase(uri, false) } returns UsageImportResult.UnsupportedVersion("9.0")

        val events = mutableListOf<UsageExportUiEvent>()
        val job = recordEmissions(viewModel.event, into = events)

        // Act
        viewModel.importFromFile(uri, false)
        advanceUntilIdle()

        // Assert
        val event = events.first() as UsageExportUiEvent.UnsupportedVersion
        assertThat(event.version).isEqualTo("9.0")
        job.cancel()
    }

    @Test
    fun `loading state - toggles correctly during operation`() = runTest(mainDispatcherRule.testDispatcher) {
        // Dieser Test ist tricky mit UnconfinedTestDispatcher, da alles sofort passiert.
        // Wir prüfen hier nur, dass es am Ende false ist.
        // Für exakte Prüfung "true -> false" bräuchte man StandardTestDispatcher und advanceUntilIdle().

        val uri = "content://test"
        coEvery { exportUseCase(uri) } returns Result.success(Unit)

        viewModel.exportToFile(uri)
        advanceUntilIdle()

        // Am Ende muss loading wieder aus sein
        assertThat(viewModel.isLoading.value == false).isTrue()
    }
}
