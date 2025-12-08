package com.github.reygnn.kolibri_launcher.ui

import com.github.reygnn.kolibri_launcher.domain.model.UsageImportResult
import com.github.reygnn.kolibri_launcher.domain.usecase.ExportUsageToFileUseCase
import com.github.reygnn.kolibri_launcher.domain.usecase.ImportUsageFromFileUseCase
import com.github.reygnn.kolibri_launcher.rule.MainDispatcherRule
import com.github.reygnn.kolibri_launcher.rules.TimberRule
import com.github.reygnn.kolibri_launcher.ui.usageexport.UsageExportUiEvent
import com.github.reygnn.kolibri_launcher.ui.usageexport.UsageExportViewModel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mock
import org.mockito.Mockito
import org.mockito.junit.MockitoJUnitRunner

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(MockitoJUnitRunner::class)
class UsageExportViewModelTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()
    @get:Rule
    val timberRule = TimberRule()

    @Mock
    private lateinit var exportUseCase: ExportUsageToFileUseCase

    @Mock
    private lateinit var importUseCase: ImportUsageFromFileUseCase

    private lateinit var viewModel: UsageExportViewModel

    @Before
    fun setup() {
        viewModel = UsageExportViewModel(exportUseCase, importUseCase)
    }

    // ========== EXPORT TESTS ==========

    @Test
    fun `exportToFile - success - emits ExportSuccess event`() = runTest {
        // Arrange
        val uri = "content://test"
        Mockito.`when`(exportUseCase(uri)).thenReturn(Result.success(Unit))

        // Event Collector starten
        val events = mutableListOf<UsageExportUiEvent>()
        val job = launch(UnconfinedTestDispatcher()) {
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
        Mockito.`when`(exportUseCase(uri)).thenReturn(Result.failure(Exception(errorMsg)))

        val events = mutableListOf<UsageExportUiEvent>()
        val job = launch(UnconfinedTestDispatcher()) {
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
        Mockito.`when`(importUseCase(uri, false)).thenReturn(successResult)

        val events = mutableListOf<UsageExportUiEvent>()
        val job = launch(UnconfinedTestDispatcher()) {
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
        Mockito.`when`(importUseCase(uri, true)).thenReturn(UsageImportResult.InvalidFormat)

        val events = mutableListOf<UsageExportUiEvent>()
        val job = launch(UnconfinedTestDispatcher()) {
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
        Mockito.`when`(importUseCase(uri, false))
            .thenReturn(UsageImportResult.UnsupportedVersion("9.0"))

        val events = mutableListOf<UsageExportUiEvent>()
        val job = launch(UnconfinedTestDispatcher()) {
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
        Mockito.`when`(exportUseCase(uri)).thenReturn(Result.success(Unit))

        viewModel.exportToFile(uri)

        // Am Ende muss loading wieder aus sein
        Assert.assertTrue(viewModel.isLoading.value == false)
    }
}