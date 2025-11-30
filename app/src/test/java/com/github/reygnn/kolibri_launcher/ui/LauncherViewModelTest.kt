package com.github.reygnn.kolibri_launcher.ui

import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.os.BatteryManager
import androidx.arch.core.executor.testing.InstantTaskExecutorRule
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.asFlow
import androidx.lifecycle.asLiveData
import app.cash.turbine.test
import com.github.reygnn.kolibri_launcher.core.MainDispatcherRule
import com.github.reygnn.kolibri_launcher.R
import com.github.reygnn.kolibri_launcher.core.AppConstants
import com.github.reygnn.kolibri_launcher.domain.model.AppInfo
import com.github.reygnn.kolibri_launcher.domain.model.TimeBasedEventType
import com.github.reygnn.kolibri_launcher.domain.model.FavoriteAppsResult
import com.github.reygnn.kolibri_launcher.domain.model.HomeSettings
import com.github.reygnn.kolibri_launcher.domain.model.SortOrder
import com.github.reygnn.kolibri_launcher.domain.model.TimeBasedEvent
import com.github.reygnn.kolibri_launcher.domain.model.UiColorsState
import com.github.reygnn.kolibri_launcher.domain.model.AppLoadResult
import com.github.reygnn.kolibri_launcher.domain.usecase.CheckAppUsageUseCase
import com.github.reygnn.kolibri_launcher.domain.usecase.GetAutoLaunchSettingUseCase
import com.github.reygnn.kolibri_launcher.domain.usecase.GetAutoShowKeyboardSettingUseCase
import com.github.reygnn.kolibri_launcher.domain.usecase.GetDrawerAppsUseCase
import com.github.reygnn.kolibri_launcher.domain.usecase.GetFavoriteAppsUseCase
import com.github.reygnn.kolibri_launcher.domain.usecase.GetLayoutSettingsUseCase
import com.github.reygnn.kolibri_launcher.domain.usecase.GetSplitModeThresholdUseCase
import com.github.reygnn.kolibri_launcher.domain.usecase.GetTextShadowEnabledUseCase
import com.github.reygnn.kolibri_launcher.domain.usecase.HandleSwipeActionUseCase
import com.github.reygnn.kolibri_launcher.domain.usecase.HideAppUseCase
import com.github.reygnn.kolibri_launcher.domain.usecase.ObserveHomeSettingsUseCase
import com.github.reygnn.kolibri_launcher.domain.usecase.ObserveInstalledAppsUseCase
import com.github.reygnn.kolibri_launcher.domain.usecase.ObserveTimeBasedEventsUseCase
import com.github.reygnn.kolibri_launcher.domain.usecase.ObserveUiColorsUseCase
import com.github.reygnn.kolibri_launcher.domain.usecase.RecordAppLaunchUseCase
import com.github.reygnn.kolibri_launcher.domain.usecase.RefreshAppsUseCase
import com.github.reygnn.kolibri_launcher.domain.usecase.RequestLockUseCase
import com.github.reygnn.kolibri_launcher.domain.usecase.RequestNotificationsUseCase
import com.github.reygnn.kolibri_launcher.domain.usecase.ResetAppUsageUseCase
import com.github.reygnn.kolibri_launcher.domain.usecase.SetChipBackgroundColorUseCase
import com.github.reygnn.kolibri_launcher.domain.usecase.SetContentTopMarginUseCase
import com.github.reygnn.kolibri_launcher.domain.usecase.SetFontBoldUseCase
import com.github.reygnn.kolibri_launcher.domain.usecase.SetLayoutScaleUseCase
import com.github.reygnn.kolibri_launcher.domain.usecase.SetTextColorUseCase
import com.github.reygnn.kolibri_launcher.domain.usecase.SetTextShadowEnabledUseCase
import com.github.reygnn.kolibri_launcher.domain.usecase.SetVerticalPaddingUseCase
import com.github.reygnn.kolibri_launcher.domain.usecase.ShowAppUseCase
import com.github.reygnn.kolibri_launcher.domain.usecase.ToggleFavoriteUseCase
import com.github.reygnn.kolibri_launcher.domain.usecase.ToggleSortOrderUseCase
import com.github.reygnn.kolibri_launcher.ui.appdrawer.AppDrawerScrollIntent
import com.github.reygnn.kolibri_launcher.ui.base.UiEvent
import com.github.reygnn.kolibri_launcher.ui.base.UiState
import com.github.reygnn.kolibri_launcher.ui.main.LauncherViewModel
import com.github.reygnn.kolibri_launcher.ui.swipeactions.SwipeSlot
import com.github.reygnn.kolibri_launcher.ui.util.AppUpdateSignal
import com.github.reygnn.kolibri_launcher.ui.util.TestMode
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.mockito.Mock
import org.mockito.Mockito.mock
import org.mockito.Mockito.`when`
import org.mockito.MockitoAnnotations
import org.mockito.kotlin.any
import org.mockito.kotlin.atLeastOnce
import org.mockito.kotlin.clearInvocations
import org.mockito.kotlin.doAnswer
import org.mockito.kotlin.eq
import org.mockito.kotlin.never
import org.mockito.kotlin.times
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import java.io.IOException
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

@ExperimentalCoroutinesApi
class LauncherViewModelTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    @get:Rule
    val instantExecutorRule = InstantTaskExecutorRule()

    @Mock
    private lateinit var getFavoriteAppsUseCase: GetFavoriteAppsUseCase

    @Mock
    private lateinit var getDrawerAppsUseCase: GetDrawerAppsUseCase

    @Mock
    private lateinit var hideAppUseCase: HideAppUseCase

    @Mock
    private lateinit var toggleFavoriteUseCase: ToggleFavoriteUseCase

    @Mock
    private lateinit var requestLockUseCase: RequestLockUseCase

    @Mock
    private lateinit var requestNotificationsUseCase: RequestNotificationsUseCase

    @Mock
    private lateinit var recordAppLaunchUseCase: RecordAppLaunchUseCase

    @Mock
    private lateinit var refreshAppsUseCase: RefreshAppsUseCase

    @Mock
    private lateinit var resetAppUsageUseCase: ResetAppUsageUseCase

    @Mock
    private lateinit var showAppUseCase: ShowAppUseCase

    @Mock
    private lateinit var toggleSortOrderUseCase: ToggleSortOrderUseCase

    @Mock
    private lateinit var handleSwipeActionUseCase: HandleSwipeActionUseCase

    @Mock
    private lateinit var observeTimeBasedEventsUseCase: ObserveTimeBasedEventsUseCase

    @Mock
    private lateinit var observeUiColorsUseCase: ObserveUiColorsUseCase

    @Mock
    private lateinit var setTextColorUseCase: SetTextColorUseCase

    @Mock
    private lateinit var setTextShadowEnabledUseCase: SetTextShadowEnabledUseCase

    @Mock
    private lateinit var setChipBackgroundColorUseCase: SetChipBackgroundColorUseCase

    @Mock
    private lateinit var observeInstalledAppsUseCase: ObserveInstalledAppsUseCase

    @Mock
    private lateinit var getAutoLaunchSettingUseCase: GetAutoLaunchSettingUseCase

    @Mock
    private lateinit var getAutoShowKeyboardSettingUseCase: GetAutoShowKeyboardSettingUseCase

    @Mock
    private lateinit var checkAppUsageUseCase: CheckAppUsageUseCase

    @Mock
    private lateinit var observeHomeSettingsUseCase: ObserveHomeSettingsUseCase

    @Mock
    private lateinit var getTextShadowEnabledUseCase: GetTextShadowEnabledUseCase

    @Mock
    private lateinit var getSplitModeThresholdUseCase: GetSplitModeThresholdUseCase

    @Mock
    private lateinit var appUpdateSignal: AppUpdateSignal

    @Mock
    private lateinit var context: Context

    @Mock
    private lateinit var getLayoutSettingsUseCase: GetLayoutSettingsUseCase

    @Mock
    private lateinit var setLayoutScaleUseCase: SetLayoutScaleUseCase

    @Mock
    private lateinit var setVerticalPaddingUseCase: SetVerticalPaddingUseCase

    @Mock
    private lateinit var setFontBoldUseCase: SetFontBoldUseCase

    @Mock
    private lateinit var setContentTopMarginUseCase: SetContentTopMarginUseCase
    // --- ENDE DER MOCKS ---

    private lateinit var viewModel: LauncherViewModel

    private val app1 = AppInfo("App A", "App A", "com.a", "MainActivity")
    private val app2 = AppInfo("App B", "App B", "com.b", "MainActivity")
    private val testApps = listOf(app1, app2)

    @Before
    fun setup() {
        MockitoAnnotations.openMocks(this)

        whenever(context.registerReceiver(any(), any(), any())).thenReturn(null)
        whenever(appUpdateSignal.events).thenReturn(MutableSharedFlow())
        whenever(context.getString(any())).thenReturn("Test String")
        whenever(context.getString(any(), any())).thenReturn("Test String with args")

        whenever(getFavoriteAppsUseCase.favoriteApps).thenReturn(flowOf(UiState.Loading))
        whenever(getDrawerAppsUseCase.drawerApps).thenReturn(
            MutableStateFlow<List<AppInfo>>(emptyList()).asLiveData()
        )
        whenever(observeTimeBasedEventsUseCase.invoke(any())).thenReturn(flowOf(emptyList()))
        whenever(observeUiColorsUseCase.invoke(any())).thenReturn(flowOf(UiColorsState()))
        whenever(observeInstalledAppsUseCase.invoke()).thenReturn(flowOf(AppLoadResult.Success))

        whenever(observeHomeSettingsUseCase.invoke()).thenReturn(flowOf(HomeSettings()))
        whenever(getSplitModeThresholdUseCase.invoke()).thenReturn(flowOf(0))

        whenever(getLayoutSettingsUseCase.layoutScale).thenReturn(flowOf(AppConstants.DEFAULT_LAYOUT_SCALE))
        whenever(getLayoutSettingsUseCase.verticalPadding).thenReturn(flowOf(AppConstants.DEFAULT_VERTICAL_PADDING_FACTOR))
        whenever(getLayoutSettingsUseCase.isFontBold).thenReturn(flowOf(AppConstants.DEFAULT_FONT_BOLD))
        whenever(getLayoutSettingsUseCase.contentTopMargin).thenReturn(flowOf(0f))
    }

    private fun setupViewModel(enableTestMode: Boolean = false) {
        viewModel = LauncherViewModel(
            getFavoriteAppsUseCase,
            getDrawerAppsUseCase,
            hideAppUseCase,
            toggleFavoriteUseCase,
            requestLockUseCase,
            requestNotificationsUseCase,
            recordAppLaunchUseCase,
            refreshAppsUseCase,
            resetAppUsageUseCase,
            showAppUseCase,
            toggleSortOrderUseCase,
            handleSwipeActionUseCase,
            observeTimeBasedEventsUseCase,
            observeUiColorsUseCase,
            setTextColorUseCase,
            setTextShadowEnabledUseCase,
            setChipBackgroundColorUseCase,
            observeInstalledAppsUseCase,
            getAutoLaunchSettingUseCase,
            observeHomeSettingsUseCase,
            checkAppUsageUseCase,
            getAutoShowKeyboardSettingUseCase,
            getTextShadowEnabledUseCase,
            getSplitModeThresholdUseCase,
            getLayoutSettingsUseCase,
            setLayoutScaleUseCase,
            setVerticalPaddingUseCase,
            setFontBoldUseCase,
            setContentTopMarginUseCase,
            appUpdateSignal,
            SavedStateHandle(),
            context,
            mainDispatcher = mainDispatcherRule.testDispatcher,
            testMode = TestMode(isEnabled = enableTestMode)
        )
    }

    // ========== STANDARD TESTS ==========

    @Test
    fun `init - loads favorite apps and updates state`() = runTest {
        val favoriteApps = FavoriteAppsResult(testApps, isFallback = false)
        whenever(getFavoriteAppsUseCase.favoriteApps).thenReturn(
            flowOf(UiState.Success(favoriteApps))
        )

        setupViewModel()
        advanceUntilIdle()

        viewModel.favoriteAppsState.test {
            val state = awaitItem()
            assertTrue(state is UiState.Success)
            assertEquals(2, state.data.apps.size)
            assertFalse(state.data.isFallback)
        }
    }

    @Test
    fun `init - with fallback favorites - loads fallback state correctly`() = runTest {
        val fallbackApps = FavoriteAppsResult(testApps, isFallback = true)

        whenever(getFavoriteAppsUseCase.favoriteApps).thenReturn(
            flowOf(UiState.Success(fallbackApps))
        )

        setupViewModel(enableTestMode = true)
        advanceUntilIdle()

        // Prüfe den State statt Events
        viewModel.favoriteAppsState.test {
            val state = awaitItem()
            assertTrue(state is UiState.Success)
            assertTrue(state.data.isFallback)
            assertEquals(2, state.data.apps.size)
        }
    }

    // --- UI Event Tests (bleiben fast gleich) ---
    @Test
    fun `onFlingUp - emits ShowAppDrawer event`() = runTest {
        setupViewModel()
        viewModel.event.test {
            viewModel.onFlingUp()
            assertTrue(awaitItem() is UiEvent.ShowAppDrawer)
        }
    }

    @Test
    fun `onLongPress - emits CustomizationOptions event`() = runTest {
        setupViewModel()
        viewModel.event.test {
            viewModel.onLongPress()
            assertTrue(awaitItem() is UiEvent.ShowCustomizationOptions)
        }
    }

    @Test
    fun `onTimeDoubleClick - emits OpenClock event`() = runTest {
        setupViewModel()
        viewModel.event.test {
            viewModel.onTimeDoubleClick()
            assertTrue(awaitItem() is UiEvent.OpenClock)
        }
    }

    @Test
    fun `onDateDoubleClick - emits OpenCalendar event`() = runTest {
        setupViewModel()
        viewModel.event.test {
            viewModel.onDateDoubleClick()
            assertTrue(awaitItem() is UiEvent.OpenCalendar)
        }
    }

    @Test
    fun `onBatteryDoubleClick - emits OpenBatterySettings event`() = runTest {
        setupViewModel()
        viewModel.event.test {
            viewModel.onBatteryDoubleClick()
            assertTrue(awaitItem() is UiEvent.OpenBatterySettings)
        }
    }

    // --- ANGEPASSTE TESTS (PRÜFEN USECASES) ---

    @Test
    fun `onAppClicked - emits LaunchApp event and calls UseCases`() = runTest {
        setupViewModel()
        advanceUntilIdle()

        viewModel.event.test {
            viewModel.onAppClicked(app1)

            val launchEvent = awaitItem()
            assertTrue(launchEvent is UiEvent.LaunchApp)
            assertEquals(app1, launchEvent.app)

            advanceUntilIdle()
        }

        // Überprüfe, ob die UseCases aufgerufen wurden
        verify(recordAppLaunchUseCase).invoke(app1)
        verify(refreshAppsUseCase).invoke()
    }

    @Test
    fun `onToggleFavorite - when not favorite - calls UseCase and shows toast`() = runTest {
        // Mocke das ERGEBNIS des UseCase
        whenever(toggleFavoriteUseCase.invoke(app1, AppConstants.MAX_FALLBACK_FAVORITES_ON_HOME))
            .thenReturn(ToggleFavoriteUseCase.Result.Success(R.string.app_added_to_favorites))

        setupViewModel()
        advanceUntilIdle()

        viewModel.event.test {
            viewModel.onToggleFavorite(app1)
            advanceUntilIdle()

            val event = awaitItem()
            assertTrue(event is UiEvent.ShowToastFromString)
        }
        // Überprüfe den UseCase
        verify(toggleFavoriteUseCase).invoke(app1, AppConstants.MAX_FALLBACK_FAVORITES_ON_HOME)
    }

    @Test
    fun `onToggleFavorite - when limit reached - calls UseCase and shows limit message`() =
        runTest {
            // Mocke das ERGEBNIS des UseCase
            whenever(
                toggleFavoriteUseCase.invoke(
                    app1,
                    AppConstants.MAX_FALLBACK_FAVORITES_ON_HOME
                )
            )
                .thenReturn(ToggleFavoriteUseCase.Result.Error(R.string.favorites_limit_reached))

            setupViewModel()
            advanceUntilIdle()

            viewModel.event.test {
                viewModel.onToggleFavorite(app1)
                advanceUntilIdle()

                val event = awaitItem()
                assertTrue(event is UiEvent.ShowToastFromString)
            }
            // Überprüfe den UseCase
            verify(toggleFavoriteUseCase).invoke(app1, AppConstants.MAX_FALLBACK_FAVORITES_ON_HOME)
        }

    @Test
    fun `onHideApp - calls UseCase and shows confirmation`() = runTest {
        setupViewModel()
        advanceUntilIdle()

        viewModel.event.test {
            viewModel.onHideApp(app1)
            advanceUntilIdle()

            val event = awaitItem()
            assertTrue(event is UiEvent.ShowToastFromString)
        }
        verify(hideAppUseCase).invoke(app1)
    }

    @Test
    fun `onShowApp - calls UseCase and displays confirmation`() = runTest {
        setupViewModel()
        advanceUntilIdle()

        viewModel.event.test {
            viewModel.onShowApp(app1)
            advanceUntilIdle()

            val event = awaitItem()
            assertTrue(event is UiEvent.ShowToastFromString)
        }
        verify(showAppUseCase).invoke(app1)
    }

    @Test
    fun `onResetAppUsage - calls UseCase and shows confirmation`() = runTest {
        setupViewModel()
        advanceUntilIdle()

        viewModel.event.test {
            viewModel.onResetAppUsage(app1)
            advanceUntilIdle()

            val event = awaitItem()
            assertTrue(event is UiEvent.ShowToastFromString)
        }
        verify(resetAppUsageUseCase).invoke(app1)
    }

    @Test
    fun `toggleSortOrder - calls ToggleSortOrderUseCase`() = runTest {
        setupViewModel()
        advanceUntilIdle()

        viewModel.toggleSortOrder()
        advanceUntilIdle()

        verify(toggleSortOrderUseCase).invoke()
    }

    @Test
    fun `onDoubleTapToLock - when enabled and available - calls UseCase`() = runTest {
        // Mocke das ERGEBNIS des UseCase
        whenever(requestLockUseCase.invoke()).thenReturn(RequestLockUseCase.Result.Success)

        setupViewModel()
        advanceUntilIdle()

        viewModel.onDoubleTapToLock()
        advanceUntilIdle()

        verify(requestLockUseCase).invoke()
    }

    @Test
    fun `onDoubleTapToLock - when enabled but not available - shows accessibility dialog`() =
        runTest {
            // Mocke das ERGEBNIS des UseCase
            whenever(requestLockUseCase.invoke()).thenReturn(RequestLockUseCase.Result.ErrorAccessibility)

            setupViewModel()
            advanceUntilIdle()

            viewModel.event.test {
                viewModel.onDoubleTapToLock()
                advanceUntilIdle()

                val event = awaitItem()
                assertTrue(event is UiEvent.ShowAccessibilityDialog)
            }
            verify(requestLockUseCase).invoke()
        }

    @Test
    fun `onDoubleTapToLock - when disabled - shows enable toast once`() = runTest {
        // Mocke das ERGEBNIS des UseCase
        whenever(requestLockUseCase.invoke()).thenReturn(RequestLockUseCase.Result.ErrorDisabled)

        setupViewModel()
        advanceUntilIdle()

        viewModel.event.test {
            viewModel.onDoubleTapToLock()
            advanceUntilIdle()

            val event = awaitItem()
            assertTrue(event is UiEvent.ShowToast)
            assertEquals(R.string.toast_enable_double_tap_to_lock, event.messageResId)

            // Second call should not emit event (Logik ist im VM)
            viewModel.onDoubleTapToLock()
            advanceUntilIdle()
            expectNoEvents()
        }
        verify(requestLockUseCase, times(2)).invoke()
    }

    // --- Tests, die gleich bleiben (da sie keine Repos/UseCases aufrufen) ---
    @Test
    fun `updateTimeAndDate - updates time and date strings`() = runTest {
        setupViewModel()
        viewModel.updateTimeAndDate()
        val state = viewModel.uiState.value
        assertTrue(state.timeString.isNotEmpty())
        assertTrue(state.dateString.isNotEmpty())
    }

    // ... (alle 'updateBatteryLevel' Tests bleiben exakt gleich) ...
    @Test
    fun `updateBatteryLevel - with valid data - updates battery percentage`() = runTest {
        setupViewModel()
        viewModel.updateBatteryLevel(75, 100)
        assertEquals("75%", viewModel.uiState.value.batteryString)
    }

    // --- Angepasste Flow-Tests ---

    @Test
    fun `uiColorsState - observes ObserveUiColorsUseCase`() = runTest {
        val testColors = UiColorsState(textColor = Color.RED, shadowColor = Color.BLUE)
        whenever(observeUiColorsUseCase.invoke(any())).thenReturn(flowOf(testColors))

        setupViewModel()
        advanceUntilIdle()

        viewModel.uiColorsState.test {
            val colors = awaitItem()
            assertEquals(Color.RED, colors.textColor)
            assertEquals(Color.BLUE, colors.shadowColor)
        }
    }

    @Test
    fun `drawerApps - emits drawer apps from use case`() = runTest {
        val drawerAppsFlow = MutableStateFlow<List<AppInfo>>(testApps)
        whenever(getDrawerAppsUseCase.drawerApps).thenReturn(drawerAppsFlow.asLiveData())

        setupViewModel()

        viewModel.drawerApps.asFlow().test {
            val apps = awaitItem()
            assertEquals(2, apps.size)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `sortOrder - emits sort order from ObserveHomeSettingsUseCase`() = runTest {
        val settings = HomeSettings(sortOrder = SortOrder.TIME_WEIGHTED_USAGE)
        whenever(observeHomeSettingsUseCase.invoke()).thenReturn(flowOf(settings))

        setupViewModel()
        advanceUntilIdle()

        viewModel.sortOrder.asFlow().test {
            val order = awaitItem()
            assertEquals(SortOrder.TIME_WEIGHTED_USAGE, order)
            cancelAndIgnoreRemainingEvents()
        }
    }

    // ========== CRASH-RESISTANCE TESTS (JETZT AUF USECASES ANGEPASST) ==========

    @Test
    fun `init - when observeInstalledAppsUseCase emits Error - shows toast`() = runTest {
        whenever(observeInstalledAppsUseCase.invoke()).thenReturn(
            flowOf(AppLoadResult.Error(R.string.error_app_list_not_loaded))
        )
        setupViewModel(enableTestMode = false)
        viewModel.event.test {
            advanceUntilIdle()
            val event = awaitItem()
            assertTrue(event is UiEvent.ShowToast)
            assertEquals(R.string.error_app_list_not_loaded, event.messageResId)
        }
    }

    @Test
    fun `onAppClicked - when recordAppLaunchUseCase fails - still launches app and shows error`() =
        runTest {
            whenever(recordAppLaunchUseCase.invoke(any())).doAnswer {
                throw IOException("Cannot record")
            }
            setupViewModel()
            advanceUntilIdle()

            viewModel.event.test {
                viewModel.onAppClicked(app1)
                advanceUntilIdle()

                val launchEvent = awaitItem()
                assertTrue(launchEvent is UiEvent.LaunchApp, "Expected LaunchApp event first")

                val errorEvent = awaitItem()
                assertTrue(errorEvent is UiEvent.ShowToast, "Expected ShowToast event second")

                ensureAllEventsConsumed()
            }
        }

    @Test
    fun `onToggleFavorite - when UseCase throws - emits error`() = runTest {
        whenever(toggleFavoriteUseCase.invoke(any(), any())).doAnswer {
            throw IOException("Cannot toggle")
        }

        setupViewModel()
        advanceUntilIdle()

        viewModel.event.test {
            viewModel.onToggleFavorite(app1)
            advanceUntilIdle()
            val event = awaitItem()
            assertTrue(event is UiEvent.ShowToast)
        }
    }

    @Test
    fun `onHideApp - when UseCase throws - emits error`() = runTest {
        whenever(hideAppUseCase.invoke(any())).doAnswer {
            throw IOException("Cannot hide")
        }

        setupViewModel()
        advanceUntilIdle()

        viewModel.event.test {
            viewModel.onHideApp(app1)
            advanceUntilIdle()
            val event = awaitItem()
            assertTrue(event is UiEvent.ShowToast)
        }
    }

    @Test
    fun `onFlingLeft - when UseCase returns LaunchApp - launches app`() = runTest {
        whenever(handleSwipeActionUseCase.invoke(SwipeSlot.LEFT))
            .thenReturn(HandleSwipeActionUseCase.Result.LaunchApp(app1))

        setupViewModel()
        advanceUntilIdle()

        viewModel.event.test {
            viewModel.onFlingLeft()
            advanceUntilIdle()

            val event = awaitItem()
            assertTrue(event is UiEvent.LaunchApp)
            assertEquals(app1, event.app)
        }
        verify(handleSwipeActionUseCase).invoke(SwipeSlot.LEFT)
    }

    @Test
    fun `onFlingRight - when UseCase returns NoAction - does nothing`() = runTest {
        whenever(handleSwipeActionUseCase.invoke(SwipeSlot.RIGHT))
            .thenReturn(HandleSwipeActionUseCase.Result.NoAction)

        setupViewModel()
        advanceUntilIdle()

        viewModel.event.test {
            viewModel.onFlingRight()
            advanceUntilIdle()
            expectNoEvents()
        }
        verify(handleSwipeActionUseCase).invoke(SwipeSlot.RIGHT)
    }

    @Test
    fun `init - when calendar enabled - updates calendar event from UseCase`() = runTest {
        val testEvent = TimeBasedEvent(
            triggerTimeMillis = System.currentTimeMillis() + 10000,
            title = "Test Meeting",
            type = TimeBasedEventType.CALENDAR
        )
        val testEventList = listOf(testEvent)
        // Mocke den UseCase, der die Logik (inkl. Settings) bereits enthält
        whenever(observeTimeBasedEventsUseCase.invoke(any())).thenReturn(flowOf(testEventList))

        setupViewModel()
        advanceUntilIdle()

        val state = viewModel.uiState.value
        assertEquals(1, state.timeBasedEvents.size)
        assertEquals("Test Meeting", state.timeBasedEvents.first().title)
    }

    @Test
    fun `init - when calendar disabled - UseCase returns empty list`() = runTest {
        // Der UseCase selbst (dank 'flatMapLatest') wird eine leere Liste ausgeben
        whenever(observeTimeBasedEventsUseCase.invoke(any())).thenReturn(flowOf(emptyList()))

        setupViewModel()
        advanceUntilIdle()

        val state = viewModel.uiState.value
        assertTrue(state.timeBasedEvents.isEmpty())
    }

    @Test
    fun `onToggleFavorite - when already favorite - removes from favorites`() = runTest {
        // Mocke das ERGEBNIS des UseCase für "Remove"
        whenever(toggleFavoriteUseCase.invoke(app1, AppConstants.MAX_FALLBACK_FAVORITES_ON_HOME))
            .thenReturn(ToggleFavoriteUseCase.Result.Success(R.string.app_removed_from_favorites))

        setupViewModel()
        advanceUntilIdle()

        viewModel.event.test {
            viewModel.onToggleFavorite(app1)
            advanceUntilIdle()

            val event = awaitItem()
            assertTrue(event is UiEvent.ShowToastFromString)
            // Optional: Prüfe dass die Message "removed" enthält
        }
        verify(toggleFavoriteUseCase).invoke(app1, AppConstants.MAX_FALLBACK_FAVORITES_ON_HOME)
    }

    @Test
    fun `onFlingLeft - when app assigned but not installed - UseCase returns NoAction`() = runTest {
        // Der UseCase gibt NoAction zurück wenn die App nicht installiert ist
        whenever(handleSwipeActionUseCase.invoke(SwipeSlot.LEFT))
            .thenReturn(HandleSwipeActionUseCase.Result.NoAction)

        setupViewModel()
        advanceUntilIdle()

        viewModel.event.test {
            viewModel.onFlingLeft()
            advanceUntilIdle()
            expectNoEvents()  // Kein Event sollte emitted werden
        }
        verify(handleSwipeActionUseCase).invoke(SwipeSlot.LEFT)
    }

    @Test
    fun `onFlingRight - when app assigned but not installed - UseCase returns NoAction`() =
        runTest {
            whenever(handleSwipeActionUseCase.invoke(SwipeSlot.RIGHT))
                .thenReturn(HandleSwipeActionUseCase.Result.NoAction)

            setupViewModel()
            advanceUntilIdle()

            viewModel.event.test {
                viewModel.onFlingRight()
                advanceUntilIdle()
                expectNoEvents()
            }
            verify(handleSwipeActionUseCase).invoke(SwipeSlot.RIGHT)
        }

    @Test
    fun `init - when calendar enabled with alarm - shows alarm first chronologically`() = runTest {
        val now = System.currentTimeMillis()
        val alarm = TimeBasedEvent(
            triggerTimeMillis = now + 3600000, // in 1 Stunde
            title = "Alarm",
            type = TimeBasedEventType.ALARM
        )
        val meeting = TimeBasedEvent(
            triggerTimeMillis = now + 7200000, // in 2 Stunden
            title = "Meeting",
            type = TimeBasedEventType.CALENDAR
        )

        // Der UseCase gibt chronologisch sortierte Events zurück
        whenever(observeTimeBasedEventsUseCase.invoke(any()))
            .thenReturn(flowOf(listOf(alarm, meeting)))

        setupViewModel()
        advanceUntilIdle()

        val state = viewModel.uiState.value
        assertEquals(2, state.timeBasedEvents.size)
        assertEquals(TimeBasedEventType.ALARM, state.timeBasedEvents[0].type)
        assertEquals(TimeBasedEventType.CALENDAR, state.timeBasedEvents[1].type)
    }

    @Test
    fun `init - when only alarm enabled - shows only alarm`() = runTest {
        val alarm = TimeBasedEvent(
            triggerTimeMillis = System.currentTimeMillis() + 3600000,
            title = "Alarm",
            type = TimeBasedEventType.ALARM
        )

        // Der UseCase gibt nur Alarm zurück (weil Calendar deaktiviert)
        whenever(observeTimeBasedEventsUseCase.invoke(any()))
            .thenReturn(flowOf(listOf(alarm)))

        setupViewModel()
        advanceUntilIdle()

        val state = viewModel.uiState.value
        assertEquals(1, state.timeBasedEvents.size)
        assertEquals(TimeBasedEventType.ALARM, state.timeBasedEvents[0].type)
    }

    @Test
    fun `init - when both calendar and alarm disabled - shows no events`() = runTest {
        // Der UseCase gibt leere Liste zurück (weil beide deaktiviert)
        whenever(observeTimeBasedEventsUseCase.invoke(any()))
            .thenReturn(flowOf(emptyList()))

        setupViewModel()
        advanceUntilIdle()

        val state = viewModel.uiState.value
        assertTrue(state.timeBasedEvents.isEmpty())
    }

    @Test
    fun `onShowApp - when UseCase throws - emits error`() = runTest {
        whenever(showAppUseCase.invoke(any())).doAnswer {
            throw IOException("Cannot show")
        }

        setupViewModel()
        advanceUntilIdle()

        viewModel.event.test {
            viewModel.onShowApp(app1)
            advanceUntilIdle()

            val event = awaitItem()
            assertTrue(event is UiEvent.ShowToast)
        }
    }

    @Test
    fun `toggleSortOrder - when UseCase throws - emits error`() = runTest {
        whenever(toggleSortOrderUseCase.invoke()).doAnswer {
            throw IOException("Cannot save")
        }

        setupViewModel()
        advanceUntilIdle()

        viewModel.event.test {
            viewModel.toggleSortOrder()
            advanceUntilIdle()

            val event = awaitItem()
            assertTrue(event is UiEvent.ShowToast)
        }
    }

    @Test
    fun `updateBatteryLevelFromIntent - with null intent - does not crash`() = runTest {
        setupViewModel()
        advanceUntilIdle()

        viewModel.updateBatteryLevelFromIntent(null)

        // ViewModel sollte nicht crashen
        assertNotNull(viewModel)
    }

    @Test
    fun `updateBatteryLevelFromIntent - with invalid data - does not update battery`() = runTest {
        setupViewModel()
        advanceUntilIdle()

        val intent = Intent().apply {
            putExtra(BatteryManager.EXTRA_LEVEL, -1)
            putExtra(BatteryManager.EXTRA_SCALE, -1)
        }

        viewModel.updateBatteryLevelFromIntent(intent)

        assertEquals("---%", viewModel.uiState.value.batteryString)
    }

    @Test
    fun `updateBatteryLevel - with invalid level - sets default battery string`() = runTest {
        setupViewModel()
        advanceUntilIdle()

        viewModel.updateBatteryLevel(-1, 100)

        assertEquals("---%", viewModel.uiState.value.batteryString)
    }

    @Test
    fun `updateBatteryLevel - with zero scale - sets default battery string`() = runTest {
        setupViewModel()
        advanceUntilIdle()

        viewModel.updateBatteryLevel(75, 0)

        assertEquals("---%", viewModel.uiState.value.batteryString)
    }

    @Test
    fun `onResetAppUsage - when UseCase throws - emits error`() = runTest {
        whenever(resetAppUsageUseCase.invoke(any())).doAnswer {
            throw IOException("Cannot reset")
        }

        setupViewModel()
        advanceUntilIdle()

        viewModel.event.test {
            viewModel.onResetAppUsage(app1)
            advanceUntilIdle()

            val event = awaitItem()
            assertTrue(event is UiEvent.ShowToast)
        }
    }

    @Test
    fun `onFlingDown - when UseCase returns ErrorDisabled - shows toast once`() = runTest {
        whenever(requestNotificationsUseCase.invoke())
            .thenReturn(RequestNotificationsUseCase.Result.ErrorDisabled)

        setupViewModel()
        advanceUntilIdle()

        viewModel.event.test {
            viewModel.onFlingDown()
            advanceUntilIdle()

            val event = awaitItem()
            assertTrue(event is UiEvent.ShowToast)
            assertEquals(R.string.toast_enable_swipe_down_to_notifications, event.messageResId)

            // Second call should not emit event (ViewModel-Logik)
            viewModel.onFlingDown()
            advanceUntilIdle()
            expectNoEvents()
        }
        verify(requestNotificationsUseCase, times(2)).invoke()
    }

    @Test
    fun `onFlingDown - when UseCase returns ErrorAccessibility - shows dialog`() = runTest {
        whenever(requestNotificationsUseCase.invoke())
            .thenReturn(RequestNotificationsUseCase.Result.ErrorAccessibility)

        setupViewModel()
        advanceUntilIdle()

        viewModel.event.test {
            viewModel.onFlingDown()
            advanceUntilIdle()

            val event = awaitItem()
            assertTrue(event is UiEvent.ShowAccessibilityDialog)
        }
    }

    @Test
    fun `onAppClicked - called rapidly multiple times - handles gracefully`() = runTest {
        setupViewModel()
        advanceUntilIdle()

        // Simuliere User der wie verrückt auf die App klickt
        repeat(10) {
            viewModel.onAppClicked(app1)
        }
        advanceUntilIdle()

        // Sollte 10x recordAppLaunch aufrufen
        verify(recordAppLaunchUseCase, times(10)).invoke(app1)
        verify(refreshAppsUseCase, times(10)).invoke()
    }

    @Test
    fun `onToggleFavorite - called twice quickly - both complete without crash`() = runTest {
        whenever(toggleFavoriteUseCase.invoke(any(), any()))
            .thenReturn(ToggleFavoriteUseCase.Result.Success(R.string.app_added_to_favorites))

        setupViewModel()
        advanceUntilIdle()

        viewModel.event.test {
            viewModel.onToggleFavorite(app1)
            viewModel.onToggleFavorite(app1) // Schnell hintereinander!

            advanceUntilIdle()

            // Beide Events sollten ankommen
            awaitItem() // Erstes Event
            awaitItem() // Zweites Event
        }
    }

    @Test
    fun `multiple simultaneous operations - all complete successfully`() = runTest {
        whenever(toggleFavoriteUseCase.invoke(any(), any()))
            .thenReturn(ToggleFavoriteUseCase.Result.Success(R.string.app_added_to_favorites))

        setupViewModel()
        advanceUntilIdle()

        // Starte mehrere Operationen gleichzeitig
        launch { viewModel.onAppClicked(app1) }
        launch { viewModel.onToggleFavorite(app2) }
        launch { viewModel.toggleSortOrder() }
        launch { viewModel.updateTimeAndDate() }

        advanceUntilIdle()

        // Keine Crashes, alle Operationen abgeschlossen
        verify(recordAppLaunchUseCase).invoke(app1)
        verify(toggleFavoriteUseCase).invoke(eq(app2), any())  // ← eq() hinzugefügt!
        verify(toggleSortOrderUseCase).invoke()
    }

    @Test
    fun `favoriteAppsState - starts with Loading and transitions correctly`() = runTest {
        val favoriteApps = FavoriteAppsResult(testApps, isFallback = false)
        val stateFlow = MutableStateFlow<UiState<FavoriteAppsResult>>(UiState.Loading)

        whenever(getFavoriteAppsUseCase.favoriteApps).thenReturn(stateFlow)

        setupViewModel()

        viewModel.favoriteAppsState.test {
            // Initial state
            assertEquals(UiState.Loading, awaitItem())

            // Update to Success
            stateFlow.value = UiState.Success(favoriteApps)
            val successState = awaitItem()
            assertTrue(successState is UiState.Success)
            assertEquals(2, successState.data.apps.size)
        }
    }

    @Test
    fun `uiState - battery level updates are reflected immediately`() = runTest {
        setupViewModel()

        viewModel.uiState.test {
            val initial = awaitItem()

            viewModel.updateBatteryLevel(50, 100)
            val updated = awaitItem()
            assertEquals("50%", updated.batteryString)

            viewModel.updateBatteryLevel(75, 100)
            val updated2 = awaitItem()
            assertEquals("75%", updated2.batteryString)
        }
    }

    @Test
    fun `onAppDrawerSearchQueryChanged - updates search query state`() = runTest {
        setupViewModel()

        viewModel.appDrawerSearchQuery.test {
            assertEquals("", awaitItem())

            viewModel.onAppDrawerSearchQueryChanged("test")
            assertEquals("test", awaitItem())

            viewModel.onAppDrawerSearchQueryChanged("test app")
            assertEquals("test app", awaitItem())
        }
    }

    @Test
    fun `onAppDrawerClosed - resets search query to empty`() = runTest {
        setupViewModel()

        viewModel.appDrawerSearchQuery.test {
            assertEquals("", awaitItem())

            viewModel.onAppDrawerSearchQueryChanged("search term")
            assertEquals("search term", awaitItem())

            viewModel.onAppDrawerClosed()
            assertEquals("", awaitItem())
        }
    }

    @Test
    fun `onAppDrawerSearchQueryChanged - with empty string - works correctly`() = runTest {
        setupViewModel()

        viewModel.appDrawerSearchQuery.test {
            awaitItem() // Initial empty

            viewModel.onAppDrawerSearchQueryChanged("test")
            awaitItem()

            viewModel.onAppDrawerSearchQueryChanged("")
            assertEquals("", awaitItem())
        }
    }

    @Test
    fun `onAppDrawerSearchQueryChanged - with special characters - handles correctly`() = runTest {
        setupViewModel()

        viewModel.appDrawerSearchQuery.test {
            awaitItem()

            viewModel.onAppDrawerSearchQueryChanged("test@#$%")
            assertEquals("test@#$%", awaitItem())

            viewModel.onAppDrawerSearchQueryChanged("émojï 🎉")
            assertEquals("émojï 🎉", awaitItem())
        }
    }

    @Test
    fun `updateBatteryLevel - with maximum values - handles correctly`() = runTest {
        setupViewModel()

        viewModel.updateBatteryLevel(Int.MAX_VALUE, Int.MAX_VALUE)

        // Sollte 100% sein
        assertEquals("100%", viewModel.uiState.value.batteryString)
    }

    @Test
    fun `updateBatteryLevel - with realistic maximum value - handles correctly`() = runTest {
        setupViewModel()

        viewModel.updateBatteryLevel(100, 100)
        assertEquals("100%", viewModel.uiState.value.batteryString)
    }

    @Test
    fun `updateBatteryLevel - with scale smaller than level - handles correctly`() = runTest {
        setupViewModel()

        viewModel.updateBatteryLevel(100, 50)

        // Sollte 200% ergeben (mathematisch korrekt, aber unrealistisch)
        // Oder sollte es abgefangen werden? Test zeigt das Verhalten!
        assertNotNull(viewModel.uiState.value.batteryString)
    }

    @Test
    fun `init - when all UseCases throw - ViewModel still initializes`() = runTest {
        whenever(getFavoriteAppsUseCase.favoriteApps).thenReturn(flow {
            throw RuntimeException("Critical error")
        })
        whenever(observeTimeBasedEventsUseCase.invoke(any())).thenReturn(flow {
            throw RuntimeException("Critical error")
        })
        whenever(observeUiColorsUseCase.invoke(any())).thenReturn(flow {
            throw RuntimeException("Critical error")
        })

        setupViewModel(enableTestMode = false)
        advanceUntilIdle()

        // ViewModel sollte existieren und nicht crashen
        assertNotNull(viewModel)
        assertNotNull(viewModel.uiState.value)
    }

    @Test
    fun `onAppClicked - emits LaunchApp before recording usage`() = runTest {
        setupViewModel()
        advanceUntilIdle()

        viewModel.event.test {
            viewModel.onAppClicked(app1)

            val event = awaitItem()
            assertTrue(event is UiEvent.LaunchApp)

            advanceUntilIdle()
        }

        // Beide Aufrufe sollten passiert sein
        verify(recordAppLaunchUseCase).invoke(app1)
        verify(refreshAppsUseCase).invoke()
    }

    @Test
    fun `onDoubleTapToLock - shows toast only once despite multiple calls`() = runTest {
        whenever(requestLockUseCase.invoke())
            .thenReturn(RequestLockUseCase.Result.ErrorDisabled)

        setupViewModel()
        advanceUntilIdle()

        viewModel.event.test {
            viewModel.onDoubleTapToLock()
            advanceUntilIdle()
            awaitItem() // First toast

            viewModel.onDoubleTapToLock()
            viewModel.onDoubleTapToLock()
            viewModel.onDoubleTapToLock()
            advanceUntilIdle()

            expectNoEvents() // Keine weiteren Toasts!
        }
    }

    @Test
    fun `onFlingDown - shows toast only once despite multiple calls`() = runTest {
        whenever(requestNotificationsUseCase.invoke())
            .thenReturn(RequestNotificationsUseCase.Result.ErrorDisabled)

        setupViewModel()
        advanceUntilIdle()

        viewModel.event.test {
            viewModel.onFlingDown()
            advanceUntilIdle()
            awaitItem() // First toast

            repeat(5) { viewModel.onFlingDown() }
            advanceUntilIdle()

            expectNoEvents() // Keine weiteren Toasts!
        }
    }

    @Test
    fun `init - in test mode - does not observe installed apps`() = runTest {
        setupViewModel(enableTestMode = true)
        advanceUntilIdle()

        // Im Test-Mode sollte observeInstalledAppsUseCase NICHT aufgerufen werden
        verify(observeInstalledAppsUseCase, never()).invoke()
    }

    @Test
    fun `init - in production mode - observes installed apps`() = runTest {
        setupViewModel(enableTestMode = false)
        advanceUntilIdle()

        // Im Production-Mode sollte observeInstalledAppsUseCase aufgerufen werden
        verify(observeInstalledAppsUseCase, atLeastOnce()).invoke()
    }

    @Test
    fun `init - in test mode - still observes favorites`() = runTest {
        val favoriteApps = FavoriteAppsResult(testApps, isFallback = false)
        whenever(getFavoriteAppsUseCase.favoriteApps).thenReturn(
            flowOf(UiState.Success(favoriteApps))
        )

        setupViewModel(enableTestMode = true)
        advanceUntilIdle()

        viewModel.favoriteAppsState.test {
            val state = awaitItem()
            assertTrue(state is UiState.Success)
        }
    }

    @Test
    fun `isAutoLaunchEnabled - returns UseCase result`() = runTest {
        whenever(getAutoLaunchSettingUseCase.invoke()).thenReturn(true)
        setupViewModel()

        val result = viewModel.isAutoLaunchEnabled()
        assertTrue(result)

        whenever(getAutoLaunchSettingUseCase.invoke()).thenReturn(false)
        val result2 = viewModel.isAutoLaunchEnabled()
        assertFalse(result2)
    }

    @Test
    fun `hasUsageData - returns UseCase result`() = runTest {
        whenever(checkAppUsageUseCase.invoke("com.test")).thenReturn(true)
        setupViewModel()

        val result = viewModel.hasUsageData("com.test")
        assertTrue(result)
    }

    @Test
    fun `hasUsageData - with null package - returns false`() = runTest {
        whenever(checkAppUsageUseCase.invoke(null)).thenReturn(false)
        setupViewModel()

        val result = viewModel.hasUsageData(null)
        assertFalse(result)
    }

    @Test
    fun `isAutoShowKeyboardEnabled - returns UseCase result`() = runTest {
        whenever(getAutoShowKeyboardSettingUseCase.invoke()).thenReturn(true)
        setupViewModel()

        val result = viewModel.isAutoShowKeyboardEnabled()
        assertTrue(result)
    }

    @Test
    fun `isTextShadowEnabled - returns UseCase result`() = runTest {
        whenever(getTextShadowEnabledUseCase.invoke()).thenReturn(true)
        setupViewModel()

        val result = viewModel.isTextShadowEnabled()
        assertTrue(result)
    }

    @Test
    fun `onSetTextColor - calls UseCase with correct color`() = runTest {
        setupViewModel()
        advanceUntilIdle()

        viewModel.onSetTextColor(Color.RED)
        advanceUntilIdle()

        verify(setTextColorUseCase).invoke(Color.RED)
    }

    @Test
    fun `onSetTextShadowEnabled - calls UseCase with correct value`() = runTest {
        setupViewModel()
        advanceUntilIdle()

        viewModel.onSetTextShadowEnabled(true)
        advanceUntilIdle()

        verify(setTextShadowEnabledUseCase).invoke(true)

        viewModel.onSetTextShadowEnabled(false)
        advanceUntilIdle()

        verify(setTextShadowEnabledUseCase).invoke(false)
    }

    @Test
    fun `onSetChipBackgroundColor - calls UseCase with correct color`() = runTest {
        setupViewModel()
        advanceUntilIdle()

        viewModel.onSetChipBackgroundColor(Color.BLUE)
        advanceUntilIdle()

        verify(setChipBackgroundColorUseCase).invoke(Color.BLUE)
    }

    @Test
    fun `updateUiColors - updates wallpaper colors flow`() = runTest {
        // Mock WallpaperColors (requires API level handling)
        setupViewModel()
        advanceUntilIdle()

        viewModel.updateUiColors(null)
        advanceUntilIdle()

        // Verify that observeUiColorsUseCase was called with the flow
        verify(observeUiColorsUseCase).invoke(any())
    }

    @Test
    fun `onAppInfoError - emits correct error event`() = runTest {
        setupViewModel()
        advanceUntilIdle()

        viewModel.event.test {
            viewModel.onAppInfoError()

            val event = awaitItem()
            assertTrue(event is UiEvent.ShowToast)
            assertEquals(R.string.error_app_info_open, event.messageResId)
        }
    }

    @Test
    fun `onFavoriteAppsError - emits ShowToastFromString with custom message`() = runTest {
        setupViewModel()
        advanceUntilIdle()

        viewModel.event.test {
            viewModel.onFavoriteAppsError("Custom error message")

            val event = awaitItem()
            assertTrue(event is UiEvent.ShowToastFromString)
            // Note: Can't check message content as it's wrapped in event
        }
    }

    @Test
    fun `refreshDynamicUiData - updates time, date, battery and events`() = runTest {
        setupViewModel()
        advanceUntilIdle()

        val initialTime = viewModel.uiState.value.timeString

        // Simulate time passing
        delay(100)

        viewModel.refreshDynamicUiData()
        advanceUntilIdle()

        // Verify refresh was triggered
        verify(observeTimeBasedEventsUseCase, atLeastOnce()).refresh()
        assertNotNull(viewModel.uiState.value.timeString)
    }

    @Test
    fun `refreshAllData - calls both dynamic and installed apps refresh`() = runTest {
        setupViewModel(enableTestMode = false)
        advanceUntilIdle()

        // Reset mocks
        clearInvocations(refreshAppsUseCase, observeTimeBasedEventsUseCase)

        viewModel.refreshAllData()
        advanceUntilIdle()

        verify(observeTimeBasedEventsUseCase).refresh()
        verify(refreshAppsUseCase).invoke()
    }

    @Test
    fun `maxFavoritesOnHome - has correct default value`() = runTest {
        setupViewModel()

        viewModel.maxFavoritesOnHome.test {
            val value = awaitItem()
            assertEquals(AppConstants.MAX_FALLBACK_FAVORITES_ON_HOME, value)
        }
    }

    @Test
    fun `splitModeThreshold - reflects value from UseCase`() = runTest {
        // Arrange
        val expectedThreshold = 250
        whenever(getSplitModeThresholdUseCase.invoke()).thenReturn(flowOf(expectedThreshold))

        // Act
        setupViewModel()

        // Assert mit Turbine
        viewModel.splitModeThreshold.test {
            // StateFlow emittiert sofort den Initialwert (0) beim Subscriben
            val initialValue = awaitItem()

            // Wenn der Flow extrem schnell war, könnte es schon 250 sein,
            // aber meistens kommt erst 0, dann 250.
            if (initialValue == expectedThreshold) {
                return@test
            }

            // Jetzt warten wir auf das Update vom UseCase, da wir "subscribed" sind
            assertEquals(expectedThreshold, awaitItem())
        }
    }

    @Test
    fun `splitModeThreshold - updates dynamically when UseCase emits new value`() = runTest {
        // Arrange: Wir nutzen einen MutableStateFlow, um Werte zur Laufzeit zu ändern
        val thresholdFlow = MutableStateFlow(0)
        whenever(getSplitModeThresholdUseCase.invoke()).thenReturn(thresholdFlow)

        setupViewModel()

        viewModel.splitModeThreshold.test {
            // Initialer Wert (0)
            assertEquals(0, awaitItem())

            // Act: Simuliere Änderung in den Settings (z.B. Slider bewegt auf 150px)
            thresholdFlow.value = 150

            // Assert: ViewModel muss den neuen Wert propagieren
            assertEquals(150, awaitItem())
        }
    }

    @Test
    fun `refreshDynamicUiData - updates time but preserves battery state`() = runTest {
        // 1. Arrange: Wir simulieren einen System-Intent mit 88% Akku
        val batteryIntent = mock(Intent::class.java)
        `when`(batteryIntent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)).thenReturn(88)
        `when`(batteryIntent.getIntExtra(BatteryManager.EXTRA_SCALE, -1)).thenReturn(100)

        // WICHTIG: Wenn das ViewModel den Context fragt, muss dieser Intent zurückkommen!
        // Wir nutzen anyOrNull() für den Receiver und any() für Filter/Flags
        `when`(context.registerReceiver(
            org.mockito.kotlin.anyOrNull(),
            any(),
            any()
        )).thenReturn(batteryIntent)

        // ViewModel setup
        setupViewModel()

        // 2. Initialen Status setzen
        viewModel.updateBatteryLevel(88, 100)
        advanceUntilIdle()

        val stateBefore = viewModel.uiState.value
        assertEquals("88%", stateBefore.batteryString)

        // 3. Act: Refresh aufrufen
        // Das VM fragt jetzt den Context -> bekommt batteryIntent -> liest 88% -> setzt 88%
        viewModel.refreshDynamicUiData()
        advanceUntilIdle()

        val stateAfter = viewModel.uiState.value

        // 4. Assert
        assertEquals("88%", stateAfter.batteryString)
        assertNotNull(stateAfter.timeString)
    }

    @Test
    fun `goldenStateIntegration_updatesAllComponentsCorrectly`() = runTest {

        // === ARRANGE ===
        val thresholdFlow = MutableStateFlow(0)
        whenever(getSplitModeThresholdUseCase.invoke()).thenReturn(thresholdFlow)

        val colorsFlow = MutableStateFlow(UiColorsState(textColor = Color.WHITE))
        whenever(observeUiColorsUseCase.invoke(any())).thenReturn(colorsFlow)

        val eventsFlow = MutableStateFlow(emptyList<TimeBasedEvent>())
        whenever(observeTimeBasedEventsUseCase.invoke(any())).thenReturn(eventsFlow)

        setupViewModel()

        // Simuliert einen UI-Subscriber, damit WhileSubscribed aktiv wird
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            viewModel.splitModeThreshold.collect {}
        }

        // === ACT ===
        thresholdFlow.value = 300
        colorsFlow.value = UiColorsState(textColor = Color.YELLOW)
        eventsFlow.value = listOf(
            TimeBasedEvent(System.currentTimeMillis(), "Jubiläum", TimeBasedEventType.CALENDAR)
        )

        advanceUntilIdle()

        // === ASSERT ===

        // 1. Threshold
        assertEquals(300, viewModel.splitModeThreshold.value)

        // 2. Colors
        assertEquals(Color.YELLOW, viewModel.uiColorsState.value.textColor)

        // 3. Events
        val currentState = viewModel.uiState.value
        assertEquals(1, currentState.timeBasedEvents.size)
        assertEquals("Jubiläum", currentState.timeBasedEvents.first().title)
    }

    // ========== ZUSÄTZLICHE SPLIT-MODE THRESHOLD TESTS ==========
    // Diese Tests zu LauncherViewModelTest.kt hinzufügen

    @Test
    fun `splitModeThreshold - default value is 0 (Auto mode)`() = runTest {
        // Arrange
        whenever(getSplitModeThresholdUseCase.invoke()).thenReturn(flowOf(0))

        // Act
        setupViewModel()

        // Assert
        viewModel.splitModeThreshold.test {
            assertEquals(0, awaitItem())
        }
    }

    @Test
    fun `splitModeThreshold - maximum value 512 works correctly`() = runTest {
        // Arrange
        val maxThreshold = 512
        whenever(getSplitModeThresholdUseCase.invoke()).thenReturn(flowOf(maxThreshold))

        // Act
        setupViewModel()

        // Assert
        viewModel.splitModeThreshold.test {
            val initialValue = awaitItem()
            if (initialValue == maxThreshold) {
                return@test
            }
            assertEquals(maxThreshold, awaitItem())
        }
    }

    @Test
    fun `splitModeThreshold - typical power-user value 42px works`() = runTest {
        // Arrange
        val recommendedThreshold = 42
        whenever(getSplitModeThresholdUseCase.invoke()).thenReturn(flowOf(recommendedThreshold))

        // Act
        setupViewModel()

        // Assert
        viewModel.splitModeThreshold.test {
            val initialValue = awaitItem()
            if (initialValue == recommendedThreshold) {
                return@test
            }
            assertEquals(recommendedThreshold, awaitItem())
        }
    }

    @Test
    fun `splitModeThreshold - rapid changes are all propagated`() = runTest {
        // Arrange: Simuliere schnelle Slider-Bewegung
        val thresholdFlow = MutableStateFlow(0)
        whenever(getSplitModeThresholdUseCase.invoke()).thenReturn(thresholdFlow)

        setupViewModel()

        viewModel.splitModeThreshold.test {
            assertEquals(0, awaitItem())

            // Act: Simuliere User bewegt Slider schnell
            thresholdFlow.value = 50
            assertEquals(50, awaitItem())

            thresholdFlow.value = 100
            assertEquals(100, awaitItem())

            thresholdFlow.value = 150
            assertEquals(150, awaitItem())

            thresholdFlow.value = 42  // User landet bei 42
            assertEquals(42, awaitItem())
        }
    }

    @Test
    fun `splitModeThreshold - change during app launch does not interfere`() = runTest {
        // Arrange
        val thresholdFlow = MutableStateFlow(0)
        whenever(getSplitModeThresholdUseCase.invoke()).thenReturn(thresholdFlow)

        setupViewModel()

        // WICHTIG: Erst nach setupViewModel() aufrufen!
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            viewModel.splitModeThreshold.collect {}
        }

        advanceUntilIdle()

        // Act: Gleichzeitig App Launch + Threshold-Änderung
        launch {
            viewModel.onAppClicked(app1)
        }
        launch {
            thresholdFlow.value = 100
        }

        advanceUntilIdle()

        // Assert: Beide Operationen sollten erfolgreich sein
        verify(recordAppLaunchUseCase).invoke(app1)
        assertEquals(100, viewModel.splitModeThreshold.value)
    }

    @Test
    fun `splitModeThreshold - survives UseCase throwing exception`() = runTest {
        // Arrange: UseCase wirft beim ersten Mal Exception, dann funktioniert es
        var callCount = 0
        whenever(getSplitModeThresholdUseCase.invoke()).thenReturn(flow {
            callCount++
            if (callCount == 1) {
                throw RuntimeException("Settings corrupted")
            }
            emit(42)
        })

        // Act
        setupViewModel()
        advanceUntilIdle()

        // Assert: ViewModel sollte überleben und Default haben
        assertNotNull(viewModel.splitModeThreshold)
        // Der Flow sollte den Fehler geschluckt haben, initialValue bleibt
        assertEquals(0, viewModel.splitModeThreshold.value)
    }

    @Test
    fun `splitModeThreshold - preset values work correctly`() = runTest {
        // Test alle empfohlenen Preset-Werte
        val presetValues = listOf(0, 42, 60, 100, 512)

        for (preset in presetValues) {
            // Arrange
            val thresholdFlow = MutableStateFlow(preset)
            whenever(getSplitModeThresholdUseCase.invoke()).thenReturn(thresholdFlow)

            // Act
            setupViewModel()

            // Assert
            viewModel.splitModeThreshold.test {
                val value = awaitItem()
                assertTrue(
                    value == preset,
                    "Expected preset $preset but got $value"
                )
                cancelAndIgnoreRemainingEvents()
            }
        }
    }

    @Test
    fun `splitModeThreshold - zero to non-zero transition works`() = runTest {
        // Arrange: User aktiviert Split-Mode-Threshold zum ersten Mal
        val thresholdFlow = MutableStateFlow(0)  // Start: Auto-Mode
        whenever(getSplitModeThresholdUseCase.invoke()).thenReturn(thresholdFlow)

        setupViewModel()

        viewModel.splitModeThreshold.test {
            assertEquals(0, awaitItem())  // Initial: Auto

            // Act: User aktiviert Power-User-Modus
            thresholdFlow.value = 42

            // Assert: Wechsel zu manuellem Threshold
            assertEquals(42, awaitItem())
        }
    }

    @Test
    fun `splitModeThreshold - non-zero to zero transition works`() = runTest {
        // Arrange: User deaktiviert Split-Mode-Threshold
        val thresholdFlow = MutableStateFlow(100)  // Start: Manuell
        whenever(getSplitModeThresholdUseCase.invoke()).thenReturn(thresholdFlow)

        setupViewModel()

        viewModel.splitModeThreshold.test {
            skipItems(1)  // Skip initial

            // Act: User wechselt zurück zu Auto
            thresholdFlow.value = 0

            // Assert: Zurück zu Auto-Mode
            assertEquals(0, awaitItem())
        }
    }

    @Test
    fun `splitModeThreshold - changes while battery and time update simultaneously`() = runTest {
        // Arrange: Realistische Multi-Update-Situation
        val thresholdFlow = MutableStateFlow(0)
        whenever(getSplitModeThresholdUseCase.invoke()).thenReturn(thresholdFlow)

        setupViewModel()

        // WICHTIG: Erst nach setupViewModel() aufrufen!
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            viewModel.splitModeThreshold.collect {}
        }

        advanceUntilIdle()

        // Act: Alles gleichzeitig
        launch { thresholdFlow.value = 100 }
        launch { viewModel.updateBatteryLevel(75, 100) }
        launch { viewModel.updateTimeAndDate() }

        advanceUntilIdle()

        // Assert: Alle Updates erfolgreich
        assertEquals(100, viewModel.splitModeThreshold.value)
        assertEquals("75%", viewModel.uiState.value.batteryString)
        assertTrue(viewModel.uiState.value.timeString.isNotEmpty())
    }

    @Test
    fun `splitModeThreshold - multiple subscribers receive same values`() = runTest {
        // Arrange
        val thresholdFlow = MutableStateFlow(0)
        whenever(getSplitModeThresholdUseCase.invoke()).thenReturn(thresholdFlow)

        setupViewModel()

        // Act: Zwei "Observers" gleichzeitig (wie HomeFragment + SettingsFragment)
        val subscriber1 = mutableListOf<Int>()
        val subscriber2 = mutableListOf<Int>()

        val job1 = launch {
            viewModel.splitModeThreshold.collect { subscriber1.add(it) }
        }

        val job2 = launch {
            viewModel.splitModeThreshold.collect { subscriber2.add(it) }
        }

        advanceUntilIdle()

        thresholdFlow.value = 42
        advanceUntilIdle()

        thresholdFlow.value = 100
        advanceUntilIdle()

        job1.cancel()
        job2.cancel()

        // Assert: Beide Subscriber haben alle Werte erhalten
        assertTrue(subscriber1.contains(0))
        assertTrue(subscriber1.contains(42))
        assertTrue(subscriber1.contains(100))

        assertTrue(subscriber2.contains(0))
        assertTrue(subscriber2.contains(42))
        assertTrue(subscriber2.contains(100))
    }

    @Test
    fun `splitModeThreshold - Flow is hot (StateFlow behavior)`() = runTest {
        // Arrange
        val thresholdFlow = MutableStateFlow(42)
        whenever(getSplitModeThresholdUseCase.invoke()).thenReturn(thresholdFlow)

        setupViewModel()
        advanceUntilIdle()

        // Act: Ändere Wert BEVOR wir subscriben
        thresholdFlow.value = 100
        advanceUntilIdle()

        // Assert: Späte Subscriber bekommen den aktuellen Wert (StateFlow-Verhalten)
        viewModel.splitModeThreshold.test {
            val currentValue = awaitItem()
            assertTrue(
                currentValue == 100,
                "StateFlow should provide current value to late subscribers"
            )
        }
    }

    @Test
    fun `splitModeThreshold - ViewModel recreation preserves UseCase subscription`() = runTest {
        // Arrange
        val thresholdFlow = MutableStateFlow(42)
        whenever(getSplitModeThresholdUseCase.invoke()).thenReturn(thresholdFlow)

        // --- VM 1 ---
        setupViewModel()

        // Subscriber für VM 1
        val job1 = launch(UnconfinedTestDispatcher(testScheduler)) {
            viewModel.splitModeThreshold.collect {}
        }
        advanceUntilIdle()

        // Change Value
        thresholdFlow.value = 100
        advanceUntilIdle()
        assertEquals(100, viewModel.splitModeThreshold.value)

        // Destroy VM 1
        job1.cancel()

        // --- VM 2 (Recreation) ---
        setupViewModel() // Überschreibt die 'viewModel' Variable

        // Subscriber für VM 2 starten
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            viewModel.splitModeThreshold.collect {}
        }
        advanceUntilIdle()

        // Assert: VM 2 sollte sofort den aktuellen Wert vom Repo (100) haben
        assertEquals(100, viewModel.splitModeThreshold.value)
    }

    @Test
    fun `splitModeThreshold - extreme rapid changes are handled gracefully`() = runTest {
        // Arrange: Stress-Test
        val thresholdFlow = MutableStateFlow(0)
        whenever(getSplitModeThresholdUseCase.invoke()).thenReturn(thresholdFlow)

        setupViewModel()

        val receivedValues = mutableListOf<Int>()
        val job = launch {
            viewModel.splitModeThreshold.collect { receivedValues.add(it) }
        }

        advanceUntilIdle()

        // Act: 100 schnelle Änderungen
        repeat(100) { i ->
            thresholdFlow.value = i % 512  // Rotiere durch alle Werte
        }
        advanceUntilIdle()

        job.cancel()

        // Assert: Keine Crashes, Flow funktioniert noch
        assertTrue(receivedValues.size > 0, "Should have received values")
        assertNotNull(viewModel.splitModeThreshold.value)
    }

    @Test
    fun `splitModeThreshold - integration with homeSettings changes`() = runTest {
        // Arrange
        val thresholdFlow = MutableStateFlow(0)
        val homeSettingsFlow = MutableStateFlow(HomeSettings())

        whenever(getSplitModeThresholdUseCase.invoke()).thenReturn(thresholdFlow)
        whenever(observeHomeSettingsUseCase.invoke()).thenReturn(homeSettingsFlow)

        setupViewModel(enableTestMode = false)

        // WICHTIG: Erst nach setupViewModel() aufrufen!
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            viewModel.splitModeThreshold.collect {}
        }

        // Act: Beide Settings gleichzeitig ändern
        launch {
            thresholdFlow.value = 100
            homeSettingsFlow.value = HomeSettings(sortOrder = SortOrder.TIME_WEIGHTED_USAGE)
        }

        advanceUntilIdle()

        // Assert: Beide Updates erfolgreich
        assertEquals(100, viewModel.splitModeThreshold.value)
        viewModel.sortOrder.asFlow().test {
            assertEquals(SortOrder.TIME_WEIGHTED_USAGE, awaitItem())
            cancelAndIgnoreRemainingEvents()
        }
    }

// ========== BOUNDARY/EDGE CASE TESTS ==========

    @Test
    fun `splitModeThreshold - boundary value 1 works (smallest non-zero)`() = runTest {
        val thresholdFlow = MutableStateFlow(1)
        whenever(getSplitModeThresholdUseCase.invoke()).thenReturn(thresholdFlow)

        setupViewModel()

        viewModel.splitModeThreshold.test {
            skipItems(1)
            assertEquals(1, viewModel.splitModeThreshold.value)
        }
    }

    @Test
    fun `splitModeThreshold - boundary value 511 works (max minus 1)`() = runTest {
        val thresholdFlow = MutableStateFlow(511)
        whenever(getSplitModeThresholdUseCase.invoke()).thenReturn(thresholdFlow)

        setupViewModel()

        viewModel.splitModeThreshold.test {
            skipItems(1)
            assertEquals(511, viewModel.splitModeThreshold.value)
        }
    }

    @Test
    fun `splitModeThreshold - toggle between two values repeatedly`() = runTest {
        // Realistisches Szenario: User experimentiert mit zwei Werten
        val thresholdFlow = MutableStateFlow(0)
        whenever(getSplitModeThresholdUseCase.invoke()).thenReturn(thresholdFlow)

        setupViewModel()

        viewModel.splitModeThreshold.test {
            assertEquals(0, awaitItem())

            // Hin und her zwischen Auto und 42
            repeat(10) { iteration ->
                val newValue = if (iteration % 2 == 0) 42 else 0
                thresholdFlow.value = newValue
                assertEquals(newValue, awaitItem())
            }
        }
    }

    @Test
    fun `splitModeThreshold - survives ViewModel being in background`() = runTest {
        // Arrange
        val thresholdFlow = MutableStateFlow(42)
        whenever(getSplitModeThresholdUseCase.invoke()).thenReturn(thresholdFlow)

        setupViewModel()

        // Wir simulieren hier KEINEN dauerhaften Collector im backgroundScope,
        // weil wir das "Background"-Verhalten (kein Subscriber) testen wollen.
        // Stattdessen nutzen wir temporary jobs.

        // 1. App im Vordergrund (Subscriber da)
        val foregroundJob = launch(UnconfinedTestDispatcher(testScheduler)) {
            viewModel.splitModeThreshold.collect {}
        }
        advanceUntilIdle()
        assertEquals(42, viewModel.splitModeThreshold.value)

        // 2. App geht in Hintergrund (Subscriber weg)
        foregroundJob.cancel()

        // WhileSubscribed(5000) hält den Flow noch 5 Sekunden am Leben.
        // Wir ändern den Wert "im Hintergrund"
        thresholdFlow.value = 100

        // Da wir im Test keine echte Zeit haben, simulieren wir,
        // dass wir innerhalb des 5s Fensters zurückkommen.

        // 3. App kommt zurück in den Vordergrund
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            viewModel.splitModeThreshold.collect {}
        }
        advanceUntilIdle()

        // Assert: Der Wert sollte aktualisiert sein
        assertEquals(100, viewModel.splitModeThreshold.value)
    }

    // ========== LAYOUT SETTINGS TESTS ==========
    @Test
    fun `layoutScaleState - default value matches AppConstants`() = runTest {
        // Setup verwendet bereits AppConstants.DEFAULT_LAYOUT_SCALE
        setupViewModel()
        advanceUntilIdle()

        viewModel.layoutScaleState.test {
            assertEquals(AppConstants.DEFAULT_LAYOUT_SCALE, awaitItem())
        }
    }

    @Test
    fun `verticalPaddingState - default value matches AppConstants`() = runTest {
        // Setup verwendet bereits AppConstants.DEFAULT_VERTICAL_PADDING_FACTOR
        setupViewModel()
        advanceUntilIdle()

        viewModel.verticalPaddingState.test {
            assertEquals(AppConstants.DEFAULT_VERTICAL_PADDING_FACTOR, awaitItem())
        }
    }

    @Test
    fun `isFontBoldState - default value matches AppConstants`() = runTest {
        // Setup verwendet bereits AppConstants.DEFAULT_FONT_BOLD
        setupViewModel()
        advanceUntilIdle()

        viewModel.isFontBoldState.test {
            assertEquals(AppConstants.DEFAULT_FONT_BOLD, awaitItem())
        }
    }

    @Test
    fun `layoutScaleState - updates dynamically when UseCase emits new value`() = runTest {
        val scaleFlow = MutableStateFlow(AppConstants.DEFAULT_LAYOUT_SCALE)
        whenever(getLayoutSettingsUseCase.layoutScale).thenReturn(scaleFlow)

        setupViewModel()

        viewModel.layoutScaleState.test {
            assertEquals(AppConstants.DEFAULT_LAYOUT_SCALE, awaitItem())

            scaleFlow.value = 0.5f
            assertEquals(0.5f, awaitItem())

            scaleFlow.value = 0.8f
            assertEquals(0.8f, awaitItem())
        }
    }

    @Test
    fun `verticalPaddingState - updates dynamically when UseCase emits new value`() = runTest {
        val paddingFlow = MutableStateFlow(AppConstants.DEFAULT_VERTICAL_PADDING_FACTOR)
        whenever(getLayoutSettingsUseCase.verticalPadding).thenReturn(paddingFlow)

        setupViewModel()

        viewModel.verticalPaddingState.test {
            assertEquals(AppConstants.DEFAULT_VERTICAL_PADDING_FACTOR, awaitItem())

            paddingFlow.value = 0.3f
            assertEquals(0.3f, awaitItem())
        }
    }

    @Test
    fun `isFontBoldState - updates dynamically when UseCase emits new value`() = runTest {
        val boldFlow = MutableStateFlow(AppConstants.DEFAULT_FONT_BOLD)
        whenever(getLayoutSettingsUseCase.isFontBold).thenReturn(boldFlow)

        setupViewModel()

        viewModel.isFontBoldState.test {
            assertEquals(AppConstants.DEFAULT_FONT_BOLD, awaitItem())

            boldFlow.value = !AppConstants.DEFAULT_FONT_BOLD
            assertEquals(!AppConstants.DEFAULT_FONT_BOLD, awaitItem())

            boldFlow.value = AppConstants.DEFAULT_FONT_BOLD
            assertEquals(AppConstants.DEFAULT_FONT_BOLD, awaitItem())
        }
    }

    @Test
    fun `layout settings - all three update simultaneously`() = runTest {
        val scaleFlow = MutableStateFlow(AppConstants.DEFAULT_LAYOUT_SCALE)
        val paddingFlow = MutableStateFlow(AppConstants.DEFAULT_VERTICAL_PADDING_FACTOR)
        val boldFlow = MutableStateFlow(AppConstants.DEFAULT_FONT_BOLD)

        whenever(getLayoutSettingsUseCase.layoutScale).thenReturn(scaleFlow)
        whenever(getLayoutSettingsUseCase.verticalPadding).thenReturn(paddingFlow)
        whenever(getLayoutSettingsUseCase.isFontBold).thenReturn(boldFlow)

        setupViewModel()
        advanceUntilIdle()

        // Alle gleichzeitig ändern
        launch { scaleFlow.value = 0.7f }
        launch { paddingFlow.value = 0.5f }
        launch { boldFlow.value = !AppConstants.DEFAULT_FONT_BOLD }

        advanceUntilIdle()

        assertEquals(0.7f, viewModel.layoutScaleState.value)
        assertEquals(0.5f, viewModel.verticalPaddingState.value)
        assertEquals(!AppConstants.DEFAULT_FONT_BOLD, viewModel.isFontBoldState.value)
    }

    @Test
    fun `layout settings - survive UseCase throwing exception`() = runTest {
        whenever(getLayoutSettingsUseCase.layoutScale).thenReturn(flow {
            throw RuntimeException("Settings corrupted")
        })
        whenever(getLayoutSettingsUseCase.verticalPadding).thenReturn(flowOf(AppConstants.DEFAULT_VERTICAL_PADDING_FACTOR))
        whenever(getLayoutSettingsUseCase.isFontBold).thenReturn(flowOf(AppConstants.DEFAULT_FONT_BOLD))

        setupViewModel()
        advanceUntilIdle()

        // ViewModel sollte überleben und Default-Wert verwenden
        assertNotNull(viewModel)
        assertEquals(AppConstants.DEFAULT_LAYOUT_SCALE, viewModel.layoutScaleState.value)
    }

    @Test
    fun `layout settings - rapid slider changes are handled gracefully`() = runTest {
        val scaleFlow = MutableStateFlow(AppConstants.DEFAULT_LAYOUT_SCALE)
        whenever(getLayoutSettingsUseCase.layoutScale).thenReturn(scaleFlow)

        setupViewModel()

        val receivedValues = mutableListOf<Float>()
        val job = launch {
            viewModel.layoutScaleState.collect { receivedValues.add(it) }
        }

        advanceUntilIdle()

        // Simuliere schnelle Slider-Bewegung
        repeat(50) { i ->
            scaleFlow.value = i / 50f
        }
        advanceUntilIdle()

        job.cancel()

        assertTrue(receivedValues.isNotEmpty())
        assertNotNull(viewModel.layoutScaleState.value)
    }

    @Test
    fun `goldenStateIntegration - layout settings included`() = runTest {
        val scaleFlow = MutableStateFlow(AppConstants.DEFAULT_LAYOUT_SCALE)
        val paddingFlow = MutableStateFlow(AppConstants.DEFAULT_VERTICAL_PADDING_FACTOR)
        val boldFlow = MutableStateFlow(AppConstants.DEFAULT_FONT_BOLD)
        val thresholdFlow = MutableStateFlow(0)
        val colorsFlow = MutableStateFlow(UiColorsState(textColor = Color.WHITE))

        whenever(getLayoutSettingsUseCase.layoutScale).thenReturn(scaleFlow)
        whenever(getLayoutSettingsUseCase.verticalPadding).thenReturn(paddingFlow)
        whenever(getLayoutSettingsUseCase.isFontBold).thenReturn(boldFlow)
        whenever(getSplitModeThresholdUseCase.invoke()).thenReturn(thresholdFlow)
        whenever(observeUiColorsUseCase.invoke(any())).thenReturn(colorsFlow)

        setupViewModel()

        // WICHTIG: Subscriber für WhileSubscribed Flow
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            viewModel.splitModeThreshold.collect {}
        }

        advanceUntilIdle()

        // Alle Settings gleichzeitig ändern
        scaleFlow.value = 0.8f
        paddingFlow.value = 0.6f
        boldFlow.value = !AppConstants.DEFAULT_FONT_BOLD
        thresholdFlow.value = 100
        colorsFlow.value = UiColorsState(textColor = Color.YELLOW)

        advanceUntilIdle()

        assertEquals(0.8f, viewModel.layoutScaleState.value)
        assertEquals(0.6f, viewModel.verticalPaddingState.value)
        assertEquals(!AppConstants.DEFAULT_FONT_BOLD, viewModel.isFontBoldState.value)
        assertEquals(100, viewModel.splitModeThreshold.value)
        assertEquals(Color.YELLOW, viewModel.uiColorsState.value.textColor)
    }

// ========== LAYOUT SETTINGS - SETTER TESTS ==========

    @Test
    fun `onSetLayoutScale - calls UseCase with correct value`() = runTest {
        setupViewModel()
        advanceUntilIdle()

        viewModel.onSetLayoutScale(0.75f)
        advanceUntilIdle()

        verify(setLayoutScaleUseCase).invoke(0.75f)
    }

    @Test
    fun `onSetLayoutScale - coerces value above limit to limit`() = runTest {
        val limit = AppConstants.LAYOUT_SCALE_MAX
        val exceededCount = limit + 0.1f
        setupViewModel()
        advanceUntilIdle()

        viewModel.onSetLayoutScale(exceededCount)
        advanceUntilIdle()

        verify(setLayoutScaleUseCase).invoke(limit)
    }

    @Test
    fun `onSetLayoutScale - coerces negative value to 0`() = runTest {
        setupViewModel()
        advanceUntilIdle()

        viewModel.onSetLayoutScale(-0.5f)
        advanceUntilIdle()

        verify(setLayoutScaleUseCase).invoke(0f)
    }

    @Test
    fun `onSetLayoutScale - boundary value 0 works`() = runTest {
        setupViewModel()
        advanceUntilIdle()

        viewModel.onSetLayoutScale(0f)
        advanceUntilIdle()

        verify(setLayoutScaleUseCase).invoke(0f)
    }

    @Test
    fun `onSetLayoutScale - boundary value 1 works`() = runTest {
        setupViewModel()
        advanceUntilIdle()

        viewModel.onSetLayoutScale(1.0f)
        advanceUntilIdle()

        verify(setLayoutScaleUseCase).invoke(1.0f)
    }

    @Test
    fun `onSetVerticalPadding - calls UseCase with correct value`() = runTest {
        setupViewModel()
        advanceUntilIdle()

        viewModel.onSetVerticalPadding(0.6f)
        advanceUntilIdle()

        verify(setVerticalPaddingUseCase).invoke(0.6f)
    }

    @Test
    fun `onSetVerticalPadding - coerces value above limit to limit`() = runTest {
        val limit = AppConstants.LAYOUT_SCALE_MAX
        val exceededCount = limit + 0.1f
        setupViewModel()
        advanceUntilIdle()

        viewModel.onSetVerticalPadding(exceededCount)
        advanceUntilIdle()

        verify(setVerticalPaddingUseCase).invoke(limit)
    }

    @Test
    fun `onSetVerticalPadding - coerces negative value to 0`() = runTest {
        setupViewModel()
        advanceUntilIdle()

        viewModel.onSetVerticalPadding(-1.0f)
        advanceUntilIdle()

        verify(setVerticalPaddingUseCase).invoke(0f)
    }

    @Test
    fun `onSetVerticalPadding - boundary value 0 works`() = runTest {
        setupViewModel()
        advanceUntilIdle()

        viewModel.onSetVerticalPadding(0f)
        advanceUntilIdle()

        verify(setVerticalPaddingUseCase).invoke(0f)
    }

    @Test
    fun `onSetVerticalPadding - boundary value 1 works`() = runTest {
        setupViewModel()
        advanceUntilIdle()

        viewModel.onSetVerticalPadding(1.0f)
        advanceUntilIdle()

        verify(setVerticalPaddingUseCase).invoke(1.0f)
    }

    @Test
    fun `onSetFontBold - calls UseCase with true`() = runTest {
        setupViewModel()
        advanceUntilIdle()

        viewModel.onSetFontBold(true)
        advanceUntilIdle()

        verify(setFontBoldUseCase).invoke(true)
    }

    @Test
    fun `onSetFontBold - calls UseCase with false`() = runTest {
        setupViewModel()
        advanceUntilIdle()

        viewModel.onSetFontBold(false)
        advanceUntilIdle()

        verify(setFontBoldUseCase).invoke(false)
    }

    @Test
    fun `onResetLayoutSettings - resets all layout values to defaults`() = runTest {
        setupViewModel()
        advanceUntilIdle()

        viewModel.onResetLayoutSettings()
        advanceUntilIdle()

        verify(setLayoutScaleUseCase).invoke(AppConstants.DEFAULT_LAYOUT_SCALE)
        verify(setVerticalPaddingUseCase).invoke(AppConstants.DEFAULT_VERTICAL_PADDING_FACTOR)
        verify(setFontBoldUseCase).invoke(AppConstants.DEFAULT_FONT_BOLD)
    }

    @Test
    fun `layoutScaleState - reflects custom value from UseCase`() = runTest {
        val customScale = 0.65f
        whenever(getLayoutSettingsUseCase.layoutScale).thenReturn(flowOf(customScale))

        setupViewModel()
        advanceUntilIdle()

        viewModel.layoutScaleState.test {
            assertEquals(customScale, awaitItem())
        }
    }

    @Test
    fun `verticalPaddingState - reflects custom value from UseCase`() = runTest {
        val customPadding = 0.4f
        whenever(getLayoutSettingsUseCase.verticalPadding).thenReturn(flowOf(customPadding))

        setupViewModel()
        advanceUntilIdle()

        viewModel.verticalPaddingState.test {
            assertEquals(customPadding, awaitItem())
        }
    }

    // ========== CONTENT TOP MARGIN TESTS (NEU) ==========

    @Test
    fun `contentTopMarginState - default value is 0`() = runTest {
        setupViewModel()
        advanceUntilIdle()

        viewModel.contentTopMarginState.test {
            assertEquals(0f, awaitItem())
        }
    }

    @Test
    fun `contentTopMarginState - reflects value from UseCase`() = runTest {
        val marginFlow = MutableStateFlow(0f)
        whenever(getLayoutSettingsUseCase.contentTopMargin).thenReturn(marginFlow)

        setupViewModel()
        advanceUntilIdle()

        viewModel.contentTopMarginState.test {
            assertEquals(0f, awaitItem())

            marginFlow.value = 0.5f
            assertEquals(0.5f, awaitItem())
        }
    }

    @Test
    fun `onSetContentTopMargin - calls UseCase with correct value`() = runTest {
        setupViewModel()
        advanceUntilIdle()

        viewModel.onSetContentTopMargin(0.3f)
        advanceUntilIdle()

        verify(setContentTopMarginUseCase).invoke(0.3f)
    }

    @Test
    fun `onSetContentTopMargin - coerces negative value to 0`() = runTest {
        setupViewModel()
        advanceUntilIdle()

        viewModel.onSetContentTopMargin(-0.1f)
        advanceUntilIdle()

        verify(setContentTopMarginUseCase).invoke(0f)
    }

    @Test
    fun `onSetContentTopMargin - coerces value above 1 to 1`() = runTest {
        setupViewModel()
        advanceUntilIdle()

        viewModel.onSetContentTopMargin(1.5f)
        advanceUntilIdle()

        verify(setContentTopMarginUseCase).invoke(1.0f)
    }

    @Test
    fun `onSetContentTopMargin - boundary value 1 works`() = runTest {
        setupViewModel()
        advanceUntilIdle()

        viewModel.onSetContentTopMargin(1.0f)
        advanceUntilIdle()

        verify(setContentTopMarginUseCase).invoke(1.0f)
    }
}