package com.github.reygnn.kolibri_launcher.ui

import app.cash.turbine.test
import com.github.reygnn.kolibri_launcher.rule.MainDispatcherRule
import com.github.reygnn.kolibri_launcher.domain.model.AppInfo
import com.github.reygnn.kolibri_launcher.domain.repository.HiddenAppsRepository
import com.github.reygnn.kolibri_launcher.domain.repository.InstalledAppsRepository
import com.github.reygnn.kolibri_launcher.domain.usecase.GetHiddenAppsUseCase
import com.github.reygnn.kolibri_launcher.domain.usecase.GetInstalledAppsUseCase
import com.github.reygnn.kolibri_launcher.domain.usecase.UpdateHiddenAppsUseCase
import com.github.reygnn.kolibri_launcher.rule.TimberRule
import com.github.reygnn.kolibri_launcher.ui.base.UiEvent
import com.github.reygnn.kolibri_launcher.ui.hiddenapps.HiddenAppsViewModel
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import java.io.IOException
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

@ExperimentalCoroutinesApi
class HiddenAppsViewModelTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()
    @get:Rule
    val timberRule = TimberRule()

    private lateinit var installedAppsRepository: InstalledAppsRepository
    private lateinit var visibilityRepository: HiddenAppsRepository

    // UseCases
    private lateinit var getInstalledAppsUseCase: GetInstalledAppsUseCase
    private lateinit var getHiddenAppsUseCase: GetHiddenAppsUseCase
    private lateinit var updateHiddenAppsUseCase: UpdateHiddenAppsUseCase

    private lateinit var viewModel: HiddenAppsViewModel

    private val app1 = AppInfo("App A", "App A", "pkg1", "class1")
    private val app2 = AppInfo("App B", "App B", "pkg2", "class2")
    private val app3 = AppInfo("App C", "App C", "pkg3", "class3")
    private val testApps = listOf(app1, app2, app3)

    @Before
    fun setup() {
        installedAppsRepository = mockk(relaxed = true)
        visibilityRepository = mockk(relaxed = true)
    }

    private fun setupViewModel() {
        // UseCases mit Mocks initialisieren
        getInstalledAppsUseCase = GetInstalledAppsUseCase(installedAppsRepository)
        getHiddenAppsUseCase = GetHiddenAppsUseCase(visibilityRepository)
        updateHiddenAppsUseCase = UpdateHiddenAppsUseCase(visibilityRepository)

        viewModel = HiddenAppsViewModel(
            getInstalledAppsUseCase,
            getHiddenAppsUseCase,
            updateHiddenAppsUseCase,
            mainDispatcher = mainDispatcherRule.testDispatcher
        )
    }

    // ========== EXISTING TESTS ==========

    @Test
    fun `initialize - loads all apps and pre-selects hidden apps`() = runTest {
        val initiallyHidden = setOf(app2.componentName)
        every { installedAppsRepository.getInstalledApps() } returns flowOf(testApps)
        every { visibilityRepository.hiddenAppsFlow } returns flowOf(initiallyHidden)

        setupViewModel()

        viewModel.initialize()
        advanceUntilIdle()

        val uiState = viewModel.uiState.value
        assertEquals(3, uiState.selectableApps.size)
        assertFalse(uiState.selectableApps.find { it.appInfo.packageName == "pkg1" }!!.isSelected)
        assertTrue(uiState.selectableApps.find { it.appInfo.packageName == "pkg2" }!!.isSelected)
        assertFalse(uiState.selectableApps.find { it.appInfo.packageName == "pkg3" }!!.isSelected)
    }

    @Test
    fun `onAppToggled - adds app to selection`() = runTest {
        every { installedAppsRepository.getInstalledApps() } returns flowOf(testApps)
        every { visibilityRepository.hiddenAppsFlow } returns flowOf(emptySet())
        setupViewModel()

        viewModel.initialize()
        advanceUntilIdle()

        viewModel.onAppToggled(app1)
        advanceUntilIdle()

        val uiState = viewModel.uiState.value
        assertTrue(uiState.selectableApps.find { it.appInfo.packageName == "pkg1" }!!.isSelected)
    }

    @Test
    fun `onAppToggled - removes app from selection`() = runTest {
        every { installedAppsRepository.getInstalledApps() } returns flowOf(testApps)
        every { visibilityRepository.hiddenAppsFlow } returns flowOf(setOf(app1.componentName))
        setupViewModel()

        viewModel.initialize()
        advanceUntilIdle()

        viewModel.onAppToggled(app1)
        advanceUntilIdle()

        val uiState = viewModel.uiState.value
        assertFalse(uiState.selectableApps.find { it.appInfo.packageName == "pkg1" }!!.isSelected)
    }

    @Test
    fun `onSearchQueryChanged - filters the app list`() = runTest {
        every { installedAppsRepository.getInstalledApps() } returns flowOf(testApps)
        every { visibilityRepository.hiddenAppsFlow } returns flowOf(emptySet())
        setupViewModel()

        viewModel.initialize()
        advanceUntilIdle()

        viewModel.onSearchQueryChanged("B")
        advanceUntilIdle()

        val uiState = viewModel.uiState.value
        assertEquals(1, uiState.selectableApps.size)
        assertEquals("App B", uiState.selectableApps[0].appInfo.displayName)
    }

    @Test
    fun `onDoneClicked - correctly updates visibilities in a single batch`() = runTest {
        // Arrange
        val initiallyHidden = setOf(app1.componentName)
        every { installedAppsRepository.getInstalledApps() } returns flowOf(testApps)
        every { visibilityRepository.hiddenAppsFlow } returns flowOf(initiallyHidden)
        setupViewModel()

        viewModel.initialize()
        advanceUntilIdle()

        // Act: App1 wird sichtbar gemacht, App3 wird versteckt
        viewModel.onAppToggled(app1)
        viewModel.onAppToggled(app3)
        advanceUntilIdle()

        // Assert
        viewModel.event.test {
            viewModel.onDoneClicked()
            advanceUntilIdle()

            // Überprüfe den EINEN Aufruf der neuen Methode (via UseCase) — suspend → coVerify
            coVerify {
                visibilityRepository.updateComponentVisibilities(
                    componentsToHide = setOf(app3.componentName), // App3 sollte versteckt werden
                    componentsToShow = setOf(app1.componentName)  // App1 sollte sichtbar gemacht werden
                )
            }

            // Stelle sicher, dass die alten Methoden NIE aufgerufen wurden
            coVerify(exactly = 0) { visibilityRepository.hideComponent(any()) }
            coVerify(exactly = 0) { visibilityRepository.showComponent(any()) }

            assertEquals(UiEvent.NavigateUp, awaitItem())
        }
    }

    @Test
    fun `initialize - when loading fails - emits error event`() = runTest {
        every { installedAppsRepository.getInstalledApps() } returns flow { throw IOException("DB error") }
        every { visibilityRepository.hiddenAppsFlow } returns flowOf(emptySet())

        setupViewModel()

        viewModel.event.test {
            viewModel.initialize()
            advanceUntilIdle()

            val event = awaitItem()
            assertTrue(event is UiEvent.ShowToast)
        }
    }

    // ========== CRASH-RESISTANCE TESTS ==========

    @Test
    fun `initialize - when hiddenAppsFlow fails - emits error event`() = runTest {
        every { installedAppsRepository.getInstalledApps() } returns flowOf(testApps)
        every { visibilityRepository.hiddenAppsFlow } returns flow {
            throw IOException("Cannot read hidden apps")
        }

        setupViewModel()

        viewModel.event.test {
            viewModel.initialize()
            advanceUntilIdle()

            val event = awaitItem()
            assertTrue(event is UiEvent.ShowToast)
        }
    }

    @Test
    fun `initialize - when both flows fail - emits error event`() = runTest {
        every { installedAppsRepository.getInstalledApps() } returns flow {
            throw RuntimeException("Database corrupted")
        }
        every { visibilityRepository.hiddenAppsFlow } returns flow {
            throw IOException("Cannot read")
        }

        setupViewModel()

        viewModel.event.test {
            viewModel.initialize()
            advanceUntilIdle()

            val event = awaitItem()
            assertTrue(event is UiEvent.ShowToast)
        }
    }


    @Test
    fun `onDoneClicked - when visibility update fails - still navigates up`() = runTest {
        // Arrange
        every { installedAppsRepository.getInstalledApps() } returns flowOf(testApps)
        every { visibilityRepository.hiddenAppsFlow } returns flowOf(emptySet())
        // Simuliere, dass der neue Batch-Aufruf eine Exception wirft (suspend → coEvery)
        coEvery { visibilityRepository.updateComponentVisibilities(any(), any()) } throws IOException("DataStore write failed")
        setupViewModel()

        viewModel.initialize()
        advanceUntilIdle()

        // Act
        viewModel.onAppToggled(app1) // Eine Änderung vornehmen, damit der Aufruf stattfindet
        advanceUntilIdle()

        // Assert
        viewModel.event.test {
            viewModel.onDoneClicked()
            advanceUntilIdle()

            // Überprüfe, dass der Aufruf versucht wurde
            coVerify {
                visibilityRepository.updateComponentVisibilities(
                    componentsToHide = setOf(app1.componentName),
                    componentsToShow = emptySet()
                )
            }

            // Das ViewModel sollte den Fehler fangen und trotzdem navigieren
            assertEquals(UiEvent.NavigateUp, awaitItem())
        }
    }

    @Test
    fun `onSearchQueryChanged - with empty query - shows all apps`() = runTest {
        every { installedAppsRepository.getInstalledApps() } returns flowOf(testApps)
        every { visibilityRepository.hiddenAppsFlow } returns flowOf(emptySet())
        setupViewModel()

        viewModel.initialize()
        advanceUntilIdle()

        viewModel.onSearchQueryChanged("")
        advanceUntilIdle()

        val uiState = viewModel.uiState.value
        assertEquals(3, uiState.selectableApps.size)
    }

    @Test
    fun `onSearchQueryChanged - with query that matches nothing - shows empty list`() = runTest {
        every { installedAppsRepository.getInstalledApps() } returns flowOf(testApps)
        every { visibilityRepository.hiddenAppsFlow } returns flowOf(emptySet())
        setupViewModel()

        viewModel.initialize()
        advanceUntilIdle()

        viewModel.onSearchQueryChanged("XYZ_NOT_FOUND")
        advanceUntilIdle()

        val uiState = viewModel.uiState.value
        assertTrue(uiState.selectableApps.isEmpty())
    }

    @Test
    fun `onSearchQueryChanged - case insensitive search works`() = runTest {
        every { installedAppsRepository.getInstalledApps() } returns flowOf(testApps)
        every { visibilityRepository.hiddenAppsFlow } returns flowOf(emptySet())
        setupViewModel()

        viewModel.initialize()
        advanceUntilIdle()

        viewModel.onSearchQueryChanged("app b")
        advanceUntilIdle()

        val uiState = viewModel.uiState.value
        assertEquals(1, uiState.selectableApps.size)
        assertEquals("App B", uiState.selectableApps[0].appInfo.displayName)
    }

    @Test
    fun `onAppToggled - with mock app - does not crash`() = runTest {
        every { installedAppsRepository.getInstalledApps() } returns flowOf(testApps)
        every { visibilityRepository.hiddenAppsFlow } returns flowOf(emptySet())
        setupViewModel()

        viewModel.initialize()
        advanceUntilIdle()

        val mockApp = mockk<AppInfo>(relaxed = true)
        viewModel.onAppToggled(mockApp)
        advanceUntilIdle()

        val uiState = viewModel.uiState.value
        assertNotNull(uiState)
    }

    @Test
    fun `onDoneClicked - with no changes - navigates up without calling repository`() = runTest {
        // Arrange
        every { installedAppsRepository.getInstalledApps() } returns flowOf(testApps)
        every { visibilityRepository.hiddenAppsFlow } returns flowOf(emptySet())
        setupViewModel()

        viewModel.initialize()
        advanceUntilIdle()

        // Assert
        viewModel.event.test {
            // 1. Lauschen ist aktiv.

            // 2. Aktion auslösen.
            viewModel.onDoneClicked()
            advanceUntilIdle()

            // 3. Seiteneffekte überprüfen.
            coVerify(exactly = 0) { visibilityRepository.updateComponentVisibilities(any(), any()) }

            // 4. Event empfangen.
            assertEquals(UiEvent.NavigateUp, awaitItem())
        }
    }

    @Test
    fun `initialize - with empty app list - creates empty UI state`() = runTest {
        every { installedAppsRepository.getInstalledApps() } returns flowOf(emptyList())
        every { visibilityRepository.hiddenAppsFlow } returns flowOf(emptySet())

        setupViewModel()

        viewModel.initialize()
        advanceUntilIdle()

        val uiState = viewModel.uiState.value
        assertTrue(uiState.selectableApps.isEmpty())
    }

    @Test
    fun `initialize - with all apps hidden - all apps pre-selected`() = runTest {
        val allHidden = testApps.map { it.componentName }.toSet()
        every { installedAppsRepository.getInstalledApps() } returns flowOf(testApps)
        every { visibilityRepository.hiddenAppsFlow } returns flowOf(allHidden)

        setupViewModel()

        viewModel.initialize()
        advanceUntilIdle()

        val uiState = viewModel.uiState.value
        assertTrue(uiState.selectableApps.all { it.isSelected })
    }

    @Test
    fun `onSearchQueryChanged - rapid query changes - handles correctly`() = runTest {
        every { installedAppsRepository.getInstalledApps() } returns flowOf(testApps)
        every { visibilityRepository.hiddenAppsFlow } returns flowOf(emptySet())
        setupViewModel()

        viewModel.initialize()
        advanceUntilIdle()

        viewModel.onSearchQueryChanged("A")
        viewModel.onSearchQueryChanged("B")
        viewModel.onSearchQueryChanged("C")
        advanceUntilIdle()

        val uiState = viewModel.uiState.value
        assertEquals(1, uiState.selectableApps.size)
        assertEquals("App C", uiState.selectableApps[0].appInfo.displayName)
    }

    @Test
    fun `onAppToggled - toggle same app multiple times - works correctly`() = runTest {
        every { installedAppsRepository.getInstalledApps() } returns flowOf(testApps)
        every { visibilityRepository.hiddenAppsFlow } returns flowOf(emptySet())
        setupViewModel()

        viewModel.initialize()
        advanceUntilIdle()

        viewModel.onAppToggled(app1)
        advanceUntilIdle()
        assertTrue(viewModel.uiState.value.selectableApps.find { it.appInfo.packageName == "pkg1" }!!.isSelected)

        viewModel.onAppToggled(app1)
        advanceUntilIdle()
        assertFalse(viewModel.uiState.value.selectableApps.find { it.appInfo.packageName == "pkg1" }!!.isSelected)

        viewModel.onAppToggled(app1)
        advanceUntilIdle()
        assertTrue(viewModel.uiState.value.selectableApps.find { it.appInfo.packageName == "pkg1" }!!.isSelected)
    }

    @Test
    fun `onDoneClicked - when repository throws exception - still navigates up`() = runTest {
        every { installedAppsRepository.getInstalledApps() } returns flowOf(testApps)
        every { visibilityRepository.hiddenAppsFlow } returns flowOf(emptySet())
        coEvery {
            visibilityRepository.updateComponentVisibilities(any(), any())
        } throws IOException("Write failed")

        setupViewModel()

        viewModel.initialize()
        advanceUntilIdle()

        viewModel.onAppToggled(app1)
        advanceUntilIdle()

        viewModel.event.test {
            viewModel.onDoneClicked()
            advanceUntilIdle()

            // Should still navigate despite errors
            assertEquals(UiEvent.NavigateUp, awaitItem())
        }
    }
}
