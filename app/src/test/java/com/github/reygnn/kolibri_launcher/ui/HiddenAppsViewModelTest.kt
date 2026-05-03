package com.github.reygnn.kolibri_launcher.ui

import app.cash.turbine.test
import com.github.reygnn.kolibri_launcher.domain.model.AppInfo
import com.github.reygnn.kolibri_launcher.domain.repository.HiddenAppsRepository
import com.github.reygnn.kolibri_launcher.domain.repository.InstalledAppsRepository
import com.github.reygnn.kolibri_launcher.domain.usecase.GetHiddenAppsUseCase
import com.github.reygnn.kolibri_launcher.domain.usecase.GetInstalledAppsUseCase
import com.github.reygnn.kolibri_launcher.domain.usecase.UpdateHiddenAppsUseCase
import com.github.reygnn.kolibri_launcher.fakes.FakeHiddenAppsRepository
import com.github.reygnn.kolibri_launcher.fakes.FakeInstalledAppsRepository
import com.github.reygnn.kolibri_launcher.rule.MainDispatcherRule
import com.github.reygnn.kolibri_launcher.rule.TimberRule
import com.github.reygnn.kolibri_launcher.ui.base.UiEvent
import com.github.reygnn.kolibri_launcher.ui.hiddenapps.HiddenAppsViewModel
import io.mockk.coEvery
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

    private lateinit var fakeInstalledApps: FakeInstalledAppsRepository
    private lateinit var fakeVisibility: FakeHiddenAppsRepository

    private lateinit var viewModel: HiddenAppsViewModel

    private val app1 = AppInfo("App A", "App A", "pkg1", "class1")
    private val app2 = AppInfo("App B", "App B", "pkg2", "class2")
    private val app3 = AppInfo("App C", "App C", "pkg3", "class3")
    private val testApps = listOf(app1, app2, app3)

    @Before
    fun setup() {
        fakeInstalledApps = FakeInstalledAppsRepository()
        fakeVisibility = FakeHiddenAppsRepository()
    }

    /**
     * Builds the ViewModel with the project's standard wiring. Both repository
     * arguments default to the fakes from `@Before`. Failure-injection tests
     * pass a `mockk<Interface>(relaxed = true)` for the broken side because
     * `spyk` cannot intercept methods on a final fake class.
     */
    private fun setupViewModel(
        installedAppsRepo: InstalledAppsRepository = fakeInstalledApps,
        visibilityRepo: HiddenAppsRepository = fakeVisibility,
    ) {
        val getInstalledAppsUseCase = GetInstalledAppsUseCase(installedAppsRepo)
        val getHiddenAppsUseCase = GetHiddenAppsUseCase(visibilityRepo)
        val updateHiddenAppsUseCase = UpdateHiddenAppsUseCase(visibilityRepo)

        viewModel = HiddenAppsViewModel(
            getInstalledAppsUseCase,
            getHiddenAppsUseCase,
            updateHiddenAppsUseCase,
            mainDispatcher = mainDispatcherRule.testDispatcher,
        )
    }

    // ========== EXISTING TESTS ==========

    @Test
    fun `initialize - loads all apps and pre-selects hidden apps`() = runTest {
        fakeInstalledApps.installedApps = testApps
        fakeVisibility.hiddenApps = setOf(app2.componentName)

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
        fakeInstalledApps.installedApps = testApps
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
        fakeInstalledApps.installedApps = testApps
        fakeVisibility.hiddenApps = setOf(app1.componentName)
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
        fakeInstalledApps.installedApps = testApps
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
        fakeInstalledApps.installedApps = testApps
        fakeVisibility.hiddenApps = setOf(app1.componentName)
        setupViewModel()

        viewModel.initialize()
        advanceUntilIdle()

        // App1 wird sichtbar gemacht, App3 wird versteckt.
        viewModel.onAppToggled(app1)
        viewModel.onAppToggled(app3)
        advanceUntilIdle()

        viewModel.event.test {
            viewModel.onDoneClicked()
            advanceUntilIdle()

            // The fake's updateComponentVisibilities applies the (hide,show)
            // delta atomically — assert the resulting set instead of mock
            // call args.
            assertEquals(setOf(app3.componentName), fakeVisibility.hiddenApps)

            assertEquals(UiEvent.NavigateUp, awaitItem())
        }
    }

    @Test
    fun `initialize - when loading fails - emits error event`() = runTest {
        // Mock the interface for the failure path; the rest of the test
        // class uses the fake.
        val brokenInstalled = mockk<InstalledAppsRepository>(relaxed = true) {
            every { getInstalledApps() } returns flow { throw IOException("DB error") }
        }

        setupViewModel(installedAppsRepo = brokenInstalled)

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
        fakeInstalledApps.installedApps = testApps
        val brokenVisibility = mockk<HiddenAppsRepository>(relaxed = true) {
            every { hiddenAppsFlow } returns flow {
                throw IOException("Cannot read hidden apps")
            }
        }

        setupViewModel(visibilityRepo = brokenVisibility)

        viewModel.event.test {
            viewModel.initialize()
            advanceUntilIdle()

            val event = awaitItem()
            assertTrue(event is UiEvent.ShowToast)
        }
    }

    @Test
    fun `initialize - when both flows fail - emits error event`() = runTest {
        val brokenInstalled = mockk<InstalledAppsRepository>(relaxed = true) {
            every { getInstalledApps() } returns flow {
                throw RuntimeException("Database corrupted")
            }
        }
        val brokenVisibility = mockk<HiddenAppsRepository>(relaxed = true) {
            every { hiddenAppsFlow } returns flow {
                throw IOException("Cannot read")
            }
        }

        setupViewModel(
            installedAppsRepo = brokenInstalled,
            visibilityRepo = brokenVisibility,
        )

        viewModel.event.test {
            viewModel.initialize()
            advanceUntilIdle()

            val event = awaitItem()
            assertTrue(event is UiEvent.ShowToast)
        }
    }


    @Test
    fun `onDoneClicked - when visibility update fails - still navigates up`() = runTest {
        fakeInstalledApps.installedApps = testApps
        val brokenVisibility = mockk<HiddenAppsRepository>(relaxed = true) {
            // Default empty hiddenAppsFlow so initialize() succeeds; the
            // failure is in the write path.
            every { hiddenAppsFlow } returns flowOf(emptySet())
            coEvery {
                updateComponentVisibilities(any(), any())
            } throws IOException("DataStore write failed")
        }
        setupViewModel(visibilityRepo = brokenVisibility)

        viewModel.initialize()
        advanceUntilIdle()

        viewModel.onAppToggled(app1)
        advanceUntilIdle()

        viewModel.event.test {
            viewModel.onDoneClicked()
            advanceUntilIdle()

            // The ViewModel must catch and still navigate up.
            assertEquals(UiEvent.NavigateUp, awaitItem())
        }
    }

    @Test
    fun `onSearchQueryChanged - with empty query - shows all apps`() = runTest {
        fakeInstalledApps.installedApps = testApps
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
        fakeInstalledApps.installedApps = testApps
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
        fakeInstalledApps.installedApps = testApps
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
    fun `onAppToggled - with arbitrary unknown app - does not crash`() = runTest {
        fakeInstalledApps.installedApps = testApps
        setupViewModel()

        viewModel.initialize()
        advanceUntilIdle()

        // An app the VM never saw during initialize.
        val unknownApp = AppInfo("Unknown", "Unknown", "pkg.unknown", "class.unknown")
        viewModel.onAppToggled(unknownApp)
        advanceUntilIdle()

        val uiState = viewModel.uiState.value
        assertNotNull(uiState)
    }

    @Test
    fun `onDoneClicked - with no changes - navigates up without touching the repository`() = runTest {
        fakeInstalledApps.installedApps = testApps
        setupViewModel()

        viewModel.initialize()
        advanceUntilIdle()

        // Snapshot the fake before the action — no toggles happened, so the
        // VM must not call updateComponentVisibilities. That would mutate
        // hiddenApps; we assert it stays empty.
        val before = fakeVisibility.hiddenApps

        viewModel.event.test {
            viewModel.onDoneClicked()
            advanceUntilIdle()

            assertEquals(before, fakeVisibility.hiddenApps)

            assertEquals(UiEvent.NavigateUp, awaitItem())
        }
    }

    @Test
    fun `initialize - with empty app list - creates empty UI state`() = runTest {
        // Both fakes already start empty; nothing to seed.
        setupViewModel()

        viewModel.initialize()
        advanceUntilIdle()

        val uiState = viewModel.uiState.value
        assertTrue(uiState.selectableApps.isEmpty())
    }

    @Test
    fun `initialize - with all apps hidden - all apps pre-selected`() = runTest {
        fakeInstalledApps.installedApps = testApps
        fakeVisibility.hiddenApps = testApps.map { it.componentName }.toSet()

        setupViewModel()

        viewModel.initialize()
        advanceUntilIdle()

        val uiState = viewModel.uiState.value
        assertTrue(uiState.selectableApps.all { it.isSelected })
    }

    @Test
    fun `onSearchQueryChanged - rapid query changes - handles correctly`() = runTest {
        fakeInstalledApps.installedApps = testApps
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
        fakeInstalledApps.installedApps = testApps
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
        fakeInstalledApps.installedApps = testApps
        val brokenVisibility = mockk<HiddenAppsRepository>(relaxed = true) {
            every { hiddenAppsFlow } returns flowOf(emptySet())
            coEvery {
                updateComponentVisibilities(any(), any())
            } throws IOException("Write failed")
        }

        setupViewModel(visibilityRepo = brokenVisibility)

        viewModel.initialize()
        advanceUntilIdle()

        viewModel.onAppToggled(app1)
        advanceUntilIdle()

        viewModel.event.test {
            viewModel.onDoneClicked()
            advanceUntilIdle()

            // Should still navigate despite errors.
            assertEquals(UiEvent.NavigateUp, awaitItem())
        }
    }
}
