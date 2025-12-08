package com.github.reygnn.kolibri_launcher.ui

import android.content.Context
import android.content.Intent
import android.os.BatteryManager
import androidx.arch.core.executor.testing.InstantTaskExecutorRule
import androidx.lifecycle.SavedStateHandle
import com.github.reygnn.kolibri_launcher.R
import com.github.reygnn.kolibri_launcher.core.AppConstants
import com.github.reygnn.kolibri_launcher.rule.MainDispatcherRule
import com.github.reygnn.kolibri_launcher.domain.model.AppInfo
import com.github.reygnn.kolibri_launcher.domain.model.FavoriteAppsResult
import com.github.reygnn.kolibri_launcher.domain.usecase.*
import com.github.reygnn.kolibri_launcher.rules.TimberRule
import com.github.reygnn.kolibri_launcher.ui.base.UiEvent
import com.github.reygnn.kolibri_launcher.ui.base.UiState
import com.github.reygnn.kolibri_launcher.ui.main.LauncherViewModel
import com.github.reygnn.kolibri_launcher.ui.util.AppUpdateSignal
import com.github.reygnn.kolibri_launcher.ui.util.TestMode
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.mockito.Mock
import org.mockito.Mockito.`when`
import org.mockito.Mockito.mock
import org.mockito.MockitoAnnotations
// WICHTIG: Mockito-Kotlin Imports nutzen!
import org.mockito.kotlin.any
import org.mockito.kotlin.anyOrNull
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever

/**
 * Security-focused Unit-Tests für LauncherViewModel.
 * * Behebt Timing-Probleme mit SharedFlow durch Nutzung von UnconfinedTestDispatcher
 * und vermeidet "Misused Matcher" Exceptions durch korrekte Mockito-Nutzung.
 */
@ExperimentalCoroutinesApi
class LauncherViewModelSecurityTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()
    @get:Rule
    val instantTaskExecutorRule = InstantTaskExecutorRule()
    @get:Rule
    val timberRule = TimberRule()

    // Mocked UseCases
    @Mock lateinit var getFavoriteAppsUseCase: GetFavoriteAppsUseCase
    @Mock lateinit var getDrawerAppsUseCase: GetDrawerAppsUseCase
    @Mock lateinit var hideAppUseCase: HideAppUseCase
    @Mock lateinit var toggleFavoriteUseCase: ToggleFavoriteUseCase
    @Mock lateinit var requestLockUseCase: RequestLockUseCase
    @Mock lateinit var requestNotificationsUseCase: RequestNotificationsUseCase
    @Mock lateinit var recordAppLaunchUseCase: RecordAppLaunchUseCase
    @Mock lateinit var refreshAppsUseCase: RefreshAppsUseCase
    @Mock lateinit var resetAppUsageUseCase: ResetAppUsageUseCase
    @Mock lateinit var showAppUseCase: ShowAppUseCase
    @Mock lateinit var toggleSortOrderUseCase: ToggleSortOrderUseCase
    @Mock lateinit var handleSwipeActionUseCase: HandleSwipeActionUseCase
    @Mock lateinit var observeTimeBasedEventsUseCase: ObserveTimeBasedEventsUseCase
    @Mock lateinit var observeUiColorsUseCase: ObserveUiColorsUseCase
    @Mock lateinit var setTextColorUseCase: SetTextColorUseCase
    @Mock lateinit var setTextShadowEnabledUseCase: SetTextShadowEnabledUseCase
    @Mock lateinit var setChipBackgroundColorUseCase: SetChipBackgroundColorUseCase
    @Mock lateinit var observeInstalledAppsUseCase: ObserveInstalledAppsUseCase
    @Mock lateinit var getAutoLaunchSettingUseCase: GetAutoLaunchSettingUseCase
    @Mock lateinit var observeHomeSettingsUseCase: ObserveHomeSettingsUseCase
    @Mock lateinit var checkAppUsageUseCase: CheckAppUsageUseCase
    @Mock lateinit var getAutoShowKeyboardSettingUseCase: GetAutoShowKeyboardSettingUseCase
    @Mock lateinit var getTextShadowEnabledUseCase: GetTextShadowEnabledUseCase
    @Mock lateinit var getSplitModeThresholdUseCase: GetSplitModeThresholdUseCase
    @Mock lateinit var getLayoutSettingsUseCase: GetLayoutSettingsUseCase
    @Mock lateinit var setLayoutScaleUseCase: SetLayoutScaleUseCase
    @Mock lateinit var setVerticalPaddingUseCase: SetVerticalPaddingUseCase
    @Mock lateinit var setFontBoldUseCase: SetFontBoldUseCase
    @Mock lateinit var setContentTopMarginUseCase: SetContentTopMarginUseCase
    @Mock lateinit var appUpdateSignal: AppUpdateSignal
    @Mock lateinit var context: Context

    private lateinit var viewModel: LauncherViewModel
    private val testMode = TestMode(isEnabled = true)

    @Before
    fun setUp() {
        MockitoAnnotations.openMocks(this)
        setupDefaultMocks()

        viewModel = LauncherViewModel(
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
            getSplitModeThresholdUseCase = getSplitModeThresholdUseCase,
            getLayoutSettingsUseCase = getLayoutSettingsUseCase,
            setLayoutScaleUseCase = setLayoutScaleUseCase,
            setVerticalPaddingUseCase = setVerticalPaddingUseCase,
            setFontBoldUseCase = setFontBoldUseCase,
            setContentTopMarginUseCase = setContentTopMarginUseCase,
            appUpdateSignal = appUpdateSignal,
            SavedStateHandle(),
            context = context,
            mainDispatcher = mainDispatcherRule.testDispatcher,
            testMode = testMode
        )
    }

    private fun setupDefaultMocks() {
        `when`(getFavoriteAppsUseCase.favoriteApps).thenReturn(
            MutableStateFlow(UiState.Success(FavoriteAppsResult(emptyList(), false)))
        )
        `when`(observeTimeBasedEventsUseCase.invoke()).thenReturn(flowOf(emptyList()))
        `when`(observeUiColorsUseCase.invoke(anyOrNull())).thenReturn(flowOf())
        `when`(getSplitModeThresholdUseCase.invoke()).thenReturn(flowOf(0))
        `when`(getLayoutSettingsUseCase.layoutScale).thenReturn(flowOf(1.0f))
        `when`(getLayoutSettingsUseCase.verticalPadding).thenReturn(flowOf(1.0f))
        `when`(getLayoutSettingsUseCase.isFontBold).thenReturn(flowOf(false))
        `when`(getLayoutSettingsUseCase.contentTopMargin).thenReturn(flowOf(0f))
        `when`(appUpdateSignal.events).thenReturn(MutableSharedFlow())

        `when`(context.getString(any())).thenReturn("Test String")
        `when`(context.getString(any(), any())).thenReturn("Test String with args")
    }

    // ========================================================================
    // SECTION 1: FLOAT ATTACKS (NaN, Infinity)
    // ========================================================================

    // CONTENT TOP MARGIN ATTACKS

    @Test
    fun `attack - NaN contentTopMargin - should be coerced to min value`() = runTest {
        viewModel.onSetContentTopMargin(Float.NaN)
        advanceUntilIdle()
        verify(setContentTopMarginUseCase).invoke(AppConstants.CONTENT_TOP_MARGIN_SCALE_MIN)
    }

    @Test
    fun `attack - Negative Infinity contentTopMargin - should be coerced to min`() = runTest {
        viewModel.onSetContentTopMargin(Float.NEGATIVE_INFINITY)
        advanceUntilIdle()
        verify(setContentTopMarginUseCase).invoke(AppConstants.CONTENT_TOP_MARGIN_SCALE_MIN)
    }

    @Test
    fun `attack - extremely small float contentTopMargin - should be coerced to min`() = runTest {
        viewModel.onSetContentTopMargin(-Float.MAX_VALUE)
        advanceUntilIdle()
        verify(setContentTopMarginUseCase).invoke(AppConstants.CONTENT_TOP_MARGIN_SCALE_MIN)
    }

    @Test
    fun `attack - extremely large float contentTopMargin - should be coerced to max`() = runTest {
        viewModel.onSetContentTopMargin(Float.MAX_VALUE)
        advanceUntilIdle()
        verify(setContentTopMarginUseCase).invoke(AppConstants.CONTENT_TOP_MARGIN_SCALE_MAX)
    }

    @Test
    fun `attack - Positive Infinity contentTopMargin - should be coerced to max`() = runTest {
        viewModel.onSetContentTopMargin(Float.POSITIVE_INFINITY)
        advanceUntilIdle()
        verify(setContentTopMarginUseCase).invoke(AppConstants.CONTENT_TOP_MARGIN_SCALE_MAX)
    }

    // SECTION: VERTICAL PADDING ATTACKS

    @Test
    fun `attack - NaN verticalPadding - should be coerced to min value`() = runTest {
        viewModel.onSetVerticalPadding(Float.NaN)
        advanceUntilIdle()
        verify(setVerticalPaddingUseCase).invoke(AppConstants.VERTICAL_PADDING_SCALE_MIN)
    }

    @Test
    fun `attack - Negative Infinity verticalPadding - should be coerced to min`() = runTest {
        viewModel.onSetVerticalPadding(Float.NEGATIVE_INFINITY)
        advanceUntilIdle()
        verify(setVerticalPaddingUseCase).invoke(AppConstants.VERTICAL_PADDING_SCALE_MIN)
    }

    @Test
    fun `attack - extremely small float verticalPadding - should be coerced to min`() = runTest {
        viewModel.onSetVerticalPadding(-Float.MAX_VALUE)
        advanceUntilIdle()
        verify(setVerticalPaddingUseCase).invoke(AppConstants.VERTICAL_PADDING_SCALE_MIN)
    }

    @Test
    fun `attack - extremely large float verticalPadding - should be coerced to max`() = runTest {
        viewModel.onSetVerticalPadding(Float.MAX_VALUE)
        advanceUntilIdle()
        verify(setVerticalPaddingUseCase).invoke(AppConstants.VERTICAL_PADDING_SCALE_MAX)
    }

    @Test
    fun `attack - Positive Infinity verticalPadding - should be coerced to max`() = runTest {
        viewModel.onSetVerticalPadding(Float.POSITIVE_INFINITY)
        advanceUntilIdle()
        verify(setVerticalPaddingUseCase).invoke(AppConstants.VERTICAL_PADDING_SCALE_MAX)
    }

    // SECTION: LAYOUT SCALE ATTACKS

    @Test
    fun `attack - NaN layoutScale - should be coerced to min value`() = runTest {
        val nanValue = Float.NaN
        viewModel.onSetLayoutScale(nanValue)
        advanceUntilIdle()
        verify(setLayoutScaleUseCase).invoke(AppConstants.LAYOUT_SCALE_MIN)
    }

    @Test
    fun `attack - Negative Infinity layoutScale - should be coerced to min`() = runTest {
        viewModel.onSetLayoutScale(Float.NEGATIVE_INFINITY)
        advanceUntilIdle()
        verify(setLayoutScaleUseCase).invoke(AppConstants.LAYOUT_SCALE_MIN)
    }

    @Test
    fun `attack - extremely small float - should be coerced to min`() = runTest {
        viewModel.onSetLayoutScale(-Float.MAX_VALUE)
        advanceUntilIdle()
        verify(setLayoutScaleUseCase).invoke(AppConstants.LAYOUT_SCALE_MIN)
    }

    @Test
    fun `attack - extremely large float - should be coerced to max`() = runTest {
        viewModel.onSetLayoutScale(Float.MAX_VALUE)
        advanceUntilIdle()
        verify(setLayoutScaleUseCase).invoke(AppConstants.LAYOUT_SCALE_MAX)
    }

    @Test
    fun `attack - Positive Infinity layoutScale - should be coerced to max`() = runTest {
        viewModel.onSetLayoutScale(Float.POSITIVE_INFINITY)
        advanceUntilIdle()
        verify(setLayoutScaleUseCase).invoke(AppConstants.LAYOUT_SCALE_MAX)
    }

    // ========================================================================
    // SECTION 1.1: HAPPY PATH (Valid Inputs)
    // ========================================================================

    @Test
    fun `valid input - layoutScale inside range - should pass through unchanged`() = runTest {
        val validValue = (AppConstants.LAYOUT_SCALE_MIN + AppConstants.LAYOUT_SCALE_MAX) / 2

        viewModel.onSetLayoutScale(validValue)
        advanceUntilIdle()

        verify(setLayoutScaleUseCase).invoke(validValue)
    }

    @Test
    fun `valid input - verticalPadding inside range - should pass through unchanged`() = runTest {
        val validValue = (AppConstants.VERTICAL_PADDING_SCALE_MIN + AppConstants.VERTICAL_PADDING_SCALE_MAX) / 2

        viewModel.onSetVerticalPadding(validValue)
        advanceUntilIdle()

        verify(setVerticalPaddingUseCase).invoke(validValue)
    }

    @Test
    fun `valid input - contentTopMargin inside range - should pass through unchanged`() = runTest {
        val validValue = (AppConstants.CONTENT_TOP_MARGIN_SCALE_MIN + AppConstants.CONTENT_TOP_MARGIN_SCALE_MAX) / 2

        viewModel.onSetContentTopMargin(validValue)
        advanceUntilIdle()

        verify(setContentTopMarginUseCase).invoke(validValue)
    }


    // ========================================================================
    // SECTION 2: INTEGER / BATTERY ATTACKS
    // ========================================================================

    @Test
    fun `attack - battery scale zero - should not crash (division by zero)`() = runTest {
        viewModel.updateBatteryLevel(level = 50, scale = 0)

        val state = viewModel.uiState.value
        assertThat(state.batteryString).isEqualTo("---%")
    }

    @Test
    fun `attack - battery negative scale - should show fallback`() = runTest {
        viewModel.updateBatteryLevel(level = 50, scale = -1)

        val state = viewModel.uiState.value
        assertThat(state.batteryString).isEqualTo("---%")
    }

    @Test
    fun `attack - battery negative level - should show fallback`() = runTest {
        viewModel.updateBatteryLevel(level = -1, scale = 100)

        val state = viewModel.uiState.value
        assertThat(state.batteryString).isEqualTo("---%")
    }

    @Test
    fun `attack - battery MAX_VALUE - should not overflow`() = runTest {
        viewModel.updateBatteryLevel(level = Int.MAX_VALUE, scale = Int.MAX_VALUE)

        val state = viewModel.uiState.value
        assertThat(state.batteryString).isEqualTo("100%")
    }

    @Test
    fun `attack - battery level greater than scale - should show over 100 percent`() = runTest {
        viewModel.updateBatteryLevel(level = 150, scale = 100)

        val state = viewModel.uiState.value
        assertThat(state.batteryString).isEqualTo("150%")
    }

    // ========================================================================
    // SECTION 3: INTENT MANIPULATION ATTACKS
    // ========================================================================

    @Test
    fun `attack - null intent - should show fallback battery`() = runTest {
        viewModel.updateBatteryLevelFromIntent(null)

        val state = viewModel.uiState.value
        assertThat(state.batteryString).isEqualTo("---%")
    }

    @Test
    fun `attack - intent without extras - should show fallback battery`() = runTest {
        val emptyIntent = mock(Intent::class.java)
        `when`(emptyIntent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)).thenReturn(-1)
        `when`(emptyIntent.getIntExtra(BatteryManager.EXTRA_SCALE, -1)).thenReturn(-1)

        viewModel.updateBatteryLevelFromIntent(emptyIntent)

        val state = viewModel.uiState.value
        assertThat(state.batteryString).isEqualTo("---%")
    }

    @Test
    fun `attack - intent with manipulated level - should handle gracefully`() = runTest {
        val maliciousIntent = mock(Intent::class.java)
        `when`(maliciousIntent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)).thenReturn(Int.MIN_VALUE)
        `when`(maliciousIntent.getIntExtra(BatteryManager.EXTRA_SCALE, -1)).thenReturn(100)

        viewModel.updateBatteryLevelFromIntent(maliciousIntent)

        assertThat(viewModel.uiState.value.batteryString).isNotEmpty()
    }

    // ========================================================================
    // SECTION 4: STRING ATTACKS
    // ========================================================================

    @Test
    fun `attack - extremely long search query - should not crash`() = runTest {
        val longQuery = "A".repeat(1024 * 1024)
        viewModel.onAppDrawerSearchQueryChanged(longQuery)

        assertThat(viewModel.appDrawerSearchQuery.value).isEqualTo(longQuery)
    }

    @Test
    fun `attack - search query with null bytes - should handle`() = runTest {
        val maliciousQuery = "search\u0000term"
        viewModel.onAppDrawerSearchQueryChanged(maliciousQuery)

        assertThat(viewModel.appDrawerSearchQuery.value).isEqualTo(maliciousQuery)
    }

    @Test
    fun `attack - search query with unicode exploits - should handle`() = runTest {
        val maliciousQuery = "\u202Eevil\u200B\u200Csearch"
        viewModel.onAppDrawerSearchQueryChanged(maliciousQuery)

        assertThat(viewModel.appDrawerSearchQuery.value).isEqualTo(maliciousQuery)
    }

    @Test
    fun `attack - empty search query - should handle`() = runTest {
        viewModel.onAppDrawerSearchQueryChanged("")
        assertThat(viewModel.appDrawerSearchQuery.value).isEmpty()
    }

    // ========================================================================
    // SECTION 5: USECASE FAILURE ATTACKS & EVENT HANDLING
    // (Nutzt UnconfinedTestDispatcher für SharedFlow)
    // ========================================================================

    @Test
    fun `attack - toggleFavorite throws exception - should show error toast`() = runTest {
        val app = createTestApp()
        whenever(toggleFavoriteUseCase.invoke(any(), any())).thenThrow(RuntimeException("Crash"))
        val events = mutableListOf<UiEvent>()

        // Collector starten
        val job = launch(UnconfinedTestDispatcher(testScheduler)) {
            viewModel.event.collect { events.add(it) }
        }

        viewModel.onToggleFavorite(app)
        advanceUntilIdle()
        job.cancel()

        assertThat(events).isNotEmpty()
        assertThat(events.first()).isInstanceOf(UiEvent.ShowToast::class.java)
    }

    @Test
    fun `attack - hideApp throws exception - should show error toast`() = runTest {
        val app = createTestApp()
        whenever(hideAppUseCase.invoke(any())).thenThrow(RuntimeException("Crash"))
        val events = mutableListOf<UiEvent>()

        val job = launch(UnconfinedTestDispatcher(testScheduler)) {
            viewModel.event.collect { events.add(it) }
        }

        viewModel.onHideApp(app)
        advanceUntilIdle()
        job.cancel()

        assertThat(events).isNotEmpty()
        assertThat(events.first()).isInstanceOf(UiEvent.ShowToast::class.java)
    }

    @Test
    fun `attack - recordAppLaunch throws exception - should show error toast`() = runTest {
        // Arrange
        val app = createTestApp()
        whenever(recordAppLaunchUseCase.invoke(any())).thenThrow(RuntimeException("Simulated crash"))
        val events = mutableListOf<UiEvent>()

        val job = launch(UnconfinedTestDispatcher(testScheduler)) {
            viewModel.event.collect { events.add(it) }
        }

        // Act - Hier das echte Objekt 'app' nutzen!
        viewModel.onAppClicked(app)
        advanceUntilIdle()
        job.cancel()

        // Assert
        // Erwartung: Erst LaunchApp, dann ShowToast (wegen Exception)
        assertThat(events).isNotEmpty()
        // Wir prüfen, ob überhaupt ein Toast kam
        val hasErrorToast = events.any {
            it is UiEvent.ShowToast && it.messageResId == R.string.error_launching_app
        }
        assertThat(hasErrorToast).isTrue()
    }

    @Test
    fun `attack - toggleSortOrder throws exception - should show error toast`() = runTest {
        whenever(toggleSortOrderUseCase.invoke()).thenThrow(RuntimeException("Crash"))
        val events = mutableListOf<UiEvent>()

        val job = launch(UnconfinedTestDispatcher(testScheduler)) {
            viewModel.event.collect { events.add(it) }
        }

        viewModel.toggleSortOrder()
        advanceUntilIdle()
        job.cancel()

        assertThat(events).isNotEmpty()
        assertThat(events.first()).isInstanceOf(UiEvent.ShowToast::class.java)
    }

    @Test
    fun `attack - setTextColor throws exception - should show error toast`() = runTest {
        whenever(setTextColorUseCase.invoke(any())).thenThrow(RuntimeException("Crash"))
        val events = mutableListOf<UiEvent>()

        val job = launch(UnconfinedTestDispatcher(testScheduler)) {
            viewModel.event.collect { events.add(it) }
        }

        viewModel.onSetTextColor(0xFF0000)
        advanceUntilIdle()
        job.cancel()

        assertThat(events).isNotEmpty()
        assertThat(events.first()).isInstanceOf(UiEvent.ShowToast::class.java)
    }

    @Test
    fun `attack - handleSwipeAction throws exception - should not crash`() = runTest {
        whenever(handleSwipeActionUseCase.invoke(any())).thenThrow(RuntimeException("Crash"))

        // Hier erwarten wir keinen Toast, nur dass es nicht abstürzt
        viewModel.onSwipeFromRightToLeft()
        advanceUntilIdle()

        viewModel.onSwipeFromLeftToRight()
        advanceUntilIdle()
    }

    // ========================================================================
    // SECTION 6: COLOR ATTACKS
    // ========================================================================

    @Test
    fun `attack - negative color value - should pass through`() = runTest {
        viewModel.onSetTextColor(-1)
        advanceUntilIdle()
        verify(setTextColorUseCase).invoke(-1)
    }

    @Test
    fun `attack - MAX_INT color - should pass through`() = runTest {
        viewModel.onSetTextColor(Int.MAX_VALUE)
        advanceUntilIdle()
        verify(setTextColorUseCase).invoke(Int.MAX_VALUE)
    }

    @Test
    fun `attack - MIN_INT color - should pass through`() = runTest {
        viewModel.onSetTextColor(Int.MIN_VALUE)
        advanceUntilIdle()
        verify(setTextColorUseCase).invoke(Int.MIN_VALUE)
    }

    // ========================================================================
    // SECTION 7: APP INFO ATTACKS
    // ========================================================================

    @Test
    fun `attack - app with empty packageName - should handle gracefully`() = runTest {
        val maliciousApp = AppInfo(
            originalName = "Malicious", displayName = "Malicious",
            packageName = "", className = "", isSystemApp = false, isFavorite = false
        )
        val events = mutableListOf<UiEvent>()

        val job = launch(UnconfinedTestDispatcher(testScheduler)) {
            viewModel.event.collect { events.add(it) }
        }

        viewModel.onAppClicked(maliciousApp)
        advanceUntilIdle()
        job.cancel()

        assertThat(events).isNotEmpty()
        assertThat(events.first()).isInstanceOf(UiEvent.LaunchApp::class.java)
    }

    @Test
    fun `attack - app with extremely long displayName - should handle`() = runTest {
        val longName = "A".repeat(10_000)
        val maliciousApp = AppInfo(
            originalName = longName, displayName = longName,
            packageName = "com.test", className = "", isSystemApp = false, isFavorite = false
        )

        viewModel.onToggleFavorite(maliciousApp)
        advanceUntilIdle()
        // Success = No Crash
    }

    @Test
    fun `attack - app with special characters in packageName - should handle`() = runTest {
        val maliciousApp = AppInfo(
            originalName = "SQL", displayName = "SQL",
            packageName = "com.app'; DROP TABLE --", className = "",
            isSystemApp = false, isFavorite = false
        )

        viewModel.onHideApp(maliciousApp)
        advanceUntilIdle()
        // Success = No Crash
    }

    // ========================================================================
    // SECTION 8: CONCURRENT ACCESS
    // ========================================================================

    @Test
    fun `attack - rapid successive calls - should not crash`() = runTest {
        val app = createTestApp()
        repeat(100) {
            viewModel.onAppClicked(app)
            viewModel.onToggleFavorite(app)
            viewModel.onSetLayoutScale(it / 100f)
        }
        advanceUntilIdle()
    }

    @Test
    fun `attack - interleaved state updates - should maintain consistency`() = runTest {
        viewModel.onSetLayoutScale(0.5f)
        viewModel.onSetVerticalPadding(0.5f)
        viewModel.onSetContentTopMargin(0.5f)
        viewModel.onSetFontBold(true)
        advanceUntilIdle()

        verify(setLayoutScaleUseCase).invoke(0.5f)
        verify(setVerticalPaddingUseCase).invoke(0.5f)
        verify(setContentTopMarginUseCase).invoke(0.5f)
        verify(setFontBoldUseCase).invoke(true)
    }

    // ========================================================================
    // SECTION 9: EDGE CASES
    // ========================================================================

    @Test
    fun `edge case - refreshAllData called multiple times - should not crash`() = runTest {
        repeat(10) { viewModel.refreshAllData() }
        advanceUntilIdle()
    }

    @Test
    fun `edge case - onAppDrawerClosed resets query`() = runTest {
        viewModel.onAppDrawerSearchQueryChanged("query")
        assertThat(viewModel.appDrawerSearchQuery.value).isEqualTo("query")

        viewModel.onAppDrawerClosed()
        assertThat(viewModel.appDrawerSearchQuery.value).isEmpty()
    }

    @Test
    fun `edge case - updateTimeAndDate with weird locale - should not crash`() = runTest {
        viewModel.updateTimeAndDate()
        val state = viewModel.uiState.value
        assertThat(state.timeString).isNotEmpty()
    }

    // Helper
    private fun createTestApp(
        packageName: String = "com.test.app",
        displayName: String = "Test App"
    ) = AppInfo(
        originalName = displayName, displayName = displayName,
        packageName = packageName, className = ".MainActivity",
        isSystemApp = false, isFavorite = false
    )
}