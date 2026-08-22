package com.github.reygnn.kolibri_launcher.ui.main

import android.content.Context
import android.content.Intent
import android.os.BatteryManager
import androidx.arch.core.executor.testing.InstantTaskExecutorRule
import androidx.lifecycle.SavedStateHandle
import com.github.reygnn.kolibri_launcher.R
import com.github.reygnn.kolibri_launcher.core.AppConstants
import com.github.reygnn.kolibri_launcher.domain.model.AppInfo
import com.github.reygnn.kolibri_launcher.domain.model.FavoriteAppsResult
import com.github.reygnn.kolibri_launcher.domain.model.WallpaperState
import com.github.reygnn.kolibri_launcher.domain.usecase.GetFabPositionUseCase
import com.github.reygnn.kolibri_launcher.domain.usecase.GetFavoriteAppsUseCase
import com.github.reygnn.kolibri_launcher.domain.usecase.GetLayoutSettingsUseCase
import com.github.reygnn.kolibri_launcher.domain.usecase.HandleSwipeActionUseCase
import com.github.reygnn.kolibri_launcher.domain.usecase.HideAppUseCase
import com.github.reygnn.kolibri_launcher.domain.usecase.ObserveHomeSettingsUseCase
import com.github.reygnn.kolibri_launcher.domain.usecase.ObserveInstalledAppsUseCase
import com.github.reygnn.kolibri_launcher.domain.usecase.ObserveTimeBasedEventsUseCase
import com.github.reygnn.kolibri_launcher.domain.usecase.ObserveUiColorsUseCase
import com.github.reygnn.kolibri_launcher.domain.usecase.ObserveWallpaperStateUseCase
import com.github.reygnn.kolibri_launcher.domain.usecase.RecordAppLaunchUseCase
import com.github.reygnn.kolibri_launcher.domain.usecase.RefreshAppsUseCase
import com.github.reygnn.kolibri_launcher.domain.usecase.SetChipBackgroundColorUseCase
import com.github.reygnn.kolibri_launcher.domain.usecase.SetContentTopMarginUseCase
import com.github.reygnn.kolibri_launcher.domain.usecase.SetFontBoldUseCase
import com.github.reygnn.kolibri_launcher.domain.usecase.SetLayoutScaleUseCase
import com.github.reygnn.kolibri_launcher.domain.usecase.SetTextColorUseCase
import com.github.reygnn.kolibri_launcher.domain.usecase.SetTextShadowEnabledUseCase
import com.github.reygnn.kolibri_launcher.domain.usecase.SetVerticalPaddingUseCase
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
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test

/**
 * Security-focused tests for LauncherViewModel.
 * Tests NaN/Infinity floats, malicious strings, intent manipulation,
 * concurrent access, and use case failure handling.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class LauncherViewModelSecurityTest {

    @get:Rule
    val instantTaskExecutorRule = InstantTaskExecutorRule()

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    @get:Rule
    val timberRule = TimberRule()

    private lateinit var setLayoutScaleUseCase: SetLayoutScaleUseCase
    private lateinit var setVerticalPaddingUseCase: SetVerticalPaddingUseCase
    private lateinit var setContentTopMarginUseCase: SetContentTopMarginUseCase
    private lateinit var setFontBoldUseCase: SetFontBoldUseCase
    private lateinit var setTextColorUseCase: SetTextColorUseCase
    private lateinit var setTextShadowEnabledUseCase: SetTextShadowEnabledUseCase
    private lateinit var setChipBackgroundColorUseCase: SetChipBackgroundColorUseCase
    private lateinit var toggleFavoriteUseCase: ToggleFavoriteUseCase
    private lateinit var hideAppUseCase: HideAppUseCase
    private lateinit var recordAppLaunchUseCase: RecordAppLaunchUseCase
    private lateinit var toggleSortOrderUseCase: ToggleSortOrderUseCase
    private lateinit var handleSwipeActionUseCase: HandleSwipeActionUseCase
    private lateinit var refreshAppsUseCase: RefreshAppsUseCase

    private lateinit var viewModel: LauncherViewModel

    @Before
    fun setUp() {
        setLayoutScaleUseCase = mockk(relaxed = true)
        setVerticalPaddingUseCase = mockk(relaxed = true)
        setContentTopMarginUseCase = mockk(relaxed = true)
        setFontBoldUseCase = mockk(relaxed = true)
        setTextColorUseCase = mockk(relaxed = true)
        setTextShadowEnabledUseCase = mockk(relaxed = true)
        setChipBackgroundColorUseCase = mockk(relaxed = true)
        toggleFavoriteUseCase = mockk(relaxed = true)
        hideAppUseCase = mockk(relaxed = true)
        recordAppLaunchUseCase = mockk(relaxed = true)
        toggleSortOrderUseCase = mockk(relaxed = true)
        handleSwipeActionUseCase = mockk(relaxed = true)
        refreshAppsUseCase = mockk(relaxed = true)

        val context: Context = mockk(relaxed = true) {
            every { registerReceiver(any(), any(), any<Int>()) } returns null
        }

        val getFavoriteAppsUseCase: GetFavoriteAppsUseCase = mockk {
            every { favoriteApps } returns MutableStateFlow(
                UiState.Success(FavoriteAppsResult(emptyList(), false))
            )
        }

        val observeTimeBasedEventsUseCase: ObserveTimeBasedEventsUseCase = mockk(relaxed = true)
        every { observeTimeBasedEventsUseCase.invoke() } returns emptyFlow()

        val observeUiColorsUseCase: ObserveUiColorsUseCase = mockk(relaxed = true)
        every { observeUiColorsUseCase.invoke() } returns emptyFlow()

        val observeInstalledAppsUseCase: ObserveInstalledAppsUseCase = mockk(relaxed = true)
        every { observeInstalledAppsUseCase.invoke() } returns emptyFlow()

        val observeHomeSettingsUseCase: ObserveHomeSettingsUseCase = mockk(relaxed = true)
        every { observeHomeSettingsUseCase.invoke() } returns emptyFlow()

        val observeWallpaperStateUseCase: ObserveWallpaperStateUseCase = mockk(relaxed = true)
        every { observeWallpaperStateUseCase.invoke() } returns flowOf(WallpaperState.NONE)

        val getLayoutSettingsUseCase: GetLayoutSettingsUseCase = mockk {
            every { layoutScale } returns flowOf(1.0f)
            every { verticalPadding } returns flowOf(1.0f)
            every { isFontBold } returns flowOf(false)
            every { contentTopMargin } returns flowOf(0f)
            every { favoritesAlignment } returns flowOf(AppConstants.DEFAULT_FAVORITES_ALIGNMENT)
        }

        val appUpdateSignal: AppUpdateSignal = mockk {
            every { events } returns MutableSharedFlow(extraBufferCapacity = 1)
        }

        viewModel = LauncherViewModel(
            getFavoriteAppsUseCase = getFavoriteAppsUseCase,
            getDrawerAppsUseCase = mockk(relaxed = true),
            hideAppUseCase = hideAppUseCase,
            toggleFavoriteUseCase = toggleFavoriteUseCase,
            recordAppLaunchUseCase = recordAppLaunchUseCase,
            refreshAppsUseCase = refreshAppsUseCase,
            resetAppUsageUseCase = mockk(relaxed = true),
            showAppUseCase = mockk(relaxed = true),
            toggleSortOrderUseCase = toggleSortOrderUseCase,
            handleSwipeActionUseCase = handleSwipeActionUseCase,
            getRecentAppsUseCase = mockk(relaxed = true),
            observeTimeBasedEventsUseCase = observeTimeBasedEventsUseCase,
            observeUiColorsUseCase = observeUiColorsUseCase,
            setTextColorUseCase = setTextColorUseCase,
            setTextShadowEnabledUseCase = setTextShadowEnabledUseCase,
            setChipBackgroundColorUseCase = setChipBackgroundColorUseCase,
            observeInstalledAppsUseCase = observeInstalledAppsUseCase,
            getAutoLaunchSettingUseCase = mockk(relaxed = true),
            observeHomeSettingsUseCase = observeHomeSettingsUseCase,
            checkAppUsageUseCase = mockk(relaxed = true),
            getAutoShowKeyboardSettingUseCase = mockk(relaxed = true),
            getTextShadowEnabledUseCase = mockk(relaxed = true),
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
            saveWallpaperStateUseCase = mockk(relaxed = true),
            setWallpaperImageUseCase = mockk(relaxed = true),
            clearWallpaperUseCase = mockk(relaxed = true),
            getFabPositionUseCase = mockk<GetFabPositionUseCase>(relaxed = true).also { every { it.invoke() } returns emptyFlow() },
            saveFabPositionUseCase = mockk(relaxed = true),
            wallpaperFileManager = mockk(relaxed = true),
            wallpaperFlattener = mockk(relaxed = true),
            wallpaperCompositeCache = mockk(relaxed = true),
            wallpaperBitmapLuminance = mockk(relaxed = true),
            compositeLuminanceSignal = mockk(relaxed = true),
            appUpdateSignal = appUpdateSignal,
            monotonicClock = neverThrottlingClock(),
            savedStateHandle = SavedStateHandle(),
            context = context,
            mainDispatcher = mainDispatcherRule.testDispatcher,
            ioDispatcher = mainDispatcherRule.testDispatcher,
            testMode = TestMode(isEnabled = true)
        )
    }

    private fun createTestApp(
        packageName: String = "com.test.app",
        displayName: String = "Test App"
    ) = AppInfo(
        originalName = displayName, displayName = displayName,
        packageName = packageName, className = ".MainActivity",
        isFavorite = false
    )

    // ========================================================================
    // SECTION 1: FLOAT ATTACKS (NaN, Infinity)
    // ========================================================================

    // --- CONTENT TOP MARGIN ---

    @Test
    fun `attack - NaN contentTopMargin - should be coerced to min`() = runTest {
        viewModel.onSetContentTopMargin(Float.NaN)
        advanceUntilIdle()
        coVerify { setContentTopMarginUseCase.invoke(AppConstants.CONTENT_TOP_MARGIN_SCALE_MIN) }
    }

    @Test
    fun `attack - Negative Infinity contentTopMargin - should be coerced to min`() = runTest {
        viewModel.onSetContentTopMargin(Float.NEGATIVE_INFINITY)
        advanceUntilIdle()
        coVerify { setContentTopMarginUseCase.invoke(AppConstants.CONTENT_TOP_MARGIN_SCALE_MIN) }
    }

    @Test
    fun `attack - extremely small float contentTopMargin - should be coerced to min`() = runTest {
        viewModel.onSetContentTopMargin(-Float.MAX_VALUE)
        advanceUntilIdle()
        coVerify { setContentTopMarginUseCase.invoke(AppConstants.CONTENT_TOP_MARGIN_SCALE_MIN) }
    }

    @Test
    fun `attack - extremely large float contentTopMargin - should be coerced to max`() = runTest {
        viewModel.onSetContentTopMargin(Float.MAX_VALUE)
        advanceUntilIdle()
        coVerify { setContentTopMarginUseCase.invoke(AppConstants.CONTENT_TOP_MARGIN_SCALE_MAX) }
    }

    @Test
    fun `attack - Positive Infinity contentTopMargin - should be coerced to max`() = runTest {
        viewModel.onSetContentTopMargin(Float.POSITIVE_INFINITY)
        advanceUntilIdle()
        coVerify { setContentTopMarginUseCase.invoke(AppConstants.CONTENT_TOP_MARGIN_SCALE_MAX) }
    }

    // --- VERTICAL PADDING ---

    @Test
    fun `attack - NaN verticalPadding - should be coerced to min`() = runTest {
        viewModel.onSetVerticalPadding(Float.NaN)
        advanceUntilIdle()
        coVerify { setVerticalPaddingUseCase.invoke(AppConstants.VERTICAL_PADDING_SCALE_MIN) }
    }

    @Test
    fun `attack - Negative Infinity verticalPadding - should be coerced to min`() = runTest {
        viewModel.onSetVerticalPadding(Float.NEGATIVE_INFINITY)
        advanceUntilIdle()
        coVerify { setVerticalPaddingUseCase.invoke(AppConstants.VERTICAL_PADDING_SCALE_MIN) }
    }

    @Test
    fun `attack - extremely small float verticalPadding - should be coerced to min`() = runTest {
        viewModel.onSetVerticalPadding(-Float.MAX_VALUE)
        advanceUntilIdle()
        coVerify { setVerticalPaddingUseCase.invoke(AppConstants.VERTICAL_PADDING_SCALE_MIN) }
    }

    @Test
    fun `attack - extremely large float verticalPadding - should be coerced to max`() = runTest {
        viewModel.onSetVerticalPadding(Float.MAX_VALUE)
        advanceUntilIdle()
        coVerify { setVerticalPaddingUseCase.invoke(AppConstants.VERTICAL_PADDING_SCALE_MAX) }
    }

    @Test
    fun `attack - Positive Infinity verticalPadding - should be coerced to max`() = runTest {
        viewModel.onSetVerticalPadding(Float.POSITIVE_INFINITY)
        advanceUntilIdle()
        coVerify { setVerticalPaddingUseCase.invoke(AppConstants.VERTICAL_PADDING_SCALE_MAX) }
    }

    // --- LAYOUT SCALE ---

    @Test
    fun `attack - NaN layoutScale - should be coerced to min`() = runTest {
        viewModel.onSetLayoutScale(Float.NaN)
        advanceUntilIdle()
        coVerify { setLayoutScaleUseCase.invoke(AppConstants.LAYOUT_SCALE_MIN) }
    }

    @Test
    fun `attack - Negative Infinity layoutScale - should be coerced to min`() = runTest {
        viewModel.onSetLayoutScale(Float.NEGATIVE_INFINITY)
        advanceUntilIdle()
        coVerify { setLayoutScaleUseCase.invoke(AppConstants.LAYOUT_SCALE_MIN) }
    }

    @Test
    fun `attack - extremely small float layoutScale - should be coerced to min`() = runTest {
        viewModel.onSetLayoutScale(-Float.MAX_VALUE)
        advanceUntilIdle()
        coVerify { setLayoutScaleUseCase.invoke(AppConstants.LAYOUT_SCALE_MIN) }
    }

    @Test
    fun `attack - extremely large float layoutScale - should be coerced to max`() = runTest {
        viewModel.onSetLayoutScale(Float.MAX_VALUE)
        advanceUntilIdle()
        coVerify { setLayoutScaleUseCase.invoke(AppConstants.LAYOUT_SCALE_MAX) }
    }

    @Test
    fun `attack - Positive Infinity layoutScale - should be coerced to max`() = runTest {
        viewModel.onSetLayoutScale(Float.POSITIVE_INFINITY)
        advanceUntilIdle()
        coVerify { setLayoutScaleUseCase.invoke(AppConstants.LAYOUT_SCALE_MAX) }
    }

    // --- HAPPY PATH ---

    @Test
    fun `valid input - layoutScale inside range - passes through unchanged`() = runTest {
        val valid = (AppConstants.LAYOUT_SCALE_MIN + AppConstants.LAYOUT_SCALE_MAX) / 2
        viewModel.onSetLayoutScale(valid)
        advanceUntilIdle()
        coVerify { setLayoutScaleUseCase.invoke(valid) }
    }

    @Test
    fun `valid input - verticalPadding inside range - passes through unchanged`() = runTest {
        val valid = (AppConstants.VERTICAL_PADDING_SCALE_MIN + AppConstants.VERTICAL_PADDING_SCALE_MAX) / 2
        viewModel.onSetVerticalPadding(valid)
        advanceUntilIdle()
        coVerify { setVerticalPaddingUseCase.invoke(valid) }
    }

    @Test
    fun `valid input - contentTopMargin inside range - passes through unchanged`() = runTest {
        val valid = (AppConstants.CONTENT_TOP_MARGIN_SCALE_MIN + AppConstants.CONTENT_TOP_MARGIN_SCALE_MAX) / 2
        viewModel.onSetContentTopMargin(valid)
        advanceUntilIdle()
        coVerify { setContentTopMarginUseCase.invoke(valid) }
    }

    // ========================================================================
    // SECTION 2: BATTERY ATTACKS
    // ========================================================================

    @Test
    fun `attack - battery scale zero - no division by zero crash`() = runTest {
        viewModel.updateBatteryLevel(level = 50, scale = 0)
        assertEquals("---%", viewModel.uiState.value.batteryString)
    }

    @Test
    fun `attack - battery negative scale - shows fallback`() = runTest {
        viewModel.updateBatteryLevel(level = 50, scale = -1)
        assertEquals("---%", viewModel.uiState.value.batteryString)
    }

    @Test
    fun `attack - battery negative level - shows fallback`() = runTest {
        viewModel.updateBatteryLevel(level = -1, scale = 100)
        assertEquals("---%", viewModel.uiState.value.batteryString)
    }

    @Test
    fun `attack - battery MAX_VALUE - no overflow`() = runTest {
        viewModel.updateBatteryLevel(level = Int.MAX_VALUE, scale = Int.MAX_VALUE)
        assertEquals("100%", viewModel.uiState.value.batteryString)
    }

    @Test
    fun `attack - battery level greater than scale - shows over 100`() = runTest {
        viewModel.updateBatteryLevel(level = 150, scale = 100)
        assertEquals("150%", viewModel.uiState.value.batteryString)
    }

    // ========================================================================
    // SECTION 3: INTENT MANIPULATION
    // ========================================================================

    @Test
    fun `attack - null intent - shows fallback battery`() = runTest {
        viewModel.updateBatteryLevelFromIntent(null)
        assertEquals("---%", viewModel.uiState.value.batteryString)
    }

    @Test
    fun `attack - intent without extras - shows fallback battery`() = runTest {
        val emptyIntent: Intent = mockk {
            every { getIntExtra(BatteryManager.EXTRA_LEVEL, -1) } returns -1
            every { getIntExtra(BatteryManager.EXTRA_SCALE, -1) } returns -1
        }
        viewModel.updateBatteryLevelFromIntent(emptyIntent)
        assertEquals("---%", viewModel.uiState.value.batteryString)
    }

    @Test
    fun `attack - intent with manipulated level - handles gracefully`() = runTest {
        val maliciousIntent: Intent = mockk {
            every { getIntExtra(BatteryManager.EXTRA_LEVEL, -1) } returns Int.MIN_VALUE
            every { getIntExtra(BatteryManager.EXTRA_SCALE, -1) } returns 100
        }
        viewModel.updateBatteryLevelFromIntent(maliciousIntent)
        assertTrue(viewModel.uiState.value.batteryString.isNotEmpty())
    }

    // ========================================================================
    // SECTION 4: STRING ATTACKS
    // ========================================================================

    @Test
    fun `attack - extremely long search query - no crash`() = runTest {
        val longQuery = "A".repeat(1024 * 1024)
        viewModel.onAppDrawerSearchQueryChanged(longQuery)
        assertEquals(longQuery, viewModel.appDrawerSearchQuery.value)
    }

    @Test
    fun `attack - search query with null bytes`() = runTest {
        val malicious = "search\u0000term"
        viewModel.onAppDrawerSearchQueryChanged(malicious)
        assertEquals(malicious, viewModel.appDrawerSearchQuery.value)
    }

    @Test
    fun `attack - search query with unicode exploits`() = runTest {
        val malicious = "\u202Eevil\u200B\u200Csearch"
        viewModel.onAppDrawerSearchQueryChanged(malicious)
        assertEquals(malicious, viewModel.appDrawerSearchQuery.value)
    }

    @Test
    fun `attack - empty search query`() = runTest {
        viewModel.onAppDrawerSearchQueryChanged("")
        assertEquals("", viewModel.appDrawerSearchQuery.value)
    }

    // ========================================================================
    // SECTION 5: USECASE FAILURE ATTACKS
    // ========================================================================

    @Test
    fun `attack - toggleFavorite throws - shows error toast`() = runTest {
        coEvery { toggleFavoriteUseCase(any(), any()) } throws RuntimeException("Crash")

        val events = mutableListOf<UiEvent>()
        val job = launch(UnconfinedTestDispatcher()) { viewModel.event.collect { events.add(it) } }

        viewModel.onToggleFavorite(createTestApp())
        advanceUntilIdle()
        job.cancel()

        assertTrue(events.any { it is UiEvent.ShowToast })
    }

    @Test
    fun `attack - hideApp throws - shows error toast`() = runTest {
        coEvery { hideAppUseCase(any()) } throws RuntimeException("Crash")

        val events = mutableListOf<UiEvent>()
        val job = launch(UnconfinedTestDispatcher()) { viewModel.event.collect { events.add(it) } }

        viewModel.onHideApp(createTestApp())
        advanceUntilIdle()
        job.cancel()

        assertTrue(events.any { it is UiEvent.ShowToast })
    }

    @Test
    fun `attack - recordAppLaunch throws - shows error toast`() = runTest {
        coEvery { recordAppLaunchUseCase(any()) } throws RuntimeException("Crash")

        val events = mutableListOf<UiEvent>()
        val job = launch(UnconfinedTestDispatcher()) { viewModel.event.collect { events.add(it) } }

        viewModel.onAppClicked(createTestApp())
        advanceUntilIdle()
        job.cancel()

        assertTrue(events.any { it is UiEvent.ShowToast && it.messageResId == R.string.error_launching_app })
    }

    @Test
    fun `attack - toggleSortOrder throws - shows error toast`() = runTest {
        coEvery { toggleSortOrderUseCase() } throws RuntimeException("Crash")

        val events = mutableListOf<UiEvent>()
        val job = launch(UnconfinedTestDispatcher()) { viewModel.event.collect { events.add(it) } }

        viewModel.toggleSortOrder()
        advanceUntilIdle()
        job.cancel()

        assertTrue(events.any { it is UiEvent.ShowToast })
    }

    @Test
    fun `attack - setTextColor throws - shows error toast`() = runTest {
        coEvery { setTextColorUseCase(any()) } throws RuntimeException("Crash")

        val events = mutableListOf<UiEvent>()
        val job = launch(UnconfinedTestDispatcher()) { viewModel.event.collect { events.add(it) } }

        viewModel.onSetTextColor(0xFF0000)
        advanceUntilIdle()
        job.cancel()

        assertTrue(events.any { it is UiEvent.ShowToast })
    }

    @Test
    fun `attack - handleSwipeAction throws - no crash`() = runTest {
        coEvery { handleSwipeActionUseCase(any()) } throws RuntimeException("Crash")

        viewModel.onSwipeFromRightToLeft()
        advanceUntilIdle()

        viewModel.onSwipeFromLeftToRight()
        advanceUntilIdle()
        // No crash = success
    }

    // ========================================================================
    // SECTION 6: COLOR ATTACKS
    // ========================================================================

    @Test
    fun `attack - negative color value - passes through`() = runTest {
        viewModel.onSetTextColor(-1)
        advanceUntilIdle()
        coVerify { setTextColorUseCase.invoke(-1) }
    }

    @Test
    fun `attack - MAX_INT color - passes through`() = runTest {
        viewModel.onSetTextColor(Int.MAX_VALUE)
        advanceUntilIdle()
        coVerify { setTextColorUseCase.invoke(Int.MAX_VALUE) }
    }

    @Test
    fun `attack - MIN_INT color - passes through`() = runTest {
        viewModel.onSetTextColor(Int.MIN_VALUE)
        advanceUntilIdle()
        coVerify { setTextColorUseCase.invoke(Int.MIN_VALUE) }
    }

    // ========================================================================
    // SECTION 7: APP INFO ATTACKS
    // ========================================================================

    @Test
    fun `attack - app with empty packageName - handles gracefully`() = runTest {
        val malicious = createTestApp(packageName = "", displayName = "Malicious")

        val events = mutableListOf<UiEvent>()
        val job = launch(UnconfinedTestDispatcher()) { viewModel.event.collect { events.add(it) } }

        viewModel.onAppClicked(malicious)
        advanceUntilIdle()
        job.cancel()

        assertTrue(events.any { it is UiEvent.LaunchApp })
    }

    @Test
    fun `attack - app with extremely long displayName - no crash`() = runTest {
        val malicious = createTestApp(displayName = "A".repeat(10_000))

        viewModel.onToggleFavorite(malicious)
        advanceUntilIdle()
    }

    @Test
    fun `attack - app with SQL injection in packageName - no crash`() = runTest {
        val malicious = createTestApp(packageName = "com.app'; DROP TABLE --")

        viewModel.onHideApp(malicious)
        advanceUntilIdle()
    }

    // ========================================================================
    // SECTION 8: CONCURRENT ACCESS
    // ========================================================================

    @Test
    fun `attack - rapid successive calls - no crash`() = runTest {
        val app = createTestApp()
        repeat(100) {
            viewModel.onAppClicked(app)
            viewModel.onToggleFavorite(app)
            viewModel.onSetLayoutScale(it / 100f)
        }
        advanceUntilIdle()
    }

    @Test
    fun `attack - interleaved state updates - all calls go through`() = runTest {
        viewModel.onSetLayoutScale(0.5f)
        viewModel.onSetVerticalPadding(0.5f)
        viewModel.onSetContentTopMargin(0.5f)
        viewModel.onSetFontBold(true)
        advanceUntilIdle()

        coVerify { setLayoutScaleUseCase.invoke(0.5f) }
        coVerify { setVerticalPaddingUseCase.invoke(0.5f) }
        coVerify { setContentTopMarginUseCase.invoke(0.5f) }
        coVerify { setFontBoldUseCase.invoke(true) }
    }

    // ========================================================================
    // SECTION 9: EDGE CASES
    // ========================================================================

    @Test
    fun `edge case - refreshAllData called multiple times - no crash`() = runTest {
        repeat(10) { viewModel.refreshAllData() }
        advanceUntilIdle()
    }

    @Test
    fun `edge case - onAppDrawerClosed resets query`() = runTest {
        viewModel.onAppDrawerSearchQueryChanged("query")
        assertEquals("query", viewModel.appDrawerSearchQuery.value)

        viewModel.onAppDrawerClosed()
        assertEquals("", viewModel.appDrawerSearchQuery.value)
    }

    @Test
    fun `edge case - updateTimeAndDate - no crash`() = runTest {
        viewModel.updateTimeAndDate()
        assertTrue(viewModel.uiState.value.timeString.isNotEmpty())
    }
}