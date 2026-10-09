package com.github.reygnn.kolibri_launcher.ui.main

import com.github.reygnn.launcher.feature.wallpaper.CachedWallpaperComposite
import com.github.reygnn.launcher.core.wallpaper.WallpaperRepository
import com.github.reygnn.launcher.feature.wallpaper.WallpaperImageStore
import com.github.reygnn.kolibri_launcher.domain.model.SettingsDefaults

import android.content.Context
import android.content.Intent
import android.os.BatteryManager
import androidx.arch.core.executor.testing.InstantTaskExecutorRule
import androidx.lifecycle.SavedStateHandle
import app.cash.turbine.test
import com.github.reygnn.kolibri_launcher.R
import com.github.reygnn.launcher.core.AppConstants
import com.github.reygnn.launcher.common.data.wallpaper.WallpaperFileManager
import com.github.reygnn.launcher.core.AppInfo
import com.github.reygnn.kolibri_launcher.domain.model.FavoriteAppsResult
import com.github.reygnn.kolibri_launcher.domain.model.HomeSettings
import com.github.reygnn.launcher.core.testing.recordEmissions
import com.github.reygnn.launcher.core.timeinfo.TimeBasedEvent
import com.github.reygnn.launcher.core.timeinfo.TimeBasedEventType
import com.github.reygnn.kolibri_launcher.domain.model.UiColorsState
import com.github.reygnn.launcher.core.wallpaper.WallpaperState
import com.github.reygnn.kolibri_launcher.domain.usecase.CheckAppUsageUseCase
import com.github.reygnn.kolibri_launcher.domain.usecase.ClearWallpaperUseCase
import com.github.reygnn.kolibri_launcher.domain.usecase.GetFabPositionUseCase
import com.github.reygnn.kolibri_launcher.domain.usecase.SaveFabPositionUseCase
import com.github.reygnn.kolibri_launcher.domain.usecase.GetAutoLaunchSettingUseCase
import com.github.reygnn.kolibri_launcher.domain.usecase.GetAutoShowKeyboardSettingUseCase
import com.github.reygnn.kolibri_launcher.domain.usecase.GetDrawerAppsUseCase
import com.github.reygnn.kolibri_launcher.domain.usecase.GetFavoriteAppsUseCase
import com.github.reygnn.kolibri_launcher.domain.usecase.GetLayoutSettingsUseCase
import com.github.reygnn.kolibri_launcher.domain.usecase.GetTextShadowEnabledUseCase
import com.github.reygnn.kolibri_launcher.domain.usecase.HandleSwipeActionUseCase
import com.github.reygnn.kolibri_launcher.domain.usecase.HideAppUseCase
import com.github.reygnn.kolibri_launcher.domain.usecase.ObserveHomeSettingsUseCase
import com.github.reygnn.kolibri_launcher.domain.usecase.ObserveInstalledAppsUseCase
import com.github.reygnn.launcher.core.timeinfo.ObserveTimeBasedEventsUseCase
import com.github.reygnn.kolibri_launcher.domain.usecase.ObserveUiColorsUseCase
import com.github.reygnn.kolibri_launcher.domain.usecase.ObserveWallpaperStateUseCase
import com.github.reygnn.kolibri_launcher.domain.usecase.RecordAppLaunchUseCase
import com.github.reygnn.launcher.core.RefreshAppsUseCase
import com.github.reygnn.kolibri_launcher.domain.usecase.ResetAppUsageUseCase
import com.github.reygnn.kolibri_launcher.domain.usecase.SaveWallpaperStateUseCase
import com.github.reygnn.kolibri_launcher.domain.usecase.SetContentTopMarginUseCase
import com.github.reygnn.kolibri_launcher.domain.usecase.SetFontBoldUseCase
import com.github.reygnn.kolibri_launcher.domain.usecase.SetLayoutScaleUseCase
import com.github.reygnn.kolibri_launcher.domain.usecase.SetTextColorUseCase
import com.github.reygnn.kolibri_launcher.domain.usecase.SetTextShadowEnabledUseCase
import com.github.reygnn.kolibri_launcher.domain.usecase.SetVerticalPaddingUseCase
import com.github.reygnn.kolibri_launcher.domain.usecase.ShowAppUseCase
import com.github.reygnn.kolibri_launcher.domain.usecase.ToggleFavoriteUseCase
import com.github.reygnn.kolibri_launcher.domain.usecase.ToggleSortOrderUseCase
import com.github.reygnn.launcher.core.testing.MainDispatcherRule
import com.github.reygnn.kolibri_launcher.rule.TimberRule
import com.github.reygnn.kolibri_launcher.ui.base.UiEvent
import com.github.reygnn.kolibri_launcher.domain.model.UiState
import com.github.reygnn.kolibri_launcher.domain.model.SwipeSlot
import com.github.reygnn.launcher.core.AppUpdateSignal
import com.github.reygnn.kolibri_launcher.ui.util.TestMode
import com.google.common.truth.Truth.assertThat
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Before
import org.junit.Rule
import org.junit.Test

/**
 * Contract test: verifies the refactored delegate-based LauncherViewModel
 * behaves identically to the old monolithic ViewModel from the Fragment's perspective.
 *
 * This test class focuses on the PUBLIC API contract that Fragments depend on:
 * - State observations (collect/observe)
 * - Action dispatching (method calls)
 * - Event reception (one-shot events)
 * - Suspend queries
 *
 * If any test here fails, a Fragment would break.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class LauncherViewModelContractTest {

    @get:Rule
    val instantTaskExecutorRule = InstantTaskExecutorRule()

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    /**
     * The persisted references the image store reads (3a-2c; 3b/29, D2). Empty by default — every
     * delete candidate counts as unreferenced; a "remove" test sets the references before the
     * removal, and the clear use case empties them, like the real repository.
     */
    private var persistedRefs: () -> Set<String> = { emptySet() }

    private fun persistedRefsRepository(): WallpaperRepository = io.mockk.mockk(relaxed = true) {
        io.mockk.coEvery { readPersistedImageUris() } answers { persistedRefs() }
    }

    @get:Rule
    val timberRule = TimberRule()

    // --- Controllable Flows (simulate data layer) ---
    private val favoriteAppsFlow = MutableStateFlow<UiState<FavoriteAppsResult>>(UiState.Loading)
    private val homeSettingsFlow = MutableStateFlow(HomeSettings())
    private val wallpaperStateFlow = MutableStateFlow(WallpaperState.NONE)
    private val uiColorsFlow = MutableStateFlow(UiColorsState())
    private val layoutScaleFlow = MutableStateFlow(AppConstants.DEFAULT_LAYOUT_SCALE)
    private val verticalPaddingFlow = MutableStateFlow(AppConstants.DEFAULT_VERTICAL_PADDING_FACTOR)
    private val fontBoldFlow = MutableStateFlow(AppConstants.DEFAULT_FONT_BOLD)
    private val contentTopMarginFlow = MutableStateFlow(0f)
    private val timeBasedEventsFlow = MutableStateFlow<List<TimeBasedEvent>>(emptyList())

    // --- Mocks ---
    private lateinit var toggleFavoriteUseCase: ToggleFavoriteUseCase
    private lateinit var recordAppLaunchUseCase: RecordAppLaunchUseCase
    private lateinit var refreshAppsUseCase: RefreshAppsUseCase
    private lateinit var hideAppUseCase: HideAppUseCase
    private lateinit var showAppUseCase: ShowAppUseCase
    private lateinit var resetAppUsageUseCase: ResetAppUsageUseCase
    private lateinit var toggleSortOrderUseCase: ToggleSortOrderUseCase
    private lateinit var handleSwipeActionUseCase: HandleSwipeActionUseCase
    private lateinit var setTextColorUseCase: SetTextColorUseCase
    private lateinit var setTextShadowEnabledUseCase: SetTextShadowEnabledUseCase
    private lateinit var setLayoutScaleUseCase: SetLayoutScaleUseCase
    private lateinit var setVerticalPaddingUseCase: SetVerticalPaddingUseCase
    private lateinit var setFontBoldUseCase: SetFontBoldUseCase
    private lateinit var setContentTopMarginUseCase: SetContentTopMarginUseCase
    private lateinit var clearWallpaperUseCase: ClearWallpaperUseCase
    private lateinit var getFabPositionUseCase: GetFabPositionUseCase
    private lateinit var saveFabPositionUseCase: SaveFabPositionUseCase
    private lateinit var saveWallpaperStateUseCase: SaveWallpaperStateUseCase
    private lateinit var wallpaperFileManager: WallpaperFileManager
    private lateinit var getAutoLaunchSettingUseCase: GetAutoLaunchSettingUseCase
    private lateinit var getAutoShowKeyboardSettingUseCase: GetAutoShowKeyboardSettingUseCase
    private lateinit var getTextShadowEnabledUseCase: GetTextShadowEnabledUseCase
    private lateinit var checkAppUsageUseCase: CheckAppUsageUseCase
    private lateinit var observeTimeBasedEventsUseCase: ObserveTimeBasedEventsUseCase

    private val testApp: AppInfo = mockk {
        every { packageName } returns "com.test.app"
        every { displayName } returns "Test App"
    }

    @Before
    fun setUp() {
        toggleFavoriteUseCase = mockk(relaxed = true)
        recordAppLaunchUseCase = mockk(relaxed = true)
        refreshAppsUseCase = mockk(relaxed = true)
        hideAppUseCase = mockk(relaxed = true)
        showAppUseCase = mockk(relaxed = true)
        resetAppUsageUseCase = mockk(relaxed = true)
        toggleSortOrderUseCase = mockk(relaxed = true)
        handleSwipeActionUseCase = mockk(relaxed = true)
        setTextColorUseCase = mockk(relaxed = true)
        setTextShadowEnabledUseCase = mockk(relaxed = true)
        setLayoutScaleUseCase = mockk(relaxed = true)
        setVerticalPaddingUseCase = mockk(relaxed = true)
        setFontBoldUseCase = mockk(relaxed = true)
        setContentTopMarginUseCase = mockk(relaxed = true)
        clearWallpaperUseCase = mockk(relaxed = true)
        getFabPositionUseCase = mockk(relaxed = true)
        every { getFabPositionUseCase.invoke() } returns emptyFlow()
        saveFabPositionUseCase = mockk(relaxed = true)
        saveWallpaperStateUseCase = mockk(relaxed = true)
        wallpaperFileManager = mockk(relaxed = true)
        getAutoLaunchSettingUseCase = mockk(relaxed = true)
        getAutoShowKeyboardSettingUseCase = mockk(relaxed = true)
        getTextShadowEnabledUseCase = mockk(relaxed = true)
        checkAppUsageUseCase = mockk(relaxed = true)

        observeTimeBasedEventsUseCase = mockk(relaxed = true)
        every { observeTimeBasedEventsUseCase.invoke() } returns timeBasedEventsFlow
    }

    private fun createViewModel(): LauncherViewModel {
        val context: Context = mockk(relaxed = true) {
            every { registerReceiver(any(), any(), any<Int>()) } returns null
        }

        val getFavoriteAppsUseCase: GetFavoriteAppsUseCase = mockk {
            every { favoriteApps } returns favoriteAppsFlow
        }

        val getDrawerAppsUseCase: GetDrawerAppsUseCase = mockk(relaxed = true)

        val observeInstalledAppsUseCase: ObserveInstalledAppsUseCase = mockk(relaxed = true)
        every { observeInstalledAppsUseCase.invoke() } returns emptyFlow()

        val observeHomeSettingsUseCase: ObserveHomeSettingsUseCase = mockk(relaxed = true)
        every { observeHomeSettingsUseCase.invoke() } returns homeSettingsFlow

        val observeUiColorsUseCase: ObserveUiColorsUseCase = mockk(relaxed = true)
        every { observeUiColorsUseCase.invoke() } returns uiColorsFlow

        val observeWallpaperStateUseCase: ObserveWallpaperStateUseCase = mockk(relaxed = true)
        every { observeWallpaperStateUseCase.invoke() } returns wallpaperStateFlow

        val getLayoutSettingsUseCase: GetLayoutSettingsUseCase = mockk {
            every { layoutScale } returns layoutScaleFlow
            every { verticalPadding } returns verticalPaddingFlow
            every { isFontBold } returns fontBoldFlow
            every { contentTopMargin } returns contentTopMarginFlow
            every { favoritesAlignment } returns flowOf(SettingsDefaults.DEFAULT_FAVORITES_ALIGNMENT)
        }

        val appUpdateSignal: AppUpdateSignal = mockk {
            every { events } returns MutableSharedFlow(extraBufferCapacity = 1)
        }

        val testMode: TestMode = mockk {
            every { isEnabled } returns true
        }

        return LauncherViewModel(
            getFavoriteAppsUseCase = getFavoriteAppsUseCase,
            getDrawerAppsUseCase = getDrawerAppsUseCase,
            hideAppUseCase = hideAppUseCase,
            toggleFavoriteUseCase = toggleFavoriteUseCase,
            recordAppLaunchUseCase = recordAppLaunchUseCase,
            refreshAppsUseCase = refreshAppsUseCase,
            resetAppUsageUseCase = resetAppUsageUseCase,
            showAppUseCase = showAppUseCase,
            toggleSortOrderUseCase = toggleSortOrderUseCase,
            handleSwipeActionUseCase = handleSwipeActionUseCase,
            getRecentAppsUseCase = mockk(relaxed = true),
            observeTimeBasedEventsUseCase = observeTimeBasedEventsUseCase,
            observeUiColorsUseCase = observeUiColorsUseCase,
            setTextColorUseCase = setTextColorUseCase,
            setTextShadowEnabledUseCase = setTextShadowEnabledUseCase,
            observeInstalledAppsUseCase = observeInstalledAppsUseCase,
            getAutoLaunchSettingUseCase = getAutoLaunchSettingUseCase,
            observeHomeSettingsUseCase = observeHomeSettingsUseCase,
            checkAppUsageUseCase = checkAppUsageUseCase,
            getAutoShowKeyboardSettingUseCase = getAutoShowKeyboardSettingUseCase,
            getTextShadowEnabledUseCase = getTextShadowEnabledUseCase,
            getLayoutSettingsUseCase = getLayoutSettingsUseCase,
            setLayoutScaleUseCase = setLayoutScaleUseCase,
            getWallpaperScrimAlphaUseCase = mockk(relaxed = true),
            setWallpaperScrimAlphaUseCase = mockk(relaxed = true),
            setVerticalPaddingUseCase = setVerticalPaddingUseCase,
            setFontBoldUseCase = setFontBoldUseCase,
            setContentTopMarginUseCase = setContentTopMarginUseCase,
            setFavoritesAlignmentUseCase = mockk(relaxed = true),
            resolveAppDrawerSurfaceUseCase = mockk(relaxed = true),
            observeWallpaperStateUseCase = observeWallpaperStateUseCase,
            saveWallpaperStateUseCase = saveWallpaperStateUseCase,
            clearWallpaperUseCase = clearWallpaperUseCase,
            getFabPositionUseCase = getFabPositionUseCase,
            saveFabPositionUseCase = saveFabPositionUseCase,
            observeWallpaperBackdropUseCase = mockk(relaxed = true),
            wallpaperImageStore = WallpaperImageStore(wallpaperFileManager, persistedRefsRepository(), mainDispatcherRule.testDispatcher),
            wallpaperComposite = CachedWallpaperComposite(mockk(relaxed = true), mockk(relaxed = true), mockk(relaxed = true), mockk(relaxed = true), mainDispatcherRule.testDispatcher),
            wallpaperDisplaySettingsStore = mockk(relaxed = true),
            appUpdateSignal = appUpdateSignal,
            monotonicClock = neverThrottlingClock(),
            savedStateHandle = SavedStateHandle(),
            context = context,
            mainDispatcher = mainDispatcherRule.testDispatcher,
            testMode = testMode
        )
    }

    // =====================================================================
    // CONTRACT 1: HomeFragment observes uiState for time/date/battery
    // "Fragment collects uiState and displays time, date, battery, events"
    // =====================================================================

    @Test
    fun `HomeFragment - uiState provides live time and date after init`() = runTest {
        val vm = createViewModel()
        advanceUntilIdle()

        val state = vm.uiState.value
        assertThat(state.timeString).isNotEqualTo("--:--")
        assertThat(state.dateString).isNotEqualTo("---")
        assertThat(state.timeString.contains(":")).isTrue()
    }

    @Test
    fun `HomeFragment - uiState reflects battery update`() = runTest {
        val vm = createViewModel()
        advanceUntilIdle()

        vm.updateBatteryLevel(42, 100)
        advanceUntilIdle()

        assertThat(vm.uiState.value.batteryString).isEqualTo("42%")
    }

    @Test
    fun `HomeFragment - uiState reflects battery from intent`() = runTest {
        val vm = createViewModel()
        advanceUntilIdle()

        val intent: Intent = mockk {
            every { getIntExtra(BatteryManager.EXTRA_LEVEL, -1) } returns 95
            every { getIntExtra(BatteryManager.EXTRA_SCALE, -1) } returns 100
            every {
                getIntExtra(BatteryManager.EXTRA_STATUS, BatteryManager.BATTERY_STATUS_UNKNOWN)
            } returns BatteryManager.BATTERY_STATUS_DISCHARGING
            every { getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) } returns 0
        }
        vm.updateBatteryLevelFromIntent(intent)
        advanceUntilIdle()

        assertThat(vm.uiState.value.batteryString).isEqualTo("95%")
    }

    @Test
    fun `HomeFragment - uiState reflects time-based events from data layer`() = runTest {
        val vm = createViewModel()
        advanceUntilIdle()

        val event = TimeBasedEvent(
            triggerTimeMillis = System.currentTimeMillis() + 3600000,
            title = "Meeting",
            type = TimeBasedEventType.CALENDAR
        )
        timeBasedEventsFlow.value = listOf(event)
        advanceUntilIdle()

        assertThat(vm.uiState.value.timeBasedEvents.size).isEqualTo(1)
        assertThat(vm.uiState.value.timeBasedEvents.first().title).isEqualTo("Meeting")
    }

    // =====================================================================
    // CONTRACT 2: HomeFragment observes favorites
    // "Fragment collects favoriteAppsState to display favorite apps on home"
    // =====================================================================

    @Test
    fun `HomeFragment - favoriteAppsState starts Loading then receives data`() = runTest {
        val vm = createViewModel()

        assertThat(vm.favoriteAppsState.value).isEqualTo(UiState.Loading)

        val apps = listOf(testApp)
        favoriteAppsFlow.value = UiState.Success(FavoriteAppsResult(apps, isFallback = false))
        advanceUntilIdle()

        val state = vm.favoriteAppsState.value
        assertThat(state).isInstanceOf(UiState.Success::class.java)
        assertThat((state as UiState.Success).data.apps.size).isEqualTo(1)
    }

    // =====================================================================
    // CONTRACT 3: HomeFragment triggers app launch
    // "Fragment calls onAppClicked → receives LaunchApp event → starts activity"
    // =====================================================================

    @Test
    fun `HomeFragment - onAppClicked emits LaunchApp event with correct app`() = runTest {
        val vm = createViewModel()
        advanceUntilIdle()

        val events = mutableListOf<UiEvent>()
        val job = recordEmissions(vm.event, into = events)

        vm.onAppClicked(testApp)
        advanceUntilIdle()

        val launchEvent = events.filterIsInstance<UiEvent.LaunchApp>().firstOrNull()
        assertThat(launchEvent).isNotNull()
        assertThat(launchEvent!!.app).isEqualTo(testApp)

        coVerify { recordAppLaunchUseCase.invoke(testApp) }
        // A launch no longer forces a re-enumeration (REACTIVE_APPLIST_SPEC): the
        // recorded launch ticks usageFlow and the drawer re-sorts reactively.
        coVerify(exactly = 0) { refreshAppsUseCase.invoke() }

        job.cancel()
    }

    // =====================================================================
    // CONTRACT 4: AppDrawerFragment uses search + sort
    // "Fragment updates search query, observes filtered results, toggles sort"
    // =====================================================================

    @Test
    fun `AppDrawerFragment - search query flows from input to state`() = runTest {
        val vm = createViewModel()

        vm.appDrawerSearchQuery.test {
            assertThat(awaitItem()).isEqualTo("")

            vm.onAppDrawerSearchQueryChanged("calc")
            assertThat(awaitItem()).isEqualTo("calc")

            vm.onAppDrawerSearchQueryChanged("calculator")
            assertThat(awaitItem()).isEqualTo("calculator")
        }
    }

    @Test
    fun `AppDrawerFragment - closing drawer resets search`() = runTest {
        val vm = createViewModel()

        vm.onAppDrawerSearchQueryChanged("test")
        assertThat(vm.appDrawerSearchQuery.value).isEqualTo("test")

        vm.onAppDrawerClosed()
        assertThat(vm.appDrawerSearchQuery.value).isEqualTo("")
    }

    @Test
    fun `AppDrawerFragment - toggleSortOrder triggers use case`() = runTest {
        val vm = createViewModel()
        advanceUntilIdle()

        vm.toggleSortOrder()
        advanceUntilIdle()

        coVerify { toggleSortOrderUseCase.invoke() }
    }

    // =====================================================================
    // CONTRACT 5: HomeFragment gestures
    // "Fragment detects gesture → calls VM → receives event → performs action"
    // =====================================================================

    @Test
    fun `HomeFragment - flingUp opens app drawer`() = runTest {
        val vm = createViewModel()
        advanceUntilIdle()

        val events = mutableListOf<UiEvent>()
        val job = recordEmissions(vm.event, into = events)

        vm.onFlingUp()
        advanceUntilIdle()

        assertThat(events.any { it == UiEvent.ShowAppDrawer }).isTrue()
        job.cancel()
    }

    @Test
    fun `HomeFragment - longPress shows customization`() = runTest {
        val vm = createViewModel()
        advanceUntilIdle()

        val events = mutableListOf<UiEvent>()
        val job = recordEmissions(vm.event, into = events)

        vm.onLongPress()
        advanceUntilIdle()

        assertThat(events.any { it == UiEvent.ShowCustomizationOptions }).isTrue()
        job.cancel()
    }

    @Test
    fun `HomeFragment - swipe launches assigned app`() = runTest {
        coEvery { handleSwipeActionUseCase(SwipeSlot.SWIPE_FROM_RIGHT_TO_LEFT) } returns
                HandleSwipeActionUseCase.Result.LaunchApp(testApp)

        val vm = createViewModel()
        advanceUntilIdle()

        val events = mutableListOf<UiEvent>()
        val job = recordEmissions(vm.event, into = events)

        vm.onSwipeFromRightToLeft()
        advanceUntilIdle()

        val launchEvent = events.filterIsInstance<UiEvent.LaunchApp>().firstOrNull()
        assertThat(launchEvent).isNotNull()
        assertThat(launchEvent!!.app).isEqualTo(testApp)

        job.cancel()
    }

    @Test
    fun `HomeFragment - doubleTap shortcuts emit correct events`() = runTest {
        val vm = createViewModel()
        advanceUntilIdle()

        val events = mutableListOf<UiEvent>()
        val job = recordEmissions(vm.event, into = events)

        vm.onTimeDoubleClick()
        vm.onDateDoubleClick()
        vm.onBatteryDoubleClick()
        advanceUntilIdle()

        assertThat(events.contains(UiEvent.OpenClock)).isTrue()
        assertThat(events.contains(UiEvent.OpenCalendar)).isTrue()
        assertThat(events.contains(UiEvent.OpenBatterySettings)).isTrue()

        job.cancel()
    }

    // =====================================================================
    // CONTRACT 6: AppContextMenu actions
    // "Fragment shows context menu → user picks action → VM executes + toast"
    // =====================================================================

    @Test
    fun `ContextMenu - toggleFavorite shows feedback toast`() = runTest {
        coEvery { toggleFavoriteUseCase(any(), any()) } returns
                ToggleFavoriteUseCase.Result.Success.Added

        val vm = createViewModel()
        advanceUntilIdle()

        val events = mutableListOf<UiEvent>()
        val job = recordEmissions(vm.event, into = events)

        vm.onToggleFavorite(testApp)
        advanceUntilIdle()

        assertThat(events.any { it is UiEvent.ShowToastFromString }).isTrue()
        job.cancel()
    }

    @Test
    fun `ContextMenu - hideApp calls use case and shows toast`() = runTest {
        val vm = createViewModel()
        advanceUntilIdle()

        val events = mutableListOf<UiEvent>()
        val job = recordEmissions(vm.event, into = events)

        vm.onHideApp(testApp)
        advanceUntilIdle()

        coVerify { hideAppUseCase.invoke(testApp) }
        assertThat(events.any { it is UiEvent.ShowToastFromString }).isTrue()
        job.cancel()
    }

    @Test
    fun `ContextMenu - resetUsage calls use case and shows toast`() = runTest {
        val vm = createViewModel()
        advanceUntilIdle()

        val events = mutableListOf<UiEvent>()
        val job = recordEmissions(vm.event, into = events)

        vm.onResetAppUsage(testApp)
        advanceUntilIdle()

        coVerify { resetAppUsageUseCase.invoke(testApp) }
        assertThat(events.any { it is UiEvent.ShowToastFromString }).isTrue()
        job.cancel()
    }

    // =====================================================================
    // CONTRACT 7: CustomizationFragment observes theming + layout
    // "Fragment collects colors, layout scale, padding, bold, threshold"
    // =====================================================================

    @Test
    fun `CustomizationFragment - all layout states reflect data layer changes`() = runTest {
        val vm = createViewModel()
        advanceUntilIdle()

        // Verify defaults
        assertThat(vm.layoutScaleState.value).isEqualTo(AppConstants.DEFAULT_LAYOUT_SCALE)
        assertThat(vm.verticalPaddingState.value).isEqualTo(AppConstants.DEFAULT_VERTICAL_PADDING_FACTOR)
        assertThat(vm.isFontBoldState.value).isEqualTo(AppConstants.DEFAULT_FONT_BOLD)
        assertThat(vm.contentTopMarginState.value).isEqualTo(0f)

        // Simulate settings change from data layer
        layoutScaleFlow.value = 0.8f
        verticalPaddingFlow.value = 0.5f
        fontBoldFlow.value = !AppConstants.DEFAULT_FONT_BOLD
        contentTopMarginFlow.value = 0.3f
        advanceUntilIdle()

        // Fragment would see these updates
        assertThat(vm.layoutScaleState.value).isEqualTo(0.8f)
        assertThat(vm.verticalPaddingState.value).isEqualTo(0.5f)
        assertThat(vm.isFontBoldState.value).isEqualTo(!AppConstants.DEFAULT_FONT_BOLD)
        assertThat(vm.contentTopMarginState.value).isEqualTo(0.3f)
    }


    @Test
    fun `CustomizationFragment - set actions call through to use cases`() = runTest {
        val vm = createViewModel()
        advanceUntilIdle()

        vm.onSetTextColor(0xFF0000)
        vm.onSetTextShadowEnabled(true)
        vm.onSetLayoutScale(0.7f)
        vm.onSetVerticalPadding(0.4f)
        vm.onSetFontBold(true)
        vm.onSetContentTopMargin(0.2f)
        advanceUntilIdle()

        coVerify { setTextColorUseCase.invoke(0xFF0000) }
        coVerify { setTextShadowEnabledUseCase.invoke(true) }
        coVerify { setLayoutScaleUseCase.invoke(0.7f) }
        coVerify { setVerticalPaddingUseCase.invoke(0.4f) }
        coVerify { setFontBoldUseCase.invoke(true) }
        coVerify { setContentTopMarginUseCase.invoke(0.2f) }
    }

    @Test
    fun `CustomizationFragment - reset restores all defaults`() = runTest {
        val vm = createViewModel()
        advanceUntilIdle()

        vm.onResetLayoutSettings()
        advanceUntilIdle()

        coVerify { setLayoutScaleUseCase.invoke(AppConstants.DEFAULT_LAYOUT_SCALE) }
        coVerify { setVerticalPaddingUseCase.invoke(AppConstants.DEFAULT_VERTICAL_PADDING_FACTOR) }
        coVerify { setFontBoldUseCase.invoke(AppConstants.DEFAULT_FONT_BOLD) }
        coVerify { setContentTopMarginUseCase.invoke(AppConstants.DEFAULT_TOP_MARGIN) }
    }

    // =====================================================================
    // CONTRACT 8: WallpaperFragment observes and manages wallpaper
    // "Fragment collects wallpaperState, toggles edit mode, sets/clears image"
    // =====================================================================

    @Test
    fun `WallpaperFragment - wallpaperState reflects data layer`() = runTest {
        val vm = createViewModel()
        advanceUntilIdle()

        assertThat(vm.wallpaperState.value.hasWallpaper).isFalse()

        wallpaperStateFlow.value = WallpaperState.single("file:///test.jpg", scale = 1.5f)
        advanceUntilIdle()

        assertThat(vm.wallpaperState.value.hasWallpaper).isTrue()
    }

    @Test
    fun `WallpaperFragment - clear wallpaper calls through`() = runTest {
        var refs = setOf("file:///data/wallpapers/wp_old")
        persistedRefs = { refs }
        coEvery { clearWallpaperUseCase.invoke() } answers { refs = emptySet() }
        val vm = createViewModel()
        advanceUntilIdle()

        vm.onClearWallpaper()
        advanceUntilIdle()

        coVerify { wallpaperFileManager.deleteFile("file:///data/wallpapers/wp_old") } // the previous state's file (D2)
        coVerify { clearWallpaperUseCase.invoke() }
    }

    // =====================================================================
    // CONTRACT 9: Suspend queries from Fragment
    // "Fragment calls suspend fun to check settings before navigation/action"
    // =====================================================================

    @Test
    fun `Fragment - isAutoLaunchEnabled returns data layer value`() = runTest {
        coEvery { getAutoLaunchSettingUseCase() } returns true
        val vm = createViewModel()

        assertThat(vm.isAutoLaunchEnabled()).isTrue()
    }

    @Test
    fun `Fragment - isAutoShowKeyboardEnabled returns data layer value`() = runTest {
        coEvery { getAutoShowKeyboardSettingUseCase() } returns true
        val vm = createViewModel()

        assertThat(vm.isAutoShowKeyboardEnabled()).isTrue()
    }

    @Test
    fun `Fragment - hasUsageData returns data layer value`() = runTest {
        coEvery { checkAppUsageUseCase(any()) } returns true
        val vm = createViewModel()

        assertThat(vm.hasUsageData("com.test.app")).isTrue()
    }

    @Test
    fun `Fragment - isTextShadowEnabled returns data layer value`() = runTest {
        coEvery { getTextShadowEnabledUseCase() } returns true
        val vm = createViewModel()

        assertThat(vm.isTextShadowEnabled()).isTrue()
    }

    // =====================================================================
    // CONTRACT 10: Composite refresh (Fragment lifecycle)
    // "Fragment calls refresh on onResume/onStart → all data updates"
    // =====================================================================

    @Test
    fun `Fragment onResume - refreshDynamicUiData updates time and events`() = runTest {
        val vm = createViewModel()
        advanceUntilIdle()

        vm.refreshDynamicUiData()
        advanceUntilIdle()

        assertThat(vm.uiState.value.timeString).isNotEqualTo("--:--")
        coVerify { observeTimeBasedEventsUseCase.refresh() }
    }

    @Test
    fun `Fragment onResume - refreshAllData updates time and refreshes apps`() = runTest {
        val vm = createViewModel()
        advanceUntilIdle()

        vm.refreshAllData()
        advanceUntilIdle()

        assertThat(vm.uiState.value.timeString).isNotEqualTo("--:--")
        coVerify { refreshAppsUseCase.invoke() }
    }

    // =====================================================================
    // CONTRACT 11: Error resilience
    // "If a use case throws, Fragment still works — gets error toast, no crash"
    // =====================================================================

    @Test
    fun `Fragment - use case failure shows error toast without crash`() = runTest {
        coEvery { recordAppLaunchUseCase(any()) } throws RuntimeException("DB error")

        val vm = createViewModel()
        advanceUntilIdle()

        val events = mutableListOf<UiEvent>()
        val job = recordEmissions(vm.event, into = events)

        vm.onAppClicked(testApp)
        advanceUntilIdle()

        // App still launches (event sent before recording)
        assertThat(events.any { it is UiEvent.LaunchApp }).isTrue()
        // Error toast shown
        assertThat(events.any { it is UiEvent.ShowToast }).isTrue()

        job.cancel()
    }

    @Test
    fun `Fragment - multiple rapid actions don't crash`() = runTest {
        coEvery { toggleFavoriteUseCase(any(), any()) } returns
                ToggleFavoriteUseCase.Result.Success.Added

        val vm = createViewModel()
        advanceUntilIdle()

        // Simulate user tapping rapidly
        repeat(5) { vm.onAppClicked(testApp) }
        repeat(3) { vm.onToggleFavorite(testApp) }
        vm.toggleSortOrder()
        vm.onFlingUp()
        vm.updateBatteryLevel(50, 100)
        vm.onAppDrawerSearchQueryChanged("test")
        vm.onAppDrawerClosed()
        advanceUntilIdle()

        // Nothing crashed — VM is still functional
        assertThat(vm.uiState.value).isNotNull()
        assertThat(vm.uiState.value.batteryString).isEqualTo("50%")
        assertThat(vm.appDrawerSearchQuery.value).isEqualTo("")
    }

    // =====================================================================
    // CONTRACT 12: End-to-end data flow
    // "Data layer change → ViewModel state update → Fragment sees it"
    // =====================================================================

    @Test
    fun `end-to-end - all data layer changes propagate to Fragment-observable state`() = runTest {
        val vm = createViewModel()
        advanceUntilIdle()

        // Simulate data layer changes across all domains
        favoriteAppsFlow.value = UiState.Success(
            FavoriteAppsResult(listOf(testApp), isFallback = false)
        )
        layoutScaleFlow.value = 0.6f
        uiColorsFlow.value = UiColorsState(textColor = 0xFFFF00)
        wallpaperStateFlow.value = WallpaperState.single("file:///test.jpg")
        timeBasedEventsFlow.value = listOf(
            TimeBasedEvent(System.currentTimeMillis(), "Standup", TimeBasedEventType.CALENDAR)
        )
        vm.updateBatteryLevel(77, 100)

        advanceUntilIdle()

        // Fragment would see all of these
        assertThat(vm.favoriteAppsState.value).isInstanceOf(UiState.Success::class.java)
        assertThat(vm.layoutScaleState.value).isEqualTo(0.6f)
        assertThat(vm.uiColorsState.value.textColor).isEqualTo(0xFFFF00)
        assertThat(vm.wallpaperState.value.hasWallpaper).isTrue()
        assertThat(vm.uiState.value.timeBasedEvents.size).isEqualTo(1)
        assertThat(vm.uiState.value.batteryString).isEqualTo("77%")
    }
}

