package com.github.reygnn.kolibri_launcher.ui.main

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.BatteryManager
import androidx.arch.core.executor.testing.InstantTaskExecutorRule
import androidx.lifecycle.SavedStateHandle
import com.github.reygnn.kolibri_launcher.core.AppConstants
import com.github.reygnn.kolibri_launcher.data.WallpaperFileManager
import com.github.reygnn.kolibri_launcher.domain.model.AppInfo
import com.github.reygnn.kolibri_launcher.domain.model.WallpaperState
import com.github.reygnn.kolibri_launcher.domain.usecase.CheckAppUsageUseCase
import com.github.reygnn.kolibri_launcher.domain.usecase.ClearWallpaperUseCase
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
import com.github.reygnn.kolibri_launcher.domain.usecase.RequestLockUseCase
import com.github.reygnn.kolibri_launcher.domain.usecase.RequestNotificationsUseCase
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
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class LauncherViewModelTest {

    @get:Rule
    val instantTaskExecutorRule = InstantTaskExecutorRule()

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    @get:Rule
    val timberRule = TimberRule()

    // --- Mocked UseCases ---
    private lateinit var getFavoriteAppsUseCase: GetFavoriteAppsUseCase
    private lateinit var getDrawerAppsUseCase: GetDrawerAppsUseCase
    private lateinit var hideAppUseCase: HideAppUseCase
    private lateinit var showAppUseCase: ShowAppUseCase
    private lateinit var toggleFavoriteUseCase: ToggleFavoriteUseCase
    private lateinit var toggleSortOrderUseCase: ToggleSortOrderUseCase
    private lateinit var recordAppLaunchUseCase: RecordAppLaunchUseCase
    private lateinit var refreshAppsUseCase: RefreshAppsUseCase
    private lateinit var resetAppUsageUseCase: ResetAppUsageUseCase
    private lateinit var requestLockUseCase: RequestLockUseCase
    private lateinit var requestNotificationsUseCase: RequestNotificationsUseCase
    private lateinit var handleSwipeActionUseCase: HandleSwipeActionUseCase
    private lateinit var observeTimeBasedEventsUseCase: ObserveTimeBasedEventsUseCase
    private lateinit var observeUiColorsUseCase: ObserveUiColorsUseCase
    private lateinit var setTextColorUseCase: SetTextColorUseCase
    private lateinit var setTextShadowEnabledUseCase: SetTextShadowEnabledUseCase
    private lateinit var setChipBackgroundColorUseCase: SetChipBackgroundColorUseCase
    private lateinit var observeInstalledAppsUseCase: ObserveInstalledAppsUseCase
    private lateinit var getAutoLaunchSettingUseCase: GetAutoLaunchSettingUseCase
    private lateinit var observeHomeSettingsUseCase: ObserveHomeSettingsUseCase
    private lateinit var checkAppUsageUseCase: CheckAppUsageUseCase
    private lateinit var getAutoShowKeyboardSettingUseCase: GetAutoShowKeyboardSettingUseCase
    private lateinit var getTextShadowEnabledUseCase: GetTextShadowEnabledUseCase
    private lateinit var getLayoutSettingsUseCase: GetLayoutSettingsUseCase
    private lateinit var setLayoutScaleUseCase: SetLayoutScaleUseCase
    private lateinit var setVerticalPaddingUseCase: SetVerticalPaddingUseCase
    private lateinit var setFontBoldUseCase: SetFontBoldUseCase
    private lateinit var setContentTopMarginUseCase: SetContentTopMarginUseCase
    private lateinit var observeWallpaperStateUseCase: ObserveWallpaperStateUseCase
    private lateinit var saveWallpaperStateUseCase: SaveWallpaperStateUseCase
    private lateinit var setWallpaperImageUseCase: SetWallpaperImageUseCase
    private lateinit var clearWallpaperUseCase: ClearWallpaperUseCase
    private lateinit var wallpaperFileManager: WallpaperFileManager
    private lateinit var appUpdateSignal: AppUpdateSignal
    private lateinit var context: Context
    private lateinit var testMode: TestMode

    private val testApp: AppInfo = mockk {
        every { packageName } returns "com.test.app"
        every { displayName } returns "Test App"
    }

    @Before
    fun setUp() {
        context = mockk {
            every { registerReceiver(any(), any(), any<Int>()) } returns null
            every { getString(any<Int>(), any<Any>()) } returns "formatted"
            every { getString(any<Int>()) } returns "string"
        }

        getFavoriteAppsUseCase = mockk {
            every { favoriteApps } returns MutableStateFlow(UiState.Loading)
        }
        getDrawerAppsUseCase = mockk(relaxed = true)
        hideAppUseCase = mockk(relaxed = true)
        showAppUseCase = mockk(relaxed = true)
        toggleFavoriteUseCase = mockk(relaxed = true)
        toggleSortOrderUseCase = mockk(relaxed = true)
        recordAppLaunchUseCase = mockk(relaxed = true)
        refreshAppsUseCase = mockk(relaxed = true)
        resetAppUsageUseCase = mockk(relaxed = true)
        requestLockUseCase = mockk(relaxed = true)
        requestNotificationsUseCase = mockk(relaxed = true)
        handleSwipeActionUseCase = mockk(relaxed = true)

        observeTimeBasedEventsUseCase = mockk(relaxed = true)
        every { observeTimeBasedEventsUseCase.invoke() } returns emptyFlow()

        observeUiColorsUseCase = mockk(relaxed = true)
        every { observeUiColorsUseCase.invoke(any()) } returns emptyFlow()

        setTextColorUseCase = mockk(relaxed = true)
        setTextShadowEnabledUseCase = mockk(relaxed = true)
        setChipBackgroundColorUseCase = mockk(relaxed = true)

        observeInstalledAppsUseCase = mockk(relaxed = true)
        every { observeInstalledAppsUseCase.invoke() } returns emptyFlow()

        getAutoLaunchSettingUseCase = mockk(relaxed = true)

        observeHomeSettingsUseCase = mockk(relaxed = true)
        every { observeHomeSettingsUseCase.invoke() } returns emptyFlow()

        checkAppUsageUseCase = mockk(relaxed = true)
        getAutoShowKeyboardSettingUseCase = mockk(relaxed = true)
        getTextShadowEnabledUseCase = mockk(relaxed = true)

        getLayoutSettingsUseCase = mockk {
            every { layoutScale } returns flowOf(AppConstants.DEFAULT_LAYOUT_SCALE)
            every { verticalPadding } returns flowOf(AppConstants.DEFAULT_VERTICAL_PADDING_FACTOR)
            every { isFontBold } returns flowOf(AppConstants.DEFAULT_FONT_BOLD)
            every { contentTopMargin } returns flowOf(0f)
        }

        setLayoutScaleUseCase = mockk(relaxed = true)
        setVerticalPaddingUseCase = mockk(relaxed = true)
        setFontBoldUseCase = mockk(relaxed = true)
        setContentTopMarginUseCase = mockk(relaxed = true)

        observeWallpaperStateUseCase = mockk(relaxed = true)
        every { observeWallpaperStateUseCase.invoke() } returns emptyFlow()

        saveWallpaperStateUseCase = mockk(relaxed = true)
        setWallpaperImageUseCase = mockk(relaxed = true)
        clearWallpaperUseCase = mockk(relaxed = true)
        wallpaperFileManager = mockk(relaxed = true)

        appUpdateSignal = mockk {
            every { events } returns MutableSharedFlow()
        }

        testMode = mockk {
            every { isEnabled } returns true
        }
    }

    private fun createViewModel() = LauncherViewModel(
        getFavoriteAppsUseCase = getFavoriteAppsUseCase,
        getDrawerAppsUseCase = getDrawerAppsUseCase,
        hideAppUseCase = hideAppUseCase,
        toggleFavoriteUseCase = toggleFavoriteUseCase,
        requestLockUseCase = requestLockUseCase,
        requestNotificationsUseCase = requestNotificationsUseCase,
        recordAppLaunchUseCase = recordAppLaunchUseCase,
        refreshAppsUseCase = refreshAppsUseCase,
        resetAppUsageUseCase = resetAppUsageUseCase,
        showAppUseCase = showAppUseCase,
        toggleSortOrderUseCase = toggleSortOrderUseCase,
        handleSwipeActionUseCase = handleSwipeActionUseCase,
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
        observeWallpaperStateUseCase = observeWallpaperStateUseCase,
        saveWallpaperStateUseCase = saveWallpaperStateUseCase,
        setWallpaperImageUseCase = setWallpaperImageUseCase,
        clearWallpaperUseCase = clearWallpaperUseCase,
        wallpaperFileManager = wallpaperFileManager,
        appUpdateSignal = appUpdateSignal,
        savedStateHandle = SavedStateHandle(),
        context = context,
        mainDispatcher = mainDispatcherRule.testDispatcher,
        testMode = testMode
    )

    // ===========================================
    // COMBINED UI STATE (combine() test)
    // ===========================================

    @Test
    fun `uiState combines clock delegate values`() = runTest {
        val vm = createViewModel()
        advanceUntilIdle()

        val state = vm.uiState.value

        assertNotEquals("--:--", state.timeString)
        assertNotEquals("---", state.dateString)
    }

    // ===========================================
    // DELEGATION: CLOCK
    // ===========================================

    @Test
    fun `refreshTimeNow delegates to clockDelegate`() = runTest {
        val vm = createViewModel()
        advanceUntilIdle()

        vm.refreshTimeNow()
        advanceUntilIdle()

        assertNotEquals("--:--", vm.uiState.value.timeString)
    }

    @Test
    fun `updateBatteryLevel delegates to clockDelegate`() = runTest {
        val vm = createViewModel()
        advanceUntilIdle()

        vm.updateBatteryLevel(85, 100)
        advanceUntilIdle()

        assertEquals("85%", vm.uiState.value.batteryString)
    }

    @Test
    fun `updateBatteryLevelFromIntent delegates to clockDelegate`() = runTest {
        val vm = createViewModel()
        advanceUntilIdle()

        val intent: Intent = mockk {
            every { getIntExtra(BatteryManager.EXTRA_LEVEL, -1) } returns 60
            every { getIntExtra(BatteryManager.EXTRA_SCALE, -1) } returns 100
        }

        vm.updateBatteryLevelFromIntent(intent)
        advanceUntilIdle()

        assertEquals("60%", vm.uiState.value.batteryString)
    }

    // ===========================================
    // DELEGATION: APP MANAGEMENT
    // ===========================================

    @Test
    fun `onAppClicked delegates and sends LaunchApp event`() = runTest {
        val vm = createViewModel()
        advanceUntilIdle()

        val collectedEvents = mutableListOf<UiEvent>()
        val collectorJob = launch(UnconfinedTestDispatcher()) {
            vm.event.collect { collectedEvents.add(it) }
        }

        vm.onAppClicked(testApp)
        advanceUntilIdle()

        assertTrue(collectedEvents.any { it is UiEvent.LaunchApp })
        collectorJob.cancel()
    }

    @Test
    fun `onHideApp delegates to appDelegate`() = runTest {
        val vm = createViewModel()
        advanceUntilIdle()

        vm.onHideApp(testApp)
        advanceUntilIdle()

        coVerify { hideAppUseCase.invoke(testApp) }
    }

    @Test
    fun `onShowApp delegates to appDelegate`() = runTest {
        val vm = createViewModel()
        advanceUntilIdle()

        vm.onShowApp(testApp)
        advanceUntilIdle()

        coVerify { showAppUseCase.invoke(testApp) }
    }

    @Test
    fun `toggleSortOrder delegates to appDelegate`() = runTest {
        val vm = createViewModel()
        advanceUntilIdle()

        vm.toggleSortOrder()
        advanceUntilIdle()

        coVerify { toggleSortOrderUseCase.invoke() }
    }

    @Test
    fun `onAppDrawerSearchQueryChanged updates query`() = runTest {
        val vm = createViewModel()
        advanceUntilIdle()

        vm.onAppDrawerSearchQueryChanged("test query")

        assertEquals("test query", vm.appDrawerSearchQuery.value)
    }

    @Test
    fun `onAppDrawerClosed resets query`() = runTest {
        val vm = createViewModel()
        advanceUntilIdle()

        vm.onAppDrawerSearchQueryChanged("test")
        vm.onAppDrawerClosed()

        assertEquals("", vm.appDrawerSearchQuery.value)
    }

    @Test
    fun `favoriteAppsState is initially Loading`() = runTest {
        val vm = createViewModel()

        assertEquals(UiState.Loading, vm.favoriteAppsState.value)
    }

    @Test
    fun `isAutoLaunchEnabled delegates to appDelegate`() = runTest {
        coEvery { getAutoLaunchSettingUseCase() } returns true

        val vm = createViewModel()
        advanceUntilIdle()

        assertTrue(vm.isAutoLaunchEnabled())
    }

    @Test
    fun `hasUsageData delegates to appDelegate`() = runTest {
        coEvery { checkAppUsageUseCase(any()) } returns true

        val vm = createViewModel()
        advanceUntilIdle()

        assertTrue(vm.hasUsageData("com.test.app"))
    }

    // ===========================================
    // DELEGATION: GESTURES
    // ===========================================

    @Test
    fun `onFlingUp delegates and sends ShowAppDrawer`() = runTest {
        val vm = createViewModel()
        advanceUntilIdle()

        val collectedEvents = mutableListOf<UiEvent>()
        val collectorJob = launch(UnconfinedTestDispatcher()) {
            vm.event.collect { collectedEvents.add(it) }
        }

        vm.onFlingUp()
        advanceUntilIdle()

        assertTrue(collectedEvents.any { it == UiEvent.ShowAppDrawer })
        collectorJob.cancel()
    }

    @Test
    fun `onLongPress delegates and sends ShowCustomizationOptions`() = runTest {
        val vm = createViewModel()
        advanceUntilIdle()

        val collectedEvents = mutableListOf<UiEvent>()
        val collectorJob = launch(UnconfinedTestDispatcher()) {
            vm.event.collect { collectedEvents.add(it) }
        }

        vm.onLongPress()
        advanceUntilIdle()

        assertTrue(collectedEvents.any { it == UiEvent.ShowCustomizationOptions })
        collectorJob.cancel()
    }

    @Test
    fun `onTimeDoubleClick delegates and sends OpenClock`() = runTest {
        val vm = createViewModel()
        advanceUntilIdle()

        val collectedEvents = mutableListOf<UiEvent>()
        val collectorJob = launch(UnconfinedTestDispatcher()) {
            vm.event.collect { collectedEvents.add(it) }
        }

        vm.onTimeDoubleClick()
        advanceUntilIdle()

        assertTrue(collectedEvents.any { it == UiEvent.OpenClock })
        collectorJob.cancel()
    }

    @Test
    fun `onDateDoubleClick delegates and sends OpenCalendar`() = runTest {
        val vm = createViewModel()
        advanceUntilIdle()

        val collectedEvents = mutableListOf<UiEvent>()
        val collectorJob = launch(UnconfinedTestDispatcher()) {
            vm.event.collect { collectedEvents.add(it) }
        }

        vm.onDateDoubleClick()
        advanceUntilIdle()

        assertTrue(collectedEvents.any { it == UiEvent.OpenCalendar })
        collectorJob.cancel()
    }

    @Test
    fun `onBatteryDoubleClick delegates and sends OpenBatterySettings`() = runTest {
        val vm = createViewModel()
        advanceUntilIdle()

        val collectedEvents = mutableListOf<UiEvent>()
        val collectorJob = launch(UnconfinedTestDispatcher()) {
            vm.event.collect { collectedEvents.add(it) }
        }

        vm.onBatteryDoubleClick()
        advanceUntilIdle()

        assertTrue(collectedEvents.any { it == UiEvent.OpenBatterySettings })
        collectorJob.cancel()
    }

    @Test
    fun `isLockingInProgress is initially false`() = runTest {
        val vm = createViewModel()

        assertFalse(vm.isLockingInProgress.value)
    }

    // ===========================================
    // DELEGATION: THEMING
    // ===========================================

    @Test
    fun `onSetTextColor delegates to themingDelegate`() = runTest {
        val vm = createViewModel()
        advanceUntilIdle()

        vm.onSetTextColor(0xFF0000)
        advanceUntilIdle()

        coVerify { setTextColorUseCase.invoke(0xFF0000) }
    }

    @Test
    fun `onSetTextShadowEnabled delegates to themingDelegate`() = runTest {
        val vm = createViewModel()
        advanceUntilIdle()

        vm.onSetTextShadowEnabled(true)
        advanceUntilIdle()

        coVerify { setTextShadowEnabledUseCase.invoke(true) }
    }

    @Test
    fun `onSetChipBackgroundColor delegates to themingDelegate`() = runTest {
        val vm = createViewModel()
        advanceUntilIdle()

        vm.onSetChipBackgroundColor(0x00FF00)
        advanceUntilIdle()

        coVerify { setChipBackgroundColorUseCase.invoke(0x00FF00) }
    }

    @Test
    fun `isTextShadowEnabled delegates to themingDelegate`() = runTest {
        coEvery { getTextShadowEnabledUseCase() } returns true

        val vm = createViewModel()
        advanceUntilIdle()

        assertTrue(vm.isTextShadowEnabled())
    }

    // ===========================================
    // DELEGATION: LAYOUT
    // ===========================================

    @Test
    fun `layoutScaleState starts with default`() = runTest {
        val vm = createViewModel()
        advanceUntilIdle()

        assertEquals(AppConstants.DEFAULT_LAYOUT_SCALE, vm.layoutScaleState.value)
    }

    @Test
    fun `onSetLayoutScale delegates to layoutDelegate`() = runTest {
        val vm = createViewModel()
        advanceUntilIdle()

        val scale = (AppConstants.LAYOUT_SCALE_MIN + AppConstants.LAYOUT_SCALE_MAX) / 2f
        vm.onSetLayoutScale(scale)
        advanceUntilIdle()

        coVerify { setLayoutScaleUseCase.invoke(scale) }
    }

    @Test
    fun `onSetVerticalPadding delegates to layoutDelegate`() = runTest {
        val vm = createViewModel()
        advanceUntilIdle()

        val padding = (AppConstants.VERTICAL_PADDING_SCALE_MIN + AppConstants.VERTICAL_PADDING_SCALE_MAX) / 2f
        vm.onSetVerticalPadding(padding)
        advanceUntilIdle()

        coVerify { setVerticalPaddingUseCase.invoke(padding) }
    }

    @Test
    fun `onSetFontBold delegates to layoutDelegate`() = runTest {
        val vm = createViewModel()
        advanceUntilIdle()

        vm.onSetFontBold(true)
        advanceUntilIdle()

        coVerify { setFontBoldUseCase.invoke(true) }
    }

    @Test
    fun `onResetLayoutSettings resets all to defaults`() = runTest {
        val vm = createViewModel()
        advanceUntilIdle()

        vm.onResetLayoutSettings()
        advanceUntilIdle()

        coVerify { setLayoutScaleUseCase.invoke(AppConstants.DEFAULT_LAYOUT_SCALE) }
        coVerify { setVerticalPaddingUseCase.invoke(AppConstants.DEFAULT_VERTICAL_PADDING_FACTOR) }
        coVerify { setFontBoldUseCase.invoke(AppConstants.DEFAULT_FONT_BOLD) }
        coVerify { setContentTopMarginUseCase.invoke(AppConstants.DEFAULT_TOP_MARGIN) }
    }

    // ===========================================
    // DELEGATION: WALLPAPER
    // ===========================================

    @Test
    fun `wallpaperState starts with NONE`() = runTest {
        val vm = createViewModel()

        assertEquals(WallpaperState.NONE, vm.wallpaperState.value)
    }

    @Test
    fun `isWallpaperEditMode starts false`() = runTest {
        val vm = createViewModel()

        assertFalse(vm.isWallpaperEditMode.value)
    }

    @Test
    fun `onSetWallpaperEditMode delegates to wallpaperDelegate`() = runTest {
        val vm = createViewModel()
        advanceUntilIdle()

        vm.onSetWallpaperEditMode(true)

        assertTrue(vm.isWallpaperEditMode.value)
    }

    @Test
    fun `onToggleWallpaperEditMode toggles state`() = runTest {
        val vm = createViewModel()
        advanceUntilIdle()

        assertFalse(vm.isWallpaperEditMode.value)

        vm.onToggleWallpaperEditMode()
        assertTrue(vm.isWallpaperEditMode.value)

        vm.onToggleWallpaperEditMode()
        assertFalse(vm.isWallpaperEditMode.value)
    }

    @Test
    fun `onClearWallpaper delegates to wallpaperDelegate`() = runTest {
        val vm = createViewModel()
        advanceUntilIdle()

        vm.onClearWallpaper()
        advanceUntilIdle()

        coVerify { wallpaperFileManager.clearAll() }
        coVerify { clearWallpaperUseCase.invoke() }
    }

    @Test
    fun `onSetWallpaperImage delegates to wallpaperDelegate`() = runTest {
        val uri: Uri = mockk()
        val internalUriString = "file:///internal/wallpaper.jpg"
        val internalUri: Uri = mockk(relaxed = true)
        every { internalUri.toString() } returns internalUriString
        coEvery { wallpaperFileManager.copyToInternal(any()) } returns internalUri

        val vm = createViewModel()
        advanceUntilIdle()

        vm.onSetWallpaperImage(uri)
        advanceUntilIdle()

        coVerify { wallpaperFileManager.copyToInternal(uri) }
        coVerify { setWallpaperImageUseCase.invoke(internalUriString) }
    }

    // ===========================================
    // COMPOSITE REFRESH
    // ===========================================

    @Test
    fun `refreshDynamicUiData updates time and refreshes events`() = runTest {
        val vm = createViewModel()
        advanceUntilIdle()

        vm.refreshDynamicUiData()
        advanceUntilIdle()

        assertNotEquals("--:--", vm.uiState.value.timeString)
        coVerify { observeTimeBasedEventsUseCase.refresh() }
    }

    @Test
    fun `refreshAllData updates time and refreshes apps`() = runTest {
        val vm = createViewModel()
        advanceUntilIdle()

        vm.refreshAllData()
        advanceUntilIdle()

        assertNotEquals("--:--", vm.uiState.value.timeString)
        coVerify { refreshAppsUseCase.invoke() }
    }

    // ===========================================
    // API SURFACE COMPLETENESS
    // ===========================================

    @Test
    fun `all public state properties are accessible`() = runTest {
        val vm = createViewModel()
        advanceUntilIdle()

        vm.uiState.value
        vm.favoriteAppsState.value
        vm.uiColorsState.value
        vm.layoutScaleState.value
        vm.verticalPaddingState.value
        vm.isFontBoldState.value
        vm.contentTopMarginState.value
        vm.wallpaperState.value
        vm.isWallpaperEditMode.value
        vm.isLockingInProgress.value
        vm.appDrawerSearchQuery.value
        vm.maxFavoritesOnHome.value

        assertTrue(true)
    }
}