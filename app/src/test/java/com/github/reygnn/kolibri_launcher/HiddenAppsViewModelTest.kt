package com.github.reygnn.kolibri_launcher

import app.cash.turbine.test
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.mockito.kotlin.any
import org.mockito.Mock
import org.mockito.Mockito.mock
import org.mockito.MockitoAnnotations
import org.mockito.kotlin.doAnswer
import org.mockito.kotlin.never
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import java.io.IOException
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

@ExperimentalCoroutinesApi
class HiddenAppsViewModelTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    @Mock private lateinit var installedAppsRepository: InstalledAppsRepository
    @Mock private lateinit var visibilityRepository: AppVisibilityRepository

    private lateinit var viewModel: HiddenAppsViewModel

    private val app1 = AppInfo("App A", "App A", "pkg1", "class1")
    private val app2 = AppInfo("App B", "App B", "pkg2", "class2")
    private val app3 = AppInfo("App C", "App C", "pkg3", "class3")
    private val testApps = listOf(app1, app2, app3)

    @Before
    fun setup() {
        MockitoAnnotations.openMocks(this)
    }

    private fun setupViewModel() {
        viewModel = HiddenAppsViewModel(installedAppsRepository, visibilityRepository)
    }

    // ========== EXISTING TESTS ==========

    @Test
    fun `initialize - loads all apps and pre-selects hidden apps`() = runTest {
        val initiallyHidden = setOf(app2.componentName)
        whenever(installedAppsRepository.getInstalledApps()).thenReturn(flowOf(testApps))
        whenever(visibilityRepository.hiddenAppsFlow).thenReturn(flowOf(initiallyHidden))

        setupViewModel()
        advanceUntilIdle()

        val uiState = viewModel.uiState.value
        assertEquals(3, uiState.selectableApps.size)
        assertFalse(uiState.selectableApps.find { it.appInfo.packageName == "pkg1" }!!.isSelected)
        assertTrue(uiState.selectableApps.find { it.appInfo.packageName == "pkg2" }!!.isSelected)
        assertFalse(uiState.selectableApps.find { it.appInfo.packageName == "pkg3" }!!.isSelected)
    }

    @Test
    fun `onAppToggled - adds app to selection`() = runTest {
        whenever(installedAppsRepository.getInstalledApps()).thenReturn(flowOf(testApps))
        whenever(visibilityRepository.hiddenAppsFlow).thenReturn(flowOf(emptySet()))
        setupViewModel()
        advanceUntilIdle()

        viewModel.onAppToggled(app1)
        advanceUntilIdle()

        val uiState = viewModel.uiState.value
        assertTrue(uiState.selectableApps.find { it.appInfo.packageName == "pkg1" }!!.isSelected)
    }

    @Test
    fun `onAppToggled - removes app from selection`() = runTest {
        whenever(installedAppsRepository.getInstalledApps()).thenReturn(flowOf(testApps))
        whenever(visibilityRepository.hiddenAppsFlow).thenReturn(flowOf(setOf(app1.componentName)))
        setupViewModel()
        advanceUntilIdle()

        viewModel.onAppToggled(app1)
        advanceUntilIdle()

        val uiState = viewModel.uiState.value
        assertFalse(uiState.selectableApps.find { it.appInfo.packageName == "pkg1" }!!.isSelected)
    }

    @Test
    fun `onSearchQueryChanged - filters the app list`() = runTest {
        whenever(installedAppsRepository.getInstalledApps()).thenReturn(flowOf(testApps))
        whenever(visibilityRepository.hiddenAppsFlow).thenReturn(flowOf(emptySet()))
        setupViewModel()
        advanceUntilIdle()

        viewModel.onSearchQueryChanged("B")
        advanceUntilIdle()

        val uiState = viewModel.uiState.value
        assertEquals(1, uiState.selectableApps.size)
        assertEquals("App B", uiState.selectableApps[0].appInfo.displayName)
    }

    @Test
    fun `onDoneClicked - correctly hides and shows apps`() = runTest {
        val initiallyHidden = setOf(app1.componentName)
        whenever(installedAppsRepository.getInstalledApps()).thenReturn(flowOf(testApps))
        whenever(visibilityRepository.hiddenAppsFlow).thenReturn(flowOf(initiallyHidden))
        setupViewModel()
        advanceUntilIdle()

        viewModel.onAppToggled(app1)
        viewModel.onAppToggled(app3)
        advanceUntilIdle()

        viewModel.eventFlow.test {
            viewModel.onDoneClicked()
            advanceUntilIdle()

            verify(visibilityRepository).showComponent(app1.componentName)
            verify(visibilityRepository).hideComponent(app3.componentName)
            verify(visibilityRepository, never()).hideComponent(app1.componentName)
            verify(visibilityRepository, never()).showComponent(app3.componentName)

            assertEquals(UiEvent.NavigateUp, awaitItem())
        }
    }

    @Test
    fun `initialize - when loading fails - emits error event`() = runTest {
        whenever(installedAppsRepository.getInstalledApps()).thenReturn(flow { throw IOException("DB error") })
        whenever(visibilityRepository.hiddenAppsFlow).thenReturn(flowOf(emptySet()))

        setupViewModel()

        viewModel.eventFlow.test {
            advanceUntilIdle()

            val event = awaitItem()
            assertTrue(event is UiEvent.ShowToast)
        }
    }

    // ========== NEW CRASH-RESISTANCE TESTS ==========

    @Test
    fun `initialize - when hiddenAppsFlow fails - emits error event`() = runTest {
        whenever(installedAppsRepository.getInstalledApps()).thenReturn(flowOf(testApps))
        whenever(visibilityRepository.hiddenAppsFlow).thenReturn(flow {
            throw IOException("Cannot read hidden apps")
        })

        setupViewModel()

        viewModel.eventFlow.test {
            advanceUntilIdle()

            val event = awaitItem()
            assertTrue(event is UiEvent.ShowToast)
        }
    }

    @Test
    fun `initialize - when both flows fail - emits error event`() = runTest {
        whenever(installedAppsRepository.getInstalledApps()).thenReturn(flow {
            throw RuntimeException("Database corrupted")
        })
        whenever(visibilityRepository.hiddenAppsFlow).thenReturn(flow {
            throw IOException("Cannot read")
        })

        setupViewModel()

        viewModel.eventFlow.test {
            advanceUntilIdle()

            val event = awaitItem()
            assertTrue(event is UiEvent.ShowToast)
        }
    }

    @Test
    fun `onDoneClicked - when hideComponent fails - still continues with other operations`() = runTest {
        whenever(installedAppsRepository.getInstalledApps()).thenReturn(flowOf(testApps))
        whenever(visibilityRepository.hiddenAppsFlow).thenReturn(flowOf(emptySet()))

        // First call fails, second succeeds
        whenever(visibilityRepository.hideComponent(app1.componentName)).thenReturn(false)
        whenever(visibilityRepository.hideComponent(app2.componentName)).thenReturn(true)

        setupViewModel()
        advanceUntilIdle()

        viewModel.onAppToggled(app1)
        viewModel.onAppToggled(app2)
        advanceUntilIdle()

        viewModel.eventFlow.test {
            viewModel.onDoneClicked()
            advanceUntilIdle()

            verify(visibilityRepository).hideComponent(app1.componentName)
            verify(visibilityRepository).hideComponent(app2.componentName)

            assertEquals(UiEvent.NavigateUp, awaitItem())
        }
    }

    @Test
    fun `onDoneClicked - when showComponent fails - still continues with other operations`() = runTest {
        val initiallyHidden = setOf(app1.componentName, app2.componentName)
        whenever(installedAppsRepository.getInstalledApps()).thenReturn(flowOf(testApps))
        whenever(visibilityRepository.hiddenAppsFlow).thenReturn(flowOf(initiallyHidden))

        whenever(visibilityRepository.showComponent(app1.componentName)).thenReturn(false)
        whenever(visibilityRepository.showComponent(app2.componentName)).thenReturn(true)

        setupViewModel()
        advanceUntilIdle()

        viewModel.onAppToggled(app1)
        viewModel.onAppToggled(app2)
        advanceUntilIdle()

        viewModel.eventFlow.test {
            viewModel.onDoneClicked()
            advanceUntilIdle()

            verify(visibilityRepository).showComponent(app1.componentName)
            verify(visibilityRepository).showComponent(app2.componentName)

            assertEquals(UiEvent.NavigateUp, awaitItem())
        }
    }

    @Test
    fun `onSearchQueryChanged - with empty query - shows all apps`() = runTest {
        whenever(installedAppsRepository.getInstalledApps()).thenReturn(flowOf(testApps))
        whenever(visibilityRepository.hiddenAppsFlow).thenReturn(flowOf(emptySet()))
        setupViewModel()
        advanceUntilIdle()

        viewModel.onSearchQueryChanged("")
        advanceUntilIdle()

        val uiState = viewModel.uiState.value
        assertEquals(3, uiState.selectableApps.size)
    }

    @Test
    fun `onSearchQueryChanged - with query that matches nothing - shows empty list`() = runTest {
        whenever(installedAppsRepository.getInstalledApps()).thenReturn(flowOf(testApps))
        whenever(visibilityRepository.hiddenAppsFlow).thenReturn(flowOf(emptySet()))
        setupViewModel()
        advanceUntilIdle()

        viewModel.onSearchQueryChanged("XYZ_NOT_FOUND")
        advanceUntilIdle()

        val uiState = viewModel.uiState.value
        assertTrue(uiState.selectableApps.isEmpty())
    }

    @Test
    fun `onSearchQueryChanged - case insensitive search works`() = runTest {
        whenever(installedAppsRepository.getInstalledApps()).thenReturn(flowOf(testApps))
        whenever(visibilityRepository.hiddenAppsFlow).thenReturn(flowOf(emptySet()))
        setupViewModel()
        advanceUntilIdle()

        viewModel.onSearchQueryChanged("app b")
        advanceUntilIdle()

        val uiState = viewModel.uiState.value
        assertEquals(1, uiState.selectableApps.size)
        assertEquals("App B", uiState.selectableApps[0].appInfo.displayName)
    }

    @Test
    fun `onAppToggled - with mock app - does not crash`() = runTest {
        whenever(installedAppsRepository.getInstalledApps()).thenReturn(flowOf(testApps))
        whenever(visibilityRepository.hiddenAppsFlow).thenReturn(flowOf(emptySet()))
        setupViewModel()
        advanceUntilIdle()

        val mockApp = mock<AppInfo>()
        viewModel.onAppToggled(mockApp)
        advanceUntilIdle()

        val uiState = viewModel.uiState.value
        assertNotNull(uiState)
    }

    @Test
    fun `onDoneClicked - with no changes - navigates up without calling repository`() = runTest {
        whenever(installedAppsRepository.getInstalledApps()).thenReturn(flowOf(testApps))
        whenever(visibilityRepository.hiddenAppsFlow).thenReturn(flowOf(emptySet()))
        setupViewModel()
        advanceUntilIdle()

        viewModel.eventFlow.test {
            viewModel.onDoneClicked()
            advanceUntilIdle()

            verify(visibilityRepository, never()).hideComponent(any())
            verify(visibilityRepository, never()).showComponent(any())

            assertEquals(UiEvent.NavigateUp, awaitItem())
        }
    }

    @Test
    fun `initialize - with empty app list - creates empty UI state`() = runTest {
        whenever(installedAppsRepository.getInstalledApps()).thenReturn(flowOf(emptyList()))
        whenever(visibilityRepository.hiddenAppsFlow).thenReturn(flowOf(emptySet()))

        setupViewModel()
        advanceUntilIdle()

        val uiState = viewModel.uiState.value
        assertTrue(uiState.selectableApps.isEmpty())
    }

    @Test
    fun `initialize - with all apps hidden - all apps pre-selected`() = runTest {
        val allHidden = testApps.map { it.componentName }.toSet()
        whenever(installedAppsRepository.getInstalledApps()).thenReturn(flowOf(testApps))
        whenever(visibilityRepository.hiddenAppsFlow).thenReturn(flowOf(allHidden))

        setupViewModel()
        advanceUntilIdle()

        val uiState = viewModel.uiState.value
        assertTrue(uiState.selectableApps.all { it.isSelected })
    }

    @Test
    fun `onSearchQueryChanged - rapid query changes - handles correctly`() = runTest {
        whenever(installedAppsRepository.getInstalledApps()).thenReturn(flowOf(testApps))
        whenever(visibilityRepository.hiddenAppsFlow).thenReturn(flowOf(emptySet()))
        setupViewModel()
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
        whenever(installedAppsRepository.getInstalledApps()).thenReturn(flowOf(testApps))
        whenever(visibilityRepository.hiddenAppsFlow).thenReturn(flowOf(emptySet()))
        setupViewModel()
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
        whenever(installedAppsRepository.getInstalledApps()).thenReturn(flowOf(testApps))
        whenever(visibilityRepository.hiddenAppsFlow).thenReturn(flowOf(emptySet()))
        whenever(visibilityRepository.hideComponent(any())).doAnswer {
            throw IOException("Write failed")
        }

        setupViewModel()
        advanceUntilIdle()

        viewModel.onAppToggled(app1)
        advanceUntilIdle()

        viewModel.eventFlow.test {
            viewModel.onDoneClicked()
            advanceUntilIdle()

            // Should still navigate despite errors
            assertEquals(UiEvent.NavigateUp, awaitItem())
        }
    }
}