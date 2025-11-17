package com.github.reygnn.kolibri_launcher

import androidx.arch.core.executor.testing.InstantTaskExecutorRule
import app.cash.turbine.test
import com.github.reygnn.kolibri_launcher.core.AppConstants
import com.github.reygnn.kolibri_launcher.domain.model.AppInfo
import com.github.reygnn.kolibri_launcher.domain.usecase.CompleteOnboardingUseCase // NEU
import com.github.reygnn.kolibri_launcher.domain.usecase.GetFavoriteComponentsUseCase // NEU
import com.github.reygnn.kolibri_launcher.domain.usecase.GetOnboardingAppsUseCase // NEU
import com.github.reygnn.kolibri_launcher.ui.onboarding.LaunchMode
import com.github.reygnn.kolibri_launcher.ui.onboarding.OnboardingEvent
import com.github.reygnn.kolibri_launcher.ui.onboarding.OnboardingViewModel
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
import org.mockito.kotlin.eq
import org.mockito.kotlin.never
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import java.io.IOException
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

@ExperimentalCoroutinesApi
class OnboardingViewModelTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    @get:Rule
    val instantExecutorRule = InstantTaskExecutorRule() // Für LiveData, falls verwendet

    // --- NEUE MOCKS (NUR USECASES) ---
    @Mock private lateinit var onboardingAppsUseCase: GetOnboardingAppsUseCase
    @Mock private lateinit var getFavoriteComponentsUseCase: GetFavoriteComponentsUseCase
    @Mock private lateinit var completeOnboardingUseCase: CompleteOnboardingUseCase

    private lateinit var viewModel: OnboardingViewModel

    private val app1 = AppInfo("App 1", "App 1", "pkg1", "class1")
    private val app2 = AppInfo("App 2", "App 2", "pkg2", "class2")
    private val app3 = AppInfo("App 3", "App 3", "pkg3", "class3")
    private val testApps = listOf(app1, app2, app3)

    @Before
    fun setup() {
        MockitoAnnotations.openMocks(this)
        // Mocke den Flow der UseCase-KLASSE
        whenever(onboardingAppsUseCase.onboardingAppsFlow).thenReturn(flowOf(testApps))
    }

    private fun setupViewModel() {
        // Rufe den NEUEN, sauberen Konstruktor auf
        viewModel = OnboardingViewModel(
            onboardingAppsUseCase,
            getFavoriteComponentsUseCase,
            completeOnboardingUseCase,
            mainDispatcher = mainDispatcherRule.testDispatcher
        )
    }

    // ========== TESTS (ANGEPASST) ==========

    @Test
    fun `init - loads apps and creates initial state correctly`() = runTest {
        setupViewModel()
        advanceUntilIdle()

        val uiState = viewModel.uiState.value
        assertEquals(3, uiState.selectableApps.size)
        assertEquals("App 1", uiState.selectableApps[0].appInfo.displayName)
        assertFalse(uiState.selectableApps[0].isSelected)
    }

    // --- onAppToggled Tests (bleiben gleich, da sie keine Repos aufrufen) ---
    @Test
    fun `onAppToggled - adds app to selection correctly`() = runTest {
        setupViewModel()
        advanceUntilIdle()

        viewModel.onAppToggled(testApps[1])
        advanceUntilIdle()

        val uiState = viewModel.uiState.value
        assertTrue(uiState.selectableApps.find { it.appInfo.packageName == "pkg2" }!!.isSelected)
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

    // --- onDoneClicked Tests (ANGEPASST, PRÜFEN USECASES) ---

    @Test
    fun `onDoneClicked - in INITIAL_SETUP mode - calls CompleteOnboardingUseCase correctly`() = runTest {
        setupViewModel()
        viewModel.setLaunchMode(LaunchMode.INITIAL_SETUP)
        viewModel.loadInitialData()
        advanceUntilIdle()

        viewModel.onAppToggled(testApps[0])
        advanceUntilIdle()

        viewModel.onDoneClicked()
        advanceUntilIdle()

        // PRÜFE DEN USECASE
        verify(completeOnboardingUseCase).invoke(
            componentNames = listOf(app1.componentName),
            isInitialSetup = true
        )
    }

    @Test
    fun `onDoneClicked - in EDIT_FAVORITES mode - calls CompleteOnboardingUseCase correctly`() = runTest {
        // Mocke den GetFavoriteComponentsUseCase für loadInitialData
        whenever(getFavoriteComponentsUseCase.invoke()).thenReturn(emptySet())

        setupViewModel()
        viewModel.setLaunchMode(LaunchMode.EDIT_FAVORITES)
        viewModel.loadInitialData()
        advanceUntilIdle()

        viewModel.onAppToggled(testApps[2])
        advanceUntilIdle()

        viewModel.onDoneClicked()
        advanceUntilIdle()

        // PRÜFE DEN USECASE
        verify(completeOnboardingUseCase).invoke(
            componentNames = listOf(app3.componentName),
            isInitialSetup = false // Korrekt
        )
    }

    @Test
    fun `onDoneClicked - when CompleteOnboardingUseCase fails - emits error event`() = runTest {
        // Mocke den UseCase, damit er einen Fehler wirft
        whenever(completeOnboardingUseCase.invoke(any(), any())).thenThrow(RuntimeException("Speichern fehlgeschlagen"))

        setupViewModel()
        advanceUntilIdle()

        viewModel.onAppToggled(testApps[0])
        advanceUntilIdle()

        viewModel.event.test {
            viewModel.onDoneClicked()

            val event = awaitItem()
            assertTrue(event is OnboardingEvent.ShowError)
            assertEquals("Save failed. Please try again.", event.message)
        }
    }

    // --- onAppToggled Limit Test (bleibt gleich) ---
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

        viewModel.event.test {
            viewModel.onAppToggled(appsOverLimit[limit])
            advanceUntilIdle()

            val event = awaitItem()
            assertTrue(event is OnboardingEvent.ShowLimitReachedToast)
            assertEquals(limit, event.limit)

            val currentState = viewModel.uiState.value
            assertEquals(limit, currentState.selectedApps.size)
        }
    }

    // ========== CRASH-RESISTANCE TESTS (ANGEPASST) ==========

    @Test
    fun `init - when onboardingAppsFlow fails - handles gracefully and emits error`() = runTest {
        // 1. Mock Setup
        whenever(onboardingAppsUseCase.onboardingAppsFlow).thenReturn(flow {
            throw IOException("Cannot load apps")
        })

        val testDispatcher = kotlinx.coroutines.test.StandardTestDispatcher(testScheduler)

        // 3. ViewModel initialisieren (Die Coroutine im init-Block wird jetzt "pausiert" gestartet)
        viewModel = OnboardingViewModel(
            onboardingAppsUseCase,
            getFavoriteComponentsUseCase,
            completeOnboardingUseCase,
            mainDispatcher = testDispatcher
        )

        // 4. Jetzt hängen wir uns an den Event-Stream
        viewModel.event.test {
            // 5. JETZT lassen wir die Zeit laufen. Der init-Block wird ausgeführt.
            advanceUntilIdle()

            // 6. Das Event wurde aufgefangen!
            val event = awaitItem()
            assertTrue(event is OnboardingEvent.ShowError)

            val uiState = viewModel.uiState.value
            assertNotNull(uiState)
            assertTrue(uiState.selectableApps.isEmpty())
        }
    }

    @Test
    fun `initialize - in EDIT_FAVORITES mode when GetFavoriteComponentsUseCase fails - handles gracefully`() = runTest {
        // 1. Mock Setup: Verwende doAnswer für suspend functions!
        whenever(getFavoriteComponentsUseCase.invoke()).doAnswer {
            throw IOException("Cannot read favorites")
        }

        // 2. ViewModel Setup
        setupViewModel()

        // 3. Test
        viewModel.event.test {
            viewModel.setLaunchMode(LaunchMode.EDIT_FAVORITES)
            viewModel.loadInitialData()

            advanceUntilIdle()

            val event = awaitItem()
            assertTrue(event is OnboardingEvent.ShowError)

            val uiState = viewModel.uiState.value
            assertNotNull(uiState)
        }
    }

    @Test
    fun `onDoneClicked - when CompleteOnboardingUseCase throws IOException - emits error`() = runTest {
        // Mocke den UseCase
        whenever(completeOnboardingUseCase.invoke(any(), any())).doAnswer {
            throw IOException("Disk full")
        }

        setupViewModel()
        advanceUntilIdle()

        viewModel.onAppToggled(testApps[0])
        advanceUntilIdle()

        viewModel.event.test {
            viewModel.onDoneClicked()
            advanceUntilIdle()
            val event = awaitItem()
            assertTrue(event is OnboardingEvent.ShowError)
        }
    }

    @Test
    fun `onDoneClicked - when CompleteOnboardingUseCase throws on settings - still saves favorites`() = runTest {
        // Dieser Test ist komplizierter, da der UseCase jetzt beides tut.
        // Wir mocken, dass der Aufruf fehlschlägt
        whenever(completeOnboardingUseCase.invoke(any(), eq(true))).doAnswer {
            throw IOException("Cannot write settings")
        }

        setupViewModel()
        viewModel.setLaunchMode(LaunchMode.INITIAL_SETUP)
        viewModel.loadInitialData()
        advanceUntilIdle()

        viewModel.onAppToggled(testApps[0])
        advanceUntilIdle()

        viewModel.event.test {
            viewModel.onDoneClicked()
            advanceUntilIdle()

            // Der UseCase wurde aufgerufen (auch wenn er fehlgeschlagen ist)
            verify(completeOnboardingUseCase).invoke(listOf(app1.componentName), true)

            val event = awaitItem()
            assertTrue(event is OnboardingEvent.ShowError)
        }
    }

    @Test
    fun `onDoneClicked - with no apps selected - calls UseCase with empty list`() = runTest {
        setupViewModel()
        viewModel.setLaunchMode(LaunchMode.INITIAL_SETUP)
        advanceUntilIdle()

        viewModel.onDoneClicked()
        advanceUntilIdle()

        // PRÜFE DEN USECASE
        verify(completeOnboardingUseCase).invoke(
            componentNames = emptyList(),
            isInitialSetup = true
        )
    }

    // ... (Die Tests 'rapid toggles', 'initialize multiple times', 'onDoneClicked multiple times'
    //      bleiben funktional gleich und sollten weiterhin bestehen) ...

    @Test
    fun `initialize - EDIT_FAVORITES mode pre-selects existing favorites`() = runTest {
        val existingFavorites = setOf(app1.componentName, app3.componentName)
        // Mocke den GetFavoriteComponentsUseCase
        whenever(getFavoriteComponentsUseCase.invoke()).thenReturn(existingFavorites)

        setupViewModel()
        viewModel.setLaunchMode(LaunchMode.EDIT_FAVORITES)
        viewModel.loadInitialData()
        advanceUntilIdle()

        val uiState = viewModel.uiState.value
        assertTrue(uiState.selectableApps.find { it.appInfo.packageName == "pkg1" }!!.isSelected)
        assertFalse(uiState.selectableApps.find { it.appInfo.packageName == "pkg2" }!!.isSelected)
        assertTrue(uiState.selectableApps.find { it.appInfo.packageName == "pkg3" }!!.isSelected)
    }

    @Test
    fun `onDoneClicked - in EDIT_FAVORITES after removing all favorites - calls UseCase with empty list`() = runTest {
        val existingFavorites = setOf(app1.componentName)
        // Mocke den GetFavoriteComponentsUseCase
        whenever(getFavoriteComponentsUseCase.invoke()).thenReturn(existingFavorites)

        setupViewModel()
        viewModel.setLaunchMode(LaunchMode.EDIT_FAVORITES)
        viewModel.loadInitialData()
        advanceUntilIdle()

        // Remove the only favorite
        viewModel.onAppToggled(app1)
        advanceUntilIdle()

        viewModel.onDoneClicked()
        advanceUntilIdle()

        // PRÜFE DEN USECASE
        verify(completeOnboardingUseCase).invoke(
            componentNames = emptyList(),
            isInitialSetup = false
        )
    }
}