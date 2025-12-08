package com.github.reygnn.kolibri_launcher.ui

import androidx.arch.core.executor.testing.InstantTaskExecutorRule
import app.cash.turbine.test
import com.github.reygnn.kolibri_launcher.core.MainDispatcherRule
import com.github.reygnn.kolibri_launcher.R
import com.github.reygnn.kolibri_launcher.core.AppConstants
import com.github.reygnn.kolibri_launcher.domain.model.AppInfo
import com.github.reygnn.kolibri_launcher.domain.usecase.CompleteOnboardingUseCase
import com.github.reygnn.kolibri_launcher.domain.usecase.GetFavoriteComponentsUseCase
import com.github.reygnn.kolibri_launcher.domain.usecase.GetOnboardingAppsUseCase
import com.github.reygnn.kolibri_launcher.rules.TimberRule
import com.github.reygnn.kolibri_launcher.ui.onboarding.LaunchMode
import com.github.reygnn.kolibri_launcher.ui.onboarding.OnboardingEvent
import com.github.reygnn.kolibri_launcher.ui.onboarding.OnboardingViewModel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.StandardTestDispatcher
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
import org.mockito.kotlin.times
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
    val instantExecutorRule = InstantTaskExecutorRule()
    @get:Rule
    val timberRule = TimberRule()

    // UseCases als Mocks
    @Mock
    private lateinit var onboardingAppsUseCase: GetOnboardingAppsUseCase
    @Mock
    private lateinit var getFavoriteComponentsUseCase: GetFavoriteComponentsUseCase
    @Mock
    private lateinit var completeOnboardingUseCase: CompleteOnboardingUseCase

    private lateinit var viewModel: OnboardingViewModel

    private val app1 = AppInfo("App 1", "App 1", "pkg1", "class1")
    private val app2 = AppInfo("App 2", "App 2", "pkg2", "class2")
    private val app3 = AppInfo("App 3", "App 3", "pkg3", "class3")
    private val testApps = listOf(app1, app2, app3)

    @Before
    fun setup() {
        MockitoAnnotations.openMocks(this)
        // Default behavior für Apps Flow
        whenever(onboardingAppsUseCase.onboardingAppsFlow).thenReturn(flowOf(testApps))
    }

    private fun setupViewModel() {
        viewModel = OnboardingViewModel(
            onboardingAppsUseCase,
            getFavoriteComponentsUseCase,
            completeOnboardingUseCase,
            mainDispatcher = mainDispatcherRule.testDispatcher
        )
    }

    // ========== TESTS (ANGEPASST AN USECASES) ==========

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

    @Test
    fun `onDoneClicked - in INITIAL_SETUP mode - calls CompleteOnboardingUseCase correctly`() =
        runTest {
            setupViewModel()
            viewModel.setLaunchMode(LaunchMode.INITIAL_SETUP)
            viewModel.loadInitialData()
            advanceUntilIdle()

            viewModel.onAppToggled(testApps[0])
            advanceUntilIdle()

            viewModel.onDoneClicked()
            advanceUntilIdle()

            // PRÜFE DEN USECASE AUFRUF
            verify(completeOnboardingUseCase).invoke(
                componentNames = listOf(app1.componentName),
                isInitialSetup = true
            )
        }

    @Test
    fun `onDoneClicked - in EDIT_FAVORITES mode - calls CompleteOnboardingUseCase correctly`() =
        runTest {
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

            // PRÜFE DEN USECASE AUFRUF
            verify(completeOnboardingUseCase).invoke(
                componentNames = listOf(app3.componentName),
                isInitialSetup = false
            )
        }

    @Test
    fun `onDoneClicked - when CompleteOnboardingUseCase fails - emits error event`() = runTest {
        // Mocke den UseCase, damit er einen Fehler wirft
        whenever(
            completeOnboardingUseCase.invoke(
                any(),
                any()
            )
        ).thenThrow(RuntimeException("Speichern fehlgeschlagen"))

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

    @Test
    fun `onAppToggled - whenLimitReached - emitsToastEventAndDoesNotSelectApp`() = runTest {
        val limit = AppConstants.MAX_FALLBACK_FAVORITES_ON_HOME
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

    // ========== CRASH-RESISTANCE TESTS ==========

    @Test
    fun `init - when onboardingAppsFlow fails - handles gracefully and emits error`() = runTest {
        // 1. Mock Setup
        whenever(onboardingAppsUseCase.onboardingAppsFlow).thenReturn(flow {
            throw IOException("Cannot load apps")
        })

        val testDispatcher = StandardTestDispatcher(testScheduler)

        // 3. ViewModel initialisieren
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
    fun `initialize - in EDIT_FAVORITES mode when GetFavoriteComponentsUseCase fails - handles gracefully`() =
        runTest {
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
    fun `onDoneClicked - when CompleteOnboardingUseCase throws IOException - emits error`() =
        runTest {
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
    fun `onDoneClicked - when CompleteOnboardingUseCase throws on settings - still saves favorites`() =
        runTest {
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
    fun `onDoneClicked - in EDIT_FAVORITES after removing all favorites - calls UseCase with empty list`() =
        runTest {
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

    @Test
    fun `onSearchQueryChanged - filters apps correctly`() = runTest {
        setupViewModel()
        advanceUntilIdle()

        viewModel.onSearchQueryChanged("App 2")
        advanceUntilIdle()

        val uiState = viewModel.uiState.value
        assertEquals(1, uiState.selectableApps.size)
        assertEquals("App 2", uiState.selectableApps[0].appInfo.displayName)
    }

    @Test
    fun `onSearchQueryChanged - with empty query - shows all apps`() = runTest {
        setupViewModel()
        advanceUntilIdle()

        viewModel.onSearchQueryChanged("App 1")
        advanceUntilIdle()

        viewModel.onSearchQueryChanged("")
        advanceUntilIdle()

        val uiState = viewModel.uiState.value
        assertEquals(3, uiState.selectableApps.size)
    }

    @Test
    fun `onSearchQueryChanged - case insensitive search works`() = runTest {
        setupViewModel()
        advanceUntilIdle()

        viewModel.onSearchQueryChanged("aPp 3")
        advanceUntilIdle()

        val uiState = viewModel.uiState.value
        assertEquals(1, uiState.selectableApps.size)
        assertEquals("App 3", uiState.selectableApps[0].appInfo.displayName)
    }

    @Test
    fun `onSearchQueryChanged - no match - shows empty list`() = runTest {
        setupViewModel()
        advanceUntilIdle()

        viewModel.onSearchQueryChanged("NonExistent")
        advanceUntilIdle()

        val uiState = viewModel.uiState.value
        assertTrue(uiState.selectableApps.isEmpty())
    }

    @Test
    fun `onSearchQueryChanged - selection persists across search changes`() = runTest {
        setupViewModel()
        advanceUntilIdle()

        // Select app1
        viewModel.onAppToggled(app1)
        advanceUntilIdle()

        // Search for something else
        viewModel.onSearchQueryChanged("App 2")
        advanceUntilIdle()

        // Clear search - app1 should still be selected
        viewModel.onSearchQueryChanged("")
        advanceUntilIdle()

        val uiState = viewModel.uiState.value
        assertTrue(uiState.selectableApps.find { it.appInfo.packageName == "pkg1" }!!.isSelected)
    }

    @Test
    fun `setLaunchMode - INITIAL_SETUP - sets correct title and subtitle`() = runTest {
        setupViewModel()

        viewModel.setLaunchMode(LaunchMode.INITIAL_SETUP)
        advanceUntilIdle()

        val uiState = viewModel.uiState.value
        assertEquals(R.string.onboarding_title_welcome, uiState.titleResId)
        assertEquals(R.string.onboarding_subtitle_welcome, uiState.subtitleResId)
    }

    @Test
    fun `setLaunchMode - EDIT_FAVORITES - sets correct title and subtitle`() = runTest {
        setupViewModel()

        viewModel.setLaunchMode(LaunchMode.EDIT_FAVORITES)
        advanceUntilIdle()

        val uiState = viewModel.uiState.value
        assertEquals(R.string.onboarding_title_edit_favorites, uiState.titleResId)
        assertEquals(R.string.onboarding_subtitle_edit_favorites, uiState.subtitleResId)
    }

    @Test
    fun `setLaunchMode - can be changed multiple times`() = runTest {
        setupViewModel()

        viewModel.setLaunchMode(LaunchMode.INITIAL_SETUP)
        advanceUntilIdle()
        assertEquals(R.string.onboarding_title_welcome, viewModel.uiState.value.titleResId)

        viewModel.setLaunchMode(LaunchMode.EDIT_FAVORITES)
        advanceUntilIdle()
        assertEquals(R.string.onboarding_title_edit_favorites, viewModel.uiState.value.titleResId)
    }

    @Test
    fun `loadInitialData - called multiple times - only loads once`() = runTest {
        whenever(getFavoriteComponentsUseCase.invoke()).thenReturn(emptySet())

        setupViewModel()
        viewModel.setLaunchMode(LaunchMode.EDIT_FAVORITES)

        viewModel.loadInitialData()
        viewModel.loadInitialData()
        viewModel.loadInitialData()
        advanceUntilIdle()

        // UseCase should only be called once
        verify(getFavoriteComponentsUseCase, atLeastOnce()).invoke()
    }

    @Test
    fun `onAppToggled - rapid toggles on same app - handles correctly`() = runTest {
        setupViewModel()
        advanceUntilIdle()

        repeat(10) {
            viewModel.onAppToggled(app1)
        }
        advanceUntilIdle()

        val uiState = viewModel.uiState.value
        // Should be unselected (even number of toggles)
        assertFalse(uiState.selectableApps.find { it.appInfo.packageName == "pkg1" }!!.isSelected)
    }

    @Test
    fun `onAppToggled - rapid toggles on different apps - handles correctly`() = runTest {
        setupViewModel()
        advanceUntilIdle()

        viewModel.onAppToggled(app1)
        viewModel.onAppToggled(app2)
        viewModel.onAppToggled(app3)
        advanceUntilIdle()

        val uiState = viewModel.uiState.value
        assertTrue(uiState.selectableApps.find { it.appInfo.packageName == "pkg1" }!!.isSelected)
        assertTrue(uiState.selectableApps.find { it.appInfo.packageName == "pkg2" }!!.isSelected)
        assertTrue(uiState.selectableApps.find { it.appInfo.packageName == "pkg3" }!!.isSelected)
    }

    @Test
    fun `onDoneClicked - called multiple times rapidly - only executes once per call`() = runTest {
        setupViewModel()
        advanceUntilIdle()

        viewModel.onAppToggled(app1)
        advanceUntilIdle()

        repeat(3) {
            viewModel.onDoneClicked()
        }
        advanceUntilIdle()

        // Should be called 3 times (not protected against multiple calls)
        verify(completeOnboardingUseCase, atLeastOnce()).invoke(any(), any())
    }

    @Test
    fun `onAppToggled - when at limit minus one - allows one more selection`() = runTest {
        val limit = AppConstants.MAX_FALLBACK_FAVORITES_ON_HOME
        val manyApps = (1..limit).map {
            AppInfo("App $it", "App $it", "pkg$it", "class$it")
        }
        whenever(onboardingAppsUseCase.onboardingAppsFlow).thenReturn(flowOf(manyApps))

        setupViewModel()
        advanceUntilIdle()

        // Select limit - 1 apps
        for (i in 0 until limit - 1) {
            viewModel.onAppToggled(manyApps[i])
        }
        advanceUntilIdle()

        viewModel.event.test {
            // This should succeed without toast
            viewModel.onAppToggled(manyApps[limit - 1])
            advanceUntilIdle()

            expectNoEvents() // No limit toast!

            val uiState = viewModel.uiState.value
            assertEquals(limit, uiState.selectedApps.size)
        }
    }

    @Test
    fun `selectedApps - are always sorted alphabetically`() = runTest {
        setupViewModel()
        advanceUntilIdle()

        // Select in reverse order
        viewModel.onAppToggled(app3)
        viewModel.onAppToggled(app1)
        viewModel.onAppToggled(app2)
        advanceUntilIdle()

        val uiState = viewModel.uiState.value
        assertEquals("App 1", uiState.selectedApps[0].displayName)
        assertEquals("App 2", uiState.selectedApps[1].displayName)
        assertEquals("App 3", uiState.selectedApps[2].displayName)
    }

    @Test
    fun `onDoneClicked - emits NavigateToMain event on success`() = runTest {
        setupViewModel()
        advanceUntilIdle()

        viewModel.onAppToggled(app1)
        advanceUntilIdle()

        viewModel.event.test {
            viewModel.onDoneClicked()
            advanceUntilIdle()

            val event = awaitItem()
            assertTrue(event is OnboardingEvent.NavigateToMain)
        }
    }

    @Test
    fun `onAppToggled - while searching - updates both filtered and selected lists`() = runTest {
        setupViewModel()
        advanceUntilIdle()

        viewModel.onSearchQueryChanged("App 2")
        advanceUntilIdle()

        viewModel.onAppToggled(app2)
        advanceUntilIdle()

        val uiState = viewModel.uiState.value
        assertEquals(1, uiState.selectableApps.size) // Filtered
        assertEquals(1, uiState.selectedApps.size)    // Selected
        assertTrue(uiState.selectableApps[0].isSelected)
    }

    @Test
    fun `onAppToggled - selected app disappears from filtered list when search changes`() =
        runTest {
            setupViewModel()
            advanceUntilIdle()

            viewModel.onAppToggled(app1)
            advanceUntilIdle()

            // Search for something else - app1 not in filtered list
            viewModel.onSearchQueryChanged("App 2")
            advanceUntilIdle()

            val uiState = viewModel.uiState.value
            assertEquals(1, uiState.selectableApps.size) // Only App 2 visible
            assertEquals(1, uiState.selectedApps.size)    // But App 1 still selected!
            assertEquals("App 1", uiState.selectedApps[0].displayName)
        }

    @Test
    fun `onAppToggled - selecting exactly at limit - works without toast`() = runTest {
        val limit = AppConstants.MAX_FALLBACK_FAVORITES_ON_HOME
        val exactApps = (1..limit).map {
            AppInfo("App $it", "App $it", "pkg$it", "class$it")
        }
        whenever(onboardingAppsUseCase.onboardingAppsFlow).thenReturn(flowOf(exactApps))

        setupViewModel()
        advanceUntilIdle()

        // Select all apps up to limit
        exactApps.forEach { app ->
            viewModel.onAppToggled(app)
        }
        advanceUntilIdle()

        val uiState = viewModel.uiState.value
        assertEquals(limit, uiState.selectedApps.size)
    }

    @Test
    fun `onSearchQueryChanged - with whitespace only - treats as empty`() = runTest {
        setupViewModel()
        advanceUntilIdle()

        viewModel.onSearchQueryChanged("   ")
        advanceUntilIdle()

        val uiState = viewModel.uiState.value
        assertEquals(3, uiState.selectableApps.size) // All apps shown
    }

    @Test
    fun `selectedApps - reflects actual selection state in selectableApps`() = runTest {
        setupViewModel()
        advanceUntilIdle()

        viewModel.onAppToggled(app1)
        viewModel.onAppToggled(app3)
        advanceUntilIdle()

        val uiState = viewModel.uiState.value

        // Check selectedApps
        assertEquals(2, uiState.selectedApps.size)

        // Check that selectableApps matches
        assertTrue(uiState.selectableApps.find { it.appInfo.packageName == "pkg1" }!!.isSelected)
        assertFalse(uiState.selectableApps.find { it.appInfo.packageName == "pkg2" }!!.isSelected)
        assertTrue(uiState.selectableApps.find { it.appInfo.packageName == "pkg3" }!!.isSelected)
    }

    @Test
    fun `loadInitialData - in INITIAL_SETUP mode - starts with empty selection`() = runTest {
        setupViewModel()
        viewModel.setLaunchMode(LaunchMode.INITIAL_SETUP)
        viewModel.loadInitialData()
        advanceUntilIdle()

        val uiState = viewModel.uiState.value
        assertTrue(uiState.selectedApps.isEmpty())
        assertTrue(uiState.selectableApps.all { !it.isSelected })
    }

    @Test
    fun `onDoneClicked - after error - can retry successfully`() = runTest {
        whenever(completeOnboardingUseCase.invoke(any(), any()))
            .thenThrow(RuntimeException("Network error"))
            .thenReturn(Unit)

        setupViewModel()
        advanceUntilIdle()

        viewModel.onAppToggled(app1)
        advanceUntilIdle()

        viewModel.event.test {
            viewModel.onDoneClicked()
            advanceUntilIdle()

            val errorEvent = awaitItem()
            assertTrue(errorEvent is OnboardingEvent.ShowError)

            viewModel.onDoneClicked()
            advanceUntilIdle()

            val successEvent = awaitItem()
            assertTrue(successEvent is OnboardingEvent.NavigateToMain)

            verify(completeOnboardingUseCase, times(2)).invoke(any(), any())
        }
    }

}