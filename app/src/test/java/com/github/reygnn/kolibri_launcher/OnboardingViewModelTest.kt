package com.github.reygnn.kolibri_launcher

import app.cash.turbine.test
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.mockito.Mock
import org.mockito.MockitoAnnotations
import org.mockito.kotlin.any
import org.mockito.kotlin.atLeastOnce
import org.mockito.kotlin.doAnswer
import org.mockito.kotlin.never
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import java.io.IOException
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

@ExperimentalCoroutinesApi
class OnboardingViewModelTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    @Mock private lateinit var onboardingAppsUseCase: GetOnboardingAppsUseCaseRepository
    @Mock private lateinit var favoritesRepository: FavoritesRepository
    @Mock private lateinit var settingsRepository: SettingsRepository

    private lateinit var viewModel: OnboardingViewModel

    private val app1 = AppInfo("App 1", "App 1", "pkg1", "class1")
    private val app2 = AppInfo("App 2", "App 2", "pkg2", "class2")
    private val app3 = AppInfo("App 3", "App 3", "pkg3", "class3")
    private val testApps = listOf(app1, app2, app3)

    @Before
    fun setup() {
        MockitoAnnotations.openMocks(this)
        whenever(onboardingAppsUseCase.onboardingAppsFlow).thenReturn(flowOf(testApps))
    }

    private fun setupViewModel() {
        viewModel = OnboardingViewModel(onboardingAppsUseCase, favoritesRepository, settingsRepository)
    }

    // ========== EXISTING TESTS ==========

    @Test
    fun `init - loads apps and creates initial state correctly`() = runTest {
        setupViewModel()
        advanceUntilIdle()

        val uiState = viewModel.uiState.value
        assertEquals(3, uiState.selectableApps.size)
        assertEquals("App 1", uiState.selectableApps[0].appInfo.displayName)
        assertFalse(uiState.selectableApps[0].isSelected)
    }

    @Test
    fun `onAppToggled - adds app to selection correctly`() = runTest {
        setupViewModel()
        advanceUntilIdle()

        viewModel.onAppToggled(testApps[1])
        advanceUntilIdle()

        val uiState = viewModel.uiState.value
        assertFalse(uiState.selectableApps.find { it.appInfo.packageName == "pkg1" }!!.isSelected)
        assertTrue(uiState.selectableApps.find { it.appInfo.packageName == "pkg2" }!!.isSelected)
        assertFalse(uiState.selectableApps.find { it.appInfo.packageName == "pkg3" }!!.isSelected)
    }

    @Test
    fun `onAppToggled - removes app from selection when toggled twice`() = runTest {
        setupViewModel()
        advanceUntilIdle()

        viewModel.onAppToggled(testApps[1])
        advanceUntilIdle()

        viewModel.onAppToggled(testApps[1])
        advanceUntilIdle()

        val uiState = viewModel.uiState.value
        assertFalse(uiState.selectableApps.find { it.appInfo.packageName == "pkg2" }!!.isSelected)
    }

    @Test
    fun `onDoneClicked - in INITIAL_SETUP mode - saves components and sets onboarding completed`() = runTest {
        setupViewModel()
        viewModel.initialize(LaunchMode.INITIAL_SETUP)
        advanceUntilIdle()

        viewModel.onAppToggled(testApps[0])
        advanceUntilIdle()

        viewModel.onDoneClicked()
        advanceUntilIdle()

        verify(favoritesRepository).saveFavoriteComponents(listOf(app1.componentName))
        verify(settingsRepository).setOnboardingCompleted()
    }

    @Test
    fun `onDoneClicked - in EDIT_FAVORITES mode - only saves components`() = runTest {
        whenever(favoritesRepository.favoriteComponentsFlow).thenReturn(flowOf(emptySet()))
        setupViewModel()
        viewModel.initialize(LaunchMode.EDIT_FAVORITES)
        advanceUntilIdle()

        viewModel.onAppToggled(testApps[2])
        advanceUntilIdle()

        viewModel.onDoneClicked()
        advanceUntilIdle()

        verify(favoritesRepository).saveFavoriteComponents(listOf(app3.componentName))
        verify(settingsRepository, never()).setOnboardingCompleted()
    }

    @Test
    fun `onDoneClicked - when saving fails - emits error event`() = runTest {
        whenever(favoritesRepository.saveFavoriteComponents(any())).thenThrow(RuntimeException("Speichern fehlgeschlagen"))

        setupViewModel()
        advanceUntilIdle()

        viewModel.onAppToggled(testApps[0])
        advanceUntilIdle()

        viewModel.event.test {
            viewModel.onDoneClicked()

            val event = awaitItem()
            assertTrue(event is OnboardingEvent.ShowError)
            assertEquals("Save failed. Please try again.", event.message)
            verify(settingsRepository, never()).setOnboardingCompleted()
        }
    }

    @Test
    fun `onAppToggled - whenLimitReached - emitsToastEventAndDoesNotSelectApp`() = runTest {
        val limit = AppConstants.MAX_FAVORITES_ON_HOME
        val appsOverLimit = (1..(limit + 1)).map {
            AppInfo("App $it", "App $it", "pkg$it", "class$it")
        }
        whenever(onboardingAppsUseCase.onboardingAppsFlow).thenReturn(flowOf(appsOverLimit))
        setupViewModel()
        advanceUntilIdle()

        for (i in 0 until limit) {
            viewModel.onAppToggled(appsOverLimit[i])
        }
        advanceUntilIdle()

        var currentState = viewModel.uiState.value
        assertEquals(limit, currentState.selectedApps.size)

        viewModel.event.test {
            viewModel.onAppToggled(appsOverLimit[limit])
            advanceUntilIdle()

            val event = awaitItem()
            assertTrue(event is OnboardingEvent.ShowLimitReachedToast)
            assertEquals(limit, event.limit)

            currentState = viewModel.uiState.value
            assertEquals(limit, currentState.selectedApps.size)

            val lastAppSelectableInfo = currentState.selectableApps.find { it.appInfo.packageName == "pkg${limit + 1}" }
            assertFalse(lastAppSelectableInfo!!.isSelected)
        }
    }

    // ========== NEW CRASH-RESISTANCE TESTS ==========

    @Test
    fun `init - when onboardingAppsFlow fails - emits error event`() = runTest {
        whenever(onboardingAppsUseCase.onboardingAppsFlow).thenReturn(flow {
            throw IOException("Cannot load apps")
        })

        setupViewModel()

        viewModel.event.test {
            advanceUntilIdle()

            val event = awaitItem()
            assertTrue(event is OnboardingEvent.ShowError)
        }
    }

    @Test
    fun `init - when onboardingAppsFlow emits empty list - creates empty state`() = runTest {
        whenever(onboardingAppsUseCase.onboardingAppsFlow).thenReturn(flowOf(emptyList()))

        setupViewModel()
        advanceUntilIdle()

        val uiState = viewModel.uiState.value
        assertTrue(uiState.selectableApps.isEmpty())
    }

    @Test
    fun `initialize - in EDIT_FAVORITES mode when favoriteComponentsFlow fails - handles gracefully`() = runTest {
        whenever(favoritesRepository.favoriteComponentsFlow).thenReturn(flow {
            throw IOException("Cannot read favorites")
        })

        setupViewModel()

        viewModel.event.test {
            viewModel.initialize(LaunchMode.EDIT_FAVORITES)
            advanceUntilIdle()

            val event = awaitItem()
            assertTrue(event is OnboardingEvent.ShowError)
        }
    }

    @Test
    fun `onDoneClicked - when saveFavoriteComponents throws IOException - emits error`() = runTest {
        whenever(favoritesRepository.saveFavoriteComponents(any())).doAnswer {
            throw IOException("Disk full")
        }

        setupViewModel()
        advanceUntilIdle()

        viewModel.onAppToggled(testApps[0])
        advanceUntilIdle()

        viewModel.event.test {
            viewModel.onDoneClicked()

            val event = awaitItem()
            assertTrue(event is OnboardingEvent.ShowError)
        }
    }

    @Test
    fun `onDoneClicked - when setOnboardingCompleted throws exception - still saves favorites`() = runTest {
        whenever(settingsRepository.setOnboardingCompleted()).doAnswer {
            throw IOException("Cannot write settings")
        }

        setupViewModel()
        viewModel.initialize(LaunchMode.INITIAL_SETUP)
        advanceUntilIdle()

        viewModel.onAppToggled(testApps[0])
        advanceUntilIdle()

        viewModel.event.test {
            viewModel.onDoneClicked()
            advanceUntilIdle()

            verify(favoritesRepository).saveFavoriteComponents(listOf(app1.componentName))

            val event = awaitItem()
            assertTrue(event is OnboardingEvent.ShowError)
        }
    }

    @Test
    fun `onDoneClicked - with no apps selected - saves empty list`() = runTest {
        setupViewModel()
        advanceUntilIdle()

        viewModel.onDoneClicked()
        advanceUntilIdle()

        verify(favoritesRepository).saveFavoriteComponents(emptyList())
    }

    @Test
    fun `onAppToggled - rapid toggles - handles correctly`() = runTest {
        setupViewModel()
        advanceUntilIdle()

        repeat(5) {
            viewModel.onAppToggled(testApps[0])
        }
        advanceUntilIdle()

        assertNotNull(viewModel)
    }

    @Test
    fun `onAppToggled - rapid toggles on same app - handles correctly`() = runTest {
        setupViewModel()
        advanceUntilIdle()

        // Toggle 10 times rapidly
        repeat(10) {
            viewModel.onAppToggled(testApps[0])
        }
        advanceUntilIdle()

        val uiState = viewModel.uiState.value
        val app1State = uiState.selectableApps.find { it.appInfo.packageName == "pkg1" }!!

        // Even number of toggles = not selected
        assertFalse(app1State.isSelected)
    }

    @Test
    fun `initialize - called multiple times - handles correctly`() = runTest {
        setupViewModel()
        advanceUntilIdle()

        viewModel.initialize(LaunchMode.INITIAL_SETUP)
        advanceUntilIdle()

        viewModel.initialize(LaunchMode.EDIT_FAVORITES)
        advanceUntilIdle()

        // Should not crash
        val uiState = viewModel.uiState.value
        assertNotNull(uiState)
    }

    @Test
    fun `onDoneClicked - called multiple times - handles correctly`() = runTest {
        setupViewModel()
        advanceUntilIdle()

        viewModel.onAppToggled(testApps[0])
        advanceUntilIdle()

        viewModel.onDoneClicked()
        advanceUntilIdle()

        viewModel.onDoneClicked()
        advanceUntilIdle()

        // Should handle multiple calls - verify it was called at least once
        verify(favoritesRepository, atLeastOnce()).saveFavoriteComponents(any())
    }

    @Test
    fun `init - with very large app list - handles efficiently`() = runTest {
        val largeAppList = (1..1000).map {
            AppInfo("App $it", "App $it", "pkg$it", "class$it")
        }
        whenever(onboardingAppsUseCase.onboardingAppsFlow).thenReturn(flowOf(largeAppList))

        setupViewModel()
        advanceUntilIdle()

        val uiState = viewModel.uiState.value
        assertEquals(1000, uiState.selectableApps.size)
    }

    @Test
    fun `onAppToggled - selecting up to MAX_FAVORITES - all succeed`() = runTest {
        val limit = AppConstants.MAX_FAVORITES_ON_HOME
        val exactLimitApps = (1..limit).map {
            AppInfo("App $it", "App $it", "pkg$it", "class$it")
        }
        whenever(onboardingAppsUseCase.onboardingAppsFlow).thenReturn(flowOf(exactLimitApps))
        setupViewModel()
        advanceUntilIdle()

        for (i in 0 until limit) {
            viewModel.onAppToggled(exactLimitApps[i])
        }
        advanceUntilIdle()

        val uiState = viewModel.uiState.value
        assertEquals(limit, uiState.selectedApps.size)
    }

    @Test
    fun `initialize - EDIT_FAVORITES mode pre-selects existing favorites`() = runTest {
        val existingFavorites = setOf(app1.componentName, app3.componentName)
        whenever(favoritesRepository.favoriteComponentsFlow).thenReturn(flowOf(existingFavorites))

        setupViewModel()
        viewModel.initialize(LaunchMode.EDIT_FAVORITES)
        advanceUntilIdle()

        val uiState = viewModel.uiState.value
        assertTrue(uiState.selectableApps.find { it.appInfo.packageName == "pkg1" }!!.isSelected)
        assertFalse(uiState.selectableApps.find { it.appInfo.packageName == "pkg2" }!!.isSelected)
        assertTrue(uiState.selectableApps.find { it.appInfo.packageName == "pkg3" }!!.isSelected)
    }

    @Test
    fun `onDoneClicked - in EDIT_FAVORITES after removing all favorites - saves empty list`() = runTest {
        val existingFavorites = setOf(app1.componentName)
        whenever(favoritesRepository.favoriteComponentsFlow).thenReturn(flowOf(existingFavorites))

        setupViewModel()
        viewModel.initialize(LaunchMode.EDIT_FAVORITES)
        advanceUntilIdle()

        // Remove the only favorite
        viewModel.onAppToggled(app1)
        advanceUntilIdle()

        viewModel.onDoneClicked()
        advanceUntilIdle()

        verify(favoritesRepository).saveFavoriteComponents(emptyList())
    }
}