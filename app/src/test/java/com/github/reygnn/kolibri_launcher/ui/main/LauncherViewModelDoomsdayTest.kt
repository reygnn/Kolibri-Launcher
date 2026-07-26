package com.github.reygnn.kolibri_launcher.ui.main

import android.content.Context
import androidx.arch.core.executor.testing.InstantTaskExecutorRule
import androidx.lifecycle.SavedStateHandle
import app.cash.turbine.test
import com.github.reygnn.kolibri_launcher.core.AppConstants
import com.github.reygnn.kolibri_launcher.domain.model.AppInfo
import com.github.reygnn.kolibri_launcher.domain.model.AppLoadResult
import com.github.reygnn.kolibri_launcher.domain.model.HomeSettings
import com.github.reygnn.kolibri_launcher.domain.model.UiColorsState
import com.github.reygnn.kolibri_launcher.domain.model.WallpaperState
import com.github.reygnn.kolibri_launcher.domain.usecase.GetDrawerAppsUseCase
import com.github.reygnn.kolibri_launcher.domain.usecase.GetFabPositionUseCase
import com.github.reygnn.kolibri_launcher.domain.usecase.GetFavoriteAppsUseCase
import com.github.reygnn.kolibri_launcher.domain.usecase.GetLayoutSettingsUseCase
import com.github.reygnn.kolibri_launcher.domain.usecase.ObserveHomeSettingsUseCase
import com.github.reygnn.kolibri_launcher.domain.usecase.ObserveInstalledAppsUseCase
import com.github.reygnn.kolibri_launcher.domain.usecase.ObserveTimeBasedEventsUseCase
import com.github.reygnn.kolibri_launcher.domain.usecase.ObserveUiColorsUseCase
import com.github.reygnn.kolibri_launcher.domain.usecase.ObserveWallpaperStateUseCase
import com.github.reygnn.kolibri_launcher.domain.usecase.RecordAppLaunchUseCase
import com.github.reygnn.kolibri_launcher.domain.usecase.RefreshAppsUseCase
import com.github.reygnn.kolibri_launcher.rule.MainDispatcherRule
import com.github.reygnn.kolibri_launcher.rule.TimberRule
import com.github.reygnn.kolibri_launcher.ui.base.UiEvent
import com.github.reygnn.kolibri_launcher.domain.model.UiState
import com.github.reygnn.kolibri_launcher.core.AppUpdateSignal
import com.github.reygnn.kolibri_launcher.ui.util.TestMode
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Rule
import org.junit.Test

/**
 * Doomsday / stress tests for the refactored delegate-based LauncherViewModel.
 * These test extreme edge cases that individual delegate tests don't cover:
 * deadlocks, mass updates, multi-exception scenarios, and process death.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class LauncherViewModelDoomsdayTest {

    @get:Rule
    val instantTaskExecutorRule = InstantTaskExecutorRule()

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    @get:Rule
    val timberRule = TimberRule()

    // --- Shared mocks ---
    private lateinit var getFavoriteAppsUseCase: GetFavoriteAppsUseCase
    private lateinit var getDrawerAppsUseCase: GetDrawerAppsUseCase
    private lateinit var observeTimeBasedEventsUseCase: ObserveTimeBasedEventsUseCase
    private lateinit var observeUiColorsUseCase: ObserveUiColorsUseCase
    private lateinit var observeInstalledAppsUseCase: ObserveInstalledAppsUseCase
    private lateinit var observeHomeSettingsUseCase: ObserveHomeSettingsUseCase
    private lateinit var observeWallpaperStateUseCase: ObserveWallpaperStateUseCase
    private lateinit var getLayoutSettingsUseCase: GetLayoutSettingsUseCase
    private lateinit var recordAppLaunchUseCase: RecordAppLaunchUseCase
    private lateinit var refreshAppsUseCase: RefreshAppsUseCase
    private lateinit var appUpdateSignal: AppUpdateSignal
    private lateinit var context: Context

    private val testApp: AppInfo = mockk {
        every { packageName } returns "com.test.app"
        every { displayName } returns "Test App"
    }

    @Before
    fun setUp() {
        context = mockk(relaxed = true) {
            every { registerReceiver(any(), any(), any<Int>()) } returns null
        }

        getFavoriteAppsUseCase = mockk {
            every { favoriteApps } returns MutableStateFlow(UiState.Loading)
        }
        getDrawerAppsUseCase = mockk(relaxed = true)

        observeTimeBasedEventsUseCase = mockk(relaxed = true)
        every { observeTimeBasedEventsUseCase.invoke() } returns emptyFlow()

        observeUiColorsUseCase = mockk(relaxed = true)
        every { observeUiColorsUseCase.invoke() } returns flowOf(UiColorsState())

        observeInstalledAppsUseCase = mockk(relaxed = true)
        every { observeInstalledAppsUseCase.invoke() } returns flowOf(AppLoadResult.Success)

        observeHomeSettingsUseCase = mockk(relaxed = true)
        every { observeHomeSettingsUseCase.invoke() } returns flowOf(HomeSettings())

        observeWallpaperStateUseCase = mockk(relaxed = true)
        every { observeWallpaperStateUseCase.invoke() } returns flowOf(WallpaperState.NONE)

        getLayoutSettingsUseCase = mockk {
            every { layoutScale } returns flowOf(AppConstants.DEFAULT_LAYOUT_SCALE)
            every { verticalPadding } returns flowOf(AppConstants.DEFAULT_VERTICAL_PADDING_FACTOR)
            every { isFontBold } returns flowOf(AppConstants.DEFAULT_FONT_BOLD)
            every { contentTopMargin } returns flowOf(0f)
            every { favoritesAlignment } returns flowOf(AppConstants.DEFAULT_FAVORITES_ALIGNMENT)
        }

        recordAppLaunchUseCase = mockk(relaxed = true)
        refreshAppsUseCase = mockk(relaxed = true)

        appUpdateSignal = mockk {
            every { events } returns MutableSharedFlow(extraBufferCapacity = 1)
        }
    }

    private fun createViewModel(
        enableTestMode: Boolean = false,
        savedStateHandle: SavedStateHandle = SavedStateHandle()
    ) = LauncherViewModel(
        getFavoriteAppsUseCase = getFavoriteAppsUseCase,
        getDrawerAppsUseCase = getDrawerAppsUseCase,
        hideAppUseCase = mockk(relaxed = true),
        toggleFavoriteUseCase = mockk(relaxed = true),
        recordAppLaunchUseCase = recordAppLaunchUseCase,
        refreshAppsUseCase = refreshAppsUseCase,
        resetAppUsageUseCase = mockk(relaxed = true),
        showAppUseCase = mockk(relaxed = true),
        toggleSortOrderUseCase = mockk(relaxed = true),
        handleSwipeActionUseCase = mockk(relaxed = true),
        getRecentAppsUseCase = mockk(relaxed = true),
        observeDoubleTapClipboardSettingUseCase = mockk(relaxed = true),
        observeTimeBasedEventsUseCase = observeTimeBasedEventsUseCase,
        observeUiColorsUseCase = observeUiColorsUseCase,
        setTextColorUseCase = mockk(relaxed = true),
        setTextShadowEnabledUseCase = mockk(relaxed = true),
        setChipBackgroundColorUseCase = mockk(relaxed = true),
        observeInstalledAppsUseCase = observeInstalledAppsUseCase,
        getAutoLaunchSettingUseCase = mockk(relaxed = true),
        observeHomeSettingsUseCase = observeHomeSettingsUseCase,
        checkAppUsageUseCase = mockk(relaxed = true),
        getAutoShowKeyboardSettingUseCase = mockk(relaxed = true),
        getTextShadowEnabledUseCase = mockk(relaxed = true),
        getLayoutSettingsUseCase = getLayoutSettingsUseCase,
        setLayoutScaleUseCase = mockk(relaxed = true),
        setVerticalPaddingUseCase = mockk(relaxed = true),
        setFontBoldUseCase = mockk(relaxed = true),
        setContentTopMarginUseCase = mockk(relaxed = true),
        setFavoritesAlignmentUseCase = mockk(relaxed = true),
        resolveAppDrawerSurfaceUseCase = mockk(relaxed = true),
        observeWallpaperStateUseCase = observeWallpaperStateUseCase,
        saveWallpaperStateUseCase = mockk(relaxed = true),
        setWallpaperImageUseCase = mockk(relaxed = true),
        clearWallpaperUseCase = mockk(relaxed = true),
        getFabPositionUseCase = mockk<GetFabPositionUseCase>(relaxed = true).also { every { it.invoke() } returns emptyFlow() },
        saveFabPositionUseCase = mockk(relaxed = true),
        wallpaperFileManager = mockk(relaxed = true),
        appUpdateSignal = appUpdateSignal,
        savedStateHandle = savedStateHandle,
        context = context,
        mainDispatcher = mainDispatcherRule.testDispatcher,
        testMode = TestMode(isEnabled = enableTestMode)
    )

    // ===========================================
    // DEADLOCK: One flow hangs forever
    // ===========================================

    @Test
    fun `doomsday - deadlock - one hanging flow does not block others`() = runTest {
        // Settings hängt für immer
        every { observeHomeSettingsUseCase.invoke() } returns flow {
            delay(Long.MAX_VALUE)
        }

        // Apps laden normal
        every { observeInstalledAppsUseCase.invoke() } returns flowOf(AppLoadResult.Success)

        val vm = createViewModel(enableTestMode = false)
        advanceUntilIdle()

        // Obwohl Settings hängen, wurde observeInstalledAppsUseCase trotzdem aufgerufen
        coVerify { observeInstalledAppsUseCase.invoke() }

        // ViewModel ist initialisiert und funktional
        assertNotNull(vm.uiState.value)
    }

    // ===========================================
    // DDOS: 10.000 app updates in rapid succession
    // ===========================================

    @Test
    fun `doomsday - DDOS - 10000 rapid app updates do not crash`() = runTest {
        val updateFlow = MutableSharedFlow<Unit>()
        every { appUpdateSignal.events } returns updateFlow

        val vm = createViewModel(enableTestMode = false)
        advanceUntilIdle()

        // Feuer frei
        repeat(10_000) {
            updateFlow.emit(Unit)
        }
        advanceUntilIdle()

        // VM lebt noch und hat versucht zu refreshen
        coVerify(atLeast = 1) { refreshAppsUseCase.invoke() }
        assertNotNull(vm.uiState.value)
    }

    // ===========================================
    // DOUBLE EXCEPTION: App vanishes during click
    // ===========================================

    @Test
    fun `doomsday - app uninstalled during click - double exception handled`() = runTest {
        coEvery { recordAppLaunchUseCase(any()) } throws IllegalArgumentException("App gone")
        coEvery { refreshAppsUseCase() } throws IllegalStateException("Package manager died")

        val vm = createViewModel()
        advanceUntilIdle()

        vm.event.test {
            vm.onAppClicked(testApp)

            // Launch-Event kommt zuerst (vor dem Recording)
            assertTrue(awaitItem() is UiEvent.LaunchApp)

            // Dann Error-Toast (wegen recordAppLaunch Failure)
            assertTrue(awaitItem() is UiEvent.ShowToast)

            // Kein Crash, obwohl ZWEI Exceptions flogen
        }
    }

    // ===========================================
    // PROCESS DEATH: SavedStateHandle restoration
    // ===========================================

    @Test
    fun `doomsday - process death - search query survives restoration`() = runTest {
        val savedState = SavedStateHandle().apply {
            set(AppConstants.KEY_SEARCH_QUERY, "Vor dem Crash")
        }

        val vm = createViewModel(
            enableTestMode = true,
            savedStateHandle = savedState
        )
        advanceUntilIdle()

        // Der Search-Query hat den Prozess-Tod überlebt
        assertEquals("Vor dem Crash", vm.appDrawerSearchQuery.value)
    }
}