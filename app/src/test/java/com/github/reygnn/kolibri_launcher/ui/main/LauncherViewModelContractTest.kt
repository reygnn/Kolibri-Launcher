package com.github.reygnn.kolibri_launcher.ui.main

import android.content.Context
import android.content.Intent
import android.os.BatteryManager
import androidx.arch.core.executor.testing.InstantTaskExecutorRule
import androidx.lifecycle.SavedStateHandle
import app.cash.turbine.test
import com.github.reygnn.kolibri_launcher.R
import com.github.reygnn.kolibri_launcher.core.AppConstants
import com.github.reygnn.kolibri_launcher.data.WallpaperFileManager
import com.github.reygnn.kolibri_launcher.domain.model.AppInfo
import com.github.reygnn.kolibri_launcher.domain.model.FavoriteAppsResult
import com.github.reygnn.kolibri_launcher.domain.model.HomeSettings
import com.github.reygnn.kolibri_launcher.domain.model.TimeBasedEvent
import com.github.reygnn.kolibri_launcher.domain.model.TimeBasedEventType
import com.github.reygnn.kolibri_launcher.domain.model.UiColorsState
import com.github.reygnn.kolibri_launcher.domain.model.WallpaperState
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
import com.github.reygnn.kolibri_launcher.domain.usecase.ObserveTimeBasedEventsUseCase
import com.github.reygnn.kolibri_launcher.domain.usecase.ObserveUiColorsUseCase
import com.github.reygnn.kolibri_launcher.domain.usecase.ObserveWallpaperStateUseCase
import com.github.reygnn.kolibri_launcher.domain.usecase.RecordAppLaunchUseCase
import com.github.reygnn.kolibri_launcher.domain.usecase.RefreshAppsUseCase
import com.github.reygnn.kolibri_launcher.domain.usecase.ResetAppUsageUseCase
import com.github.reygnn.kolibri_launcher.domain.usecase.SaveWallpaperStateUseCase
import com.github.reygnn.kolibri_launcher.domain.usecase.SetChipBackgroundColorUseCase
import com.github.reygnn.kolibri_launcher.domain.usecase.SetContentTopMarginUseCase
import com.github.reygnn.kolibri_launcher.domain.usecase.SetFontBoldUseCase
import com.github.reygnn.kolibri_launcher.domain.usecase.SetLayoutScaleUseCase
import com.github.reygnn.kolibri_launcher.domain.usecase.SetTextColorUseCase
import com.github.reygnn.kolibri_launcher.domain.usecase.SetTextShadowEnabledUseCase
import com.github.reygnn.kolibri_launcher.domain.usecase.SetVerticalPaddingUseCase
import com.github.reygnn.kolibri_launcher.domain.usecase.SetWallpaperImageUseCase
import com.github.reygnn.kolibri_launcher.domain.usecase.ShowAppUseCase
import com.github.reygnn.kolibri_launcher.domain.usecase.ToggleFavoriteUseCase
import com.github.reygnn.kolibri_launcher.domain.usecase.ToggleSortOrderUseCase
import com.github.reygnn.kolibri_launcher.rule.MainDispatcherRule
import com.github.reygnn.kolibri_launcher.rule.TimberRule
import com.github.reygnn.kolibri_launcher.ui.base.UiEvent
import com.github.reygnn.kolibri_launcher.domain.model.UiState
import com.github.reygnn.kolibri_launcher.domain.model.SwipeSlot
import com.github.reygnn.kolibri_launcher.core.AppUpdateSignal
import com.github.reygnn.kolibri_launcher.ui.util.TestMode
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
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
    private lateinit var setChipBackgroundColorUseCase: SetChipBackgroundColorUseCase
    private lateinit var setLayoutScaleUseCase: SetLayoutScaleUseCase
    private lateinit var setVerticalPaddingUseCase: SetVerticalPaddingUseCase
    private lateinit var setFontBoldUseCase: SetFontBoldUseCase
    private lateinit var setContentTopMarginUseCase: SetContentTopMarginUseCase
    private lateinit var setWallpaperImageUseCase: SetWallpaperImageUseCase
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
        setChipBackgroundColorUseCase = mockk(relaxed = true)
        setLayoutScaleUseCase = mockk(relaxed = true)
        setVerticalPaddingUseCase = mockk(relaxed = true)
        setFontBoldUseCase = mockk(relaxed = true)
        setContentTopMarginUseCase = mockk(relaxed = true)
        setWallpaperImageUseCase = mockk(relaxed = true)
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
            every { favoritesAlignment } returns flowOf(AppConstants.DEFAULT_FAVORITES_ALIGNMENT)
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
            observeDoubleTapClipboardSettingUseCase = mockk(relaxed = true),
            observeTimeBasedEventsUseCase = observeTimeBasedEventsUseCase,
            observeUiColorsUseCase = observeUiColorsUseCase,
            setTextColorUseCase = setTextColorUseCase,
            setTextShadowEnabledUseCase = setTextShadowEnabledUseCase,
            setChipBackgroundColorUseCase = setChipBackgroundColorUseCase,
            observeInstalledAppsUseCase = observeInstalledAppsUseCase,
            getAutoLaunchSettingUseCase = getAutoLaunchSettingUseCase,
            observeHomeSettingsUseCase = observeHomeSettingsUseCase,
            checkAppUsageUseCase = checkAppUsageUseCase,
            getAutoShowKeyboardSettingUseCase = getAutoShowKeyboardSettingUseCase,
            getTextShadowEnabledUseCase = getTextShadowEnabledUseCase,
            getLayoutSettingsUseCase = getLayoutSettingsUseCase,
            setLayoutScaleUseCase = setLayoutScaleUseCase,
            setVerticalPaddingUseCase = setVerticalPaddingUseCase,
            setFontBoldUseCase = setFontBoldUseCase,
            setContentTopMarginUseCase = setContentTopMarginUseCase,
            setFavoritesAlignmentUseCase = mockk(relaxed = true),
            resolveAppDrawerSurfaceUseCase = mockk(relaxed = true),
            observeWallpaperStateUseCase = observeWallpaperStateUseCase,
            saveWallpaperStateUseCase = saveWallpaperStateUseCase,
            setWallpaperImageUseCase = setWallpaperImageUseCase,
            clearWallpaperUseCase = clearWallpaperUseCase,
            getFabPositionUseCase = getFabPositionUseCase,
            saveFabPositionUseCase = saveFabPositionUseCase,
            wallpaperFileManager = wallpaperFileManager,
            wallpaperFlattener = mockk(relaxed = true),
            wallpaperCompositeStore = mockk(relaxed = true),
            wallpaperCompositeCache = mockk(relaxed = true),
            appUpdateSignal = appUpdateSignal,
            monotonicClock = neverThrottlingClock(),
            savedStateHandle = SavedStateHandle(),
            context = context,
            mainDispatcher = mainDispatcherRule.testDispatcher,
            ioDispatcher = mainDispatcherRule.testDispatcher,
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
        assertNotEquals("--:--", state.timeString)
        assertNotEquals("---", state.dateString)
        assertTrue(state.timeString.contains(":"))
    }

    @Test
    fun `HomeFragment - uiState reflects battery update`() = runTest {
        val vm = createViewModel()
        advanceUntilIdle()

        vm.updateBatteryLevel(42, 100)

        assertEquals("42%", vm.uiState.value.batteryString)
    }

    @Test
    fun `HomeFragment - uiState reflects battery from intent`() = runTest {
        val vm = createViewModel()
        advanceUntilIdle()

        val intent: Intent = mockk {
            every { getIntExtra(BatteryManager.EXTRA_LEVEL, -1) } returns 95
            every { getIntExtra(BatteryManager.EXTRA_SCALE, -1) } returns 100
        }
        vm.updateBatteryLevelFromIntent(intent)

        assertEquals("95%", vm.uiState.value.batteryString)
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

        assertEquals(1, vm.uiState.value.timeBasedEvents.size)
        assertEquals("Meeting", vm.uiState.value.timeBasedEvents.first().title)
    }

    // =====================================================================
    // CONTRACT 2: HomeFragment observes favorites
    // "Fragment collects favoriteAppsState to display favorite apps on home"
    // =====================================================================

    @Test
    fun `HomeFragment - favoriteAppsState starts Loading then receives data`() = runTest {
        val vm = createViewModel()

        assertEquals(UiState.Loading, vm.favoriteAppsState.value)

        val apps = listOf(testApp)
        favoriteAppsFlow.value = UiState.Success(FavoriteAppsResult(apps, isFallback = false))
        advanceUntilIdle()

        val state = vm.favoriteAppsState.value
        assertTrue(state is UiState.Success)
        assertEquals(1, (state as UiState.Success).data.apps.size)
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
        val job = launch(UnconfinedTestDispatcher()) { vm.event.collect { events.add(it) } }

        vm.onAppClicked(testApp)
        advanceUntilIdle()

        val launchEvent = events.filterIsInstance<UiEvent.LaunchApp>().firstOrNull()
        assertNotNull(launchEvent)
        assertEquals(testApp, launchEvent!!.app)

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
            assertEquals("", awaitItem())

            vm.onAppDrawerSearchQueryChanged("calc")
            assertEquals("calc", awaitItem())

            vm.onAppDrawerSearchQueryChanged("calculator")
            assertEquals("calculator", awaitItem())
        }
    }

    @Test
    fun `AppDrawerFragment - closing drawer resets search`() = runTest {
        val vm = createViewModel()

        vm.onAppDrawerSearchQueryChanged("test")
        assertEquals("test", vm.appDrawerSearchQuery.value)

        vm.onAppDrawerClosed()
        assertEquals("", vm.appDrawerSearchQuery.value)
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
        val job = launch(UnconfinedTestDispatcher()) { vm.event.collect { events.add(it) } }

        vm.onFlingUp()
        advanceUntilIdle()

        assertTrue(events.any { it == UiEvent.ShowAppDrawer })
        job.cancel()
    }

    @Test
    fun `HomeFragment - longPress shows customization`() = runTest {
        val vm = createViewModel()
        advanceUntilIdle()

        val events = mutableListOf<UiEvent>()
        val job = launch(UnconfinedTestDispatcher()) { vm.event.collect { events.add(it) } }

        vm.onLongPress()
        advanceUntilIdle()

        assertTrue(events.any { it == UiEvent.ShowCustomizationOptions })
        job.cancel()
    }

    @Test
    fun `HomeFragment - swipe launches assigned app`() = runTest {
        coEvery { handleSwipeActionUseCase(SwipeSlot.SWIPE_FROM_RIGHT_TO_LEFT) } returns
                HandleSwipeActionUseCase.Result.LaunchApp(testApp)

        val vm = createViewModel()
        advanceUntilIdle()

        val events = mutableListOf<UiEvent>()
        val job = launch(UnconfinedTestDispatcher()) { vm.event.collect { events.add(it) } }

        vm.onSwipeFromRightToLeft()
        advanceUntilIdle()

        val launchEvent = events.filterIsInstance<UiEvent.LaunchApp>().firstOrNull()
        assertNotNull(launchEvent)
        assertEquals(testApp, launchEvent!!.app)

        job.cancel()
    }

    @Test
    fun `HomeFragment - doubleTap shortcuts emit correct events`() = runTest {
        val vm = createViewModel()
        advanceUntilIdle()

        val events = mutableListOf<UiEvent>()
        val job = launch(UnconfinedTestDispatcher()) { vm.event.collect { events.add(it) } }

        vm.onTimeDoubleClick()
        vm.onDateDoubleClick()
        vm.onBatteryDoubleClick()
        advanceUntilIdle()

        assertTrue(events.contains(UiEvent.OpenClock))
        assertTrue(events.contains(UiEvent.OpenCalendar))
        assertTrue(events.contains(UiEvent.OpenBatterySettings))

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
        val job = launch(UnconfinedTestDispatcher()) { vm.event.collect { events.add(it) } }

        vm.onToggleFavorite(testApp)
        advanceUntilIdle()

        assertTrue(events.any { it is UiEvent.ShowToastFromString })
        job.cancel()
    }

    @Test
    fun `ContextMenu - hideApp calls use case and shows toast`() = runTest {
        val vm = createViewModel()
        advanceUntilIdle()

        val events = mutableListOf<UiEvent>()
        val job = launch(UnconfinedTestDispatcher()) { vm.event.collect { events.add(it) } }

        vm.onHideApp(testApp)
        advanceUntilIdle()

        coVerify { hideAppUseCase.invoke(testApp) }
        assertTrue(events.any { it is UiEvent.ShowToastFromString })
        job.cancel()
    }

    @Test
    fun `ContextMenu - resetUsage calls use case and shows toast`() = runTest {
        val vm = createViewModel()
        advanceUntilIdle()

        val events = mutableListOf<UiEvent>()
        val job = launch(UnconfinedTestDispatcher()) { vm.event.collect { events.add(it) } }

        vm.onResetAppUsage(testApp)
        advanceUntilIdle()

        coVerify { resetAppUsageUseCase.invoke(testApp) }
        assertTrue(events.any { it is UiEvent.ShowToastFromString })
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
        assertEquals(AppConstants.DEFAULT_LAYOUT_SCALE, vm.layoutScaleState.value)
        assertEquals(AppConstants.DEFAULT_VERTICAL_PADDING_FACTOR, vm.verticalPaddingState.value)
        assertEquals(AppConstants.DEFAULT_FONT_BOLD, vm.isFontBoldState.value)
        assertEquals(0f, vm.contentTopMarginState.value)

        // Simulate settings change from data layer
        layoutScaleFlow.value = 0.8f
        verticalPaddingFlow.value = 0.5f
        fontBoldFlow.value = !AppConstants.DEFAULT_FONT_BOLD
        contentTopMarginFlow.value = 0.3f
        advanceUntilIdle()

        // Fragment would see these updates
        assertEquals(0.8f, vm.layoutScaleState.value)
        assertEquals(0.5f, vm.verticalPaddingState.value)
        assertEquals(!AppConstants.DEFAULT_FONT_BOLD, vm.isFontBoldState.value)
        assertEquals(0.3f, vm.contentTopMarginState.value)
    }


    @Test
    fun `CustomizationFragment - set actions call through to use cases`() = runTest {
        val vm = createViewModel()
        advanceUntilIdle()

        vm.onSetTextColor(0xFF0000)
        vm.onSetTextShadowEnabled(true)
        vm.onSetChipBackgroundColor(0x00FF00)
        vm.onSetLayoutScale(0.7f)
        vm.onSetVerticalPadding(0.4f)
        vm.onSetFontBold(true)
        vm.onSetContentTopMargin(0.2f)
        advanceUntilIdle()

        coVerify { setTextColorUseCase.invoke(0xFF0000) }
        coVerify { setTextShadowEnabledUseCase.invoke(true) }
        coVerify { setChipBackgroundColorUseCase.invoke(0x00FF00) }
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

        assertFalse(vm.wallpaperState.value.hasWallpaper)

        wallpaperStateFlow.value = WallpaperState(imageUri = "file:///test.jpg", scale = 1.5f)
        advanceUntilIdle()

        assertTrue(vm.wallpaperState.value.hasWallpaper)
    }

    @Test
    fun `WallpaperFragment - edit mode toggles correctly`() = runTest {
        val vm = createViewModel()

        assertFalse(vm.isWallpaperEditMode.value)

        vm.onSetWallpaperEditMode(true)
        assertTrue(vm.isWallpaperEditMode.value)

        vm.onToggleWallpaperEditMode()
        assertFalse(vm.isWallpaperEditMode.value)

        vm.onToggleWallpaperEditMode()
        assertTrue(vm.isWallpaperEditMode.value)
    }

    @Test
    fun `WallpaperFragment - clear wallpaper calls through`() = runTest {
        val vm = createViewModel()
        advanceUntilIdle()

        vm.onClearWallpaper()
        advanceUntilIdle()

        coVerify { wallpaperFileManager.clearAll() }
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

        assertTrue(vm.isAutoLaunchEnabled())
    }

    @Test
    fun `Fragment - isAutoShowKeyboardEnabled returns data layer value`() = runTest {
        coEvery { getAutoShowKeyboardSettingUseCase() } returns true
        val vm = createViewModel()

        assertTrue(vm.isAutoShowKeyboardEnabled())
    }

    @Test
    fun `Fragment - hasUsageData returns data layer value`() = runTest {
        coEvery { checkAppUsageUseCase(any()) } returns true
        val vm = createViewModel()

        assertTrue(vm.hasUsageData("com.test.app"))
    }

    @Test
    fun `Fragment - isTextShadowEnabled returns data layer value`() = runTest {
        coEvery { getTextShadowEnabledUseCase() } returns true
        val vm = createViewModel()

        assertTrue(vm.isTextShadowEnabled())
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

        assertNotEquals("--:--", vm.uiState.value.timeString)
        coVerify { observeTimeBasedEventsUseCase.refresh() }
    }

    @Test
    fun `Fragment onResume - refreshAllData updates time and refreshes apps`() = runTest {
        val vm = createViewModel()
        advanceUntilIdle()

        vm.refreshAllData()
        advanceUntilIdle()

        assertNotEquals("--:--", vm.uiState.value.timeString)
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
        val job = launch(UnconfinedTestDispatcher()) { vm.event.collect { events.add(it) } }

        vm.onAppClicked(testApp)
        advanceUntilIdle()

        // App still launches (event sent before recording)
        assertTrue(events.any { it is UiEvent.LaunchApp })
        // Error toast shown
        assertTrue(events.any { it is UiEvent.ShowToast })

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
        assertNotNull(vm.uiState.value)
        assertEquals("50%", vm.uiState.value.batteryString)
        assertEquals("", vm.appDrawerSearchQuery.value)
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
        wallpaperStateFlow.value = WallpaperState(imageUri = "file:///test.jpg")
        timeBasedEventsFlow.value = listOf(
            TimeBasedEvent(System.currentTimeMillis(), "Standup", TimeBasedEventType.CALENDAR)
        )
        vm.updateBatteryLevel(77, 100)

        advanceUntilIdle()

        // Fragment would see all of these
        assertTrue(vm.favoriteAppsState.value is UiState.Success)
        assertEquals(0.6f, vm.layoutScaleState.value)
        assertEquals(0xFFFF00, vm.uiColorsState.value.textColor)
        assertTrue(vm.wallpaperState.value.hasWallpaper)
        assertEquals(1, vm.uiState.value.timeBasedEvents.size)
        assertEquals("77%", vm.uiState.value.batteryString)
    }
}