package com.github.reygnn.kolibri_launcher.ui.main

import com.github.reygnn.launcher.feature.wallpaper.CachedWallpaperComposite
import com.github.reygnn.launcher.core.wallpaper.WallpaperRepository
import com.github.reygnn.launcher.feature.wallpaper.WallpaperImageStore
import com.github.reygnn.kolibri_launcher.domain.model.SettingsDefaults

import android.content.Context
import androidx.arch.core.executor.testing.InstantTaskExecutorRule
import androidx.lifecycle.SavedStateHandle
import app.cash.turbine.test
import com.github.reygnn.launcher.core.AppConstants
import com.github.reygnn.launcher.core.AppInfo
import com.github.reygnn.kolibri_launcher.domain.model.AppLoadResult
import com.github.reygnn.kolibri_launcher.domain.model.HomeSettings
import com.github.reygnn.kolibri_launcher.domain.model.UiColorsState
import com.github.reygnn.launcher.core.wallpaper.WallpaperState
import com.github.reygnn.kolibri_launcher.domain.usecase.GetDrawerAppsUseCase
import com.github.reygnn.kolibri_launcher.domain.usecase.GetFabPositionUseCase
import com.github.reygnn.kolibri_launcher.domain.usecase.GetFavoriteAppsUseCase
import com.github.reygnn.kolibri_launcher.domain.usecase.GetLayoutSettingsUseCase
import com.github.reygnn.kolibri_launcher.domain.usecase.ObserveHomeSettingsUseCase
import com.github.reygnn.kolibri_launcher.domain.usecase.ObserveInstalledAppsUseCase
import com.github.reygnn.launcher.core.timeinfo.ObserveTimeBasedEventsUseCase
import com.github.reygnn.kolibri_launcher.domain.usecase.ObserveUiColorsUseCase
import com.github.reygnn.kolibri_launcher.domain.usecase.ObserveWallpaperStateUseCase
import com.github.reygnn.kolibri_launcher.domain.usecase.RecordAppLaunchUseCase
import com.github.reygnn.launcher.core.RefreshAppsUseCase
import com.github.reygnn.launcher.core.testing.MainDispatcherRule
import com.github.reygnn.kolibri_launcher.rule.TimberRule
import com.github.reygnn.kolibri_launcher.ui.base.UiEvent
import com.github.reygnn.kolibri_launcher.domain.model.UiState
import com.github.reygnn.launcher.core.AppUpdateSignal
import com.github.reygnn.launcher.core.PackageEvent
import com.github.reygnn.kolibri_launcher.ui.util.TestMode
import com.google.common.truth.Truth.assertThat
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
            every { favoritesAlignment } returns flowOf(SettingsDefaults.DEFAULT_FAVORITES_ALIGNMENT)
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
        observeTimeBasedEventsUseCase = observeTimeBasedEventsUseCase,
        observeUiColorsUseCase = observeUiColorsUseCase,
        setTextColorUseCase = mockk(relaxed = true),
        setTextShadowEnabledUseCase = mockk(relaxed = true),
        observeInstalledAppsUseCase = observeInstalledAppsUseCase,
        getAutoLaunchSettingUseCase = mockk(relaxed = true),
        observeHomeSettingsUseCase = observeHomeSettingsUseCase,
        checkAppUsageUseCase = mockk(relaxed = true),
        getAutoShowKeyboardSettingUseCase = mockk(relaxed = true),
        getTextShadowEnabledUseCase = mockk(relaxed = true),
        getLayoutSettingsUseCase = getLayoutSettingsUseCase,
        setLayoutScaleUseCase = mockk(relaxed = true),
        getWallpaperScrimAlphaUseCase = mockk(relaxed = true),
        setWallpaperScrimAlphaUseCase = mockk(relaxed = true),
        setVerticalPaddingUseCase = mockk(relaxed = true),
        setFontBoldUseCase = mockk(relaxed = true),
        setContentTopMarginUseCase = mockk(relaxed = true),
        setFavoritesAlignmentUseCase = mockk(relaxed = true),
        resolveAppDrawerSurfaceUseCase = mockk(relaxed = true),
        observeWallpaperStateUseCase = observeWallpaperStateUseCase,
        saveWallpaperStateUseCase = mockk(relaxed = true),
        clearWallpaperUseCase = mockk(relaxed = true),
        getFabPositionUseCase = mockk<GetFabPositionUseCase>(relaxed = true).also { every { it.invoke() } returns emptyFlow() },
        saveFabPositionUseCase = mockk(relaxed = true),
        observeWallpaperBackdropUseCase = mockk(relaxed = true),
        wallpaperImageStore = WallpaperImageStore(mockk(relaxed = true), persistedNothing(), mainDispatcherRule.testDispatcher),
        wallpaperComposite = CachedWallpaperComposite(mockk(relaxed = true), mockk(relaxed = true), mockk(relaxed = true), mockk(relaxed = true), mainDispatcherRule.testDispatcher),
        wallpaperDisplaySettingsStore = mockk(relaxed = true),
        appUpdateSignal = appUpdateSignal,
        monotonicClock = neverThrottlingClock(),
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
        assertThat(vm.uiState.value).isNotNull()
    }

    // ===========================================
    // DDOS: 10.000 app updates in rapid succession
    // ===========================================

    @Test
    fun `doomsday - DDOS - 10000 rapid app updates do not crash`() = runTest {
        val updateFlow = MutableSharedFlow<PackageEvent>()
        every { appUpdateSignal.events } returns updateFlow

        val vm = createViewModel(enableTestMode = false)
        advanceUntilIdle()

        // Feuer frei
        repeat(10_000) {
            updateFlow.emit(PackageEvent.Added("com.ddos.app"))
        }
        advanceUntilIdle()

        // VM lebt noch und hat versucht zu refreshen
        coVerify(atLeast = 1) { refreshAppsUseCase.invoke() }
        assertThat(vm.uiState.value).isNotNull()
    }

    // ===========================================
    // DOUBLE EXCEPTION: App vanishes during click
    // ===========================================

    @Test
    fun `doomsday - app uninstalled during click - recording exception handled`() = runTest {
        // A click no longer calls refreshAppsUseCase (REACTIVE_APPLIST_SPEC), so
        // the only failure mode on this path is recordAppLaunch throwing.
        coEvery { recordAppLaunchUseCase(any()) } throws IllegalArgumentException("App gone")

        val vm = createViewModel()
        advanceUntilIdle()

        vm.event.test {
            vm.onAppClicked(testApp)

            // Launch-Event kommt zuerst (vor dem Recording)
            assertThat(awaitItem()).isInstanceOf(UiEvent.LaunchApp::class.java)

            // Dann Error-Toast (wegen recordAppLaunch Failure)
            assertThat(awaitItem()).isInstanceOf(UiEvent.ShowToast::class.java)

            // No crash despite the recording exception.
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
        assertThat(vm.appDrawerSearchQuery.value).isEqualTo("Vor dem Crash")
    }
}

/**
 * A repository for [WallpaperImageStore] whose persisted state references nothing (3a-2c): every
 * delete candidate counts as unreferenced, as before the store read the persisted state itself.
 */
private fun persistedNothing(): WallpaperRepository = io.mockk.mockk(relaxed = true) {
    io.mockk.coEvery { readPersistedImageUris() } returns emptySet()
}
