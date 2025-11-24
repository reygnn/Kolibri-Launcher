package com.github.reygnn.kolibri_launcher

import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.os.BatteryManager
import androidx.arch.core.executor.testing.InstantTaskExecutorRule
import androidx.lifecycle.asFlow
import androidx.lifecycle.asLiveData
import app.cash.turbine.test
import com.github.reygnn.kolibri_launcher.core.AppConstants
import com.github.reygnn.kolibri_launcher.domain.model.AppInfo
import com.github.reygnn.kolibri_launcher.domain.model.EventType
import com.github.reygnn.kolibri_launcher.domain.model.FavoriteAppsResult
import com.github.reygnn.kolibri_launcher.domain.model.HomeSettings
import com.github.reygnn.kolibri_launcher.domain.model.SortOrder
import com.github.reygnn.kolibri_launcher.domain.model.TimeBasedEvent
import com.github.reygnn.kolibri_launcher.domain.model.UiColorsState
import com.github.reygnn.kolibri_launcher.domain.usecase.*
import com.github.reygnn.kolibri_launcher.ui.base.UiEvent
import com.github.reygnn.kolibri_launcher.ui.base.UiState
import com.github.reygnn.kolibri_launcher.ui.main.LauncherViewModel
import com.github.reygnn.kolibri_launcher.ui.swipeactions.SwipeSlot
import com.github.reygnn.kolibri_launcher.ui.util.AppUpdateSignal
import com.github.reygnn.kolibri_launcher.ui.util.TestMode
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.mockito.Mock
import org.mockito.MockitoAnnotations
import org.mockito.kotlin.*
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
    private lateinit var getSplitModeThresholdUseCase : GetSplitModeThresholdUseCase


    @Mock
    private lateinit var appUpdateSignal: AppUpdateSignal
    @Mock
    private lateinit var context: Context
    // --- ENDE DER MOCKS ---

    private lateinit var viewModel: LauncherViewModel

    private val app1 = AppInfo("App A", "App A", "com.a", "MainActivity")
    private val app2 = AppInfo("App B", "App B", "com.b", "MainActivity")
    private val testApps = listOf(app1, app2)

    @Before
    fun setup() {
        MockitoAnnotations.openMocks(this)

        // Mocks für Helfer und Context
        whenever(context.registerReceiver(any(), any(), any())).thenReturn(null)
        whenever(appUpdateSignal.events).thenReturn(MutableSharedFlow())
        whenever(context.getString(any())).thenReturn("Test String")
        whenever(context.getString(any(), any())).thenReturn("Test String with args")

        // Mocks für UseCases, die Flows bereitstellen (für den init-Block)
        whenever(getFavoriteAppsUseCase.favoriteApps).thenReturn(flowOf(UiState.Loading))
        whenever(getDrawerAppsUseCase.drawerApps).thenReturn(
            MutableStateFlow<List<AppInfo>>(emptyList()).asLiveData()
        )
        whenever(observeTimeBasedEventsUseCase.invoke(any())).thenReturn(flowOf(emptyList()))
        whenever(observeUiColorsUseCase.invoke(any())).thenReturn(flowOf(UiColorsState()))
        whenever(observeInstalledAppsUseCase.invoke()).thenReturn(flowOf(AppLoadResult.Success))

        whenever(observeHomeSettingsUseCase.invoke()).thenReturn(flowOf(HomeSettings()))
        whenever(getSplitModeThresholdUseCase.invoke()).thenReturn(flowOf(0))
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
            appUpdateSignal,
            context,
            mainDispatcher = mainDispatcherRule.testDispatcher,
            testMode = TestMode(isEnabled = enableTestMode)
        )
    }

    // ========== STANDARD TESTS (JETZT SAUBER) ==========

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
    fun `onToggleFavorite - when limit reached - calls UseCase and shows limit message`() = runTest {
        // Mocke das ERGEBNIS des UseCase
        whenever(toggleFavoriteUseCase.invoke(app1, AppConstants.MAX_FALLBACK_FAVORITES_ON_HOME))
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
    fun `onDoubleTapToLock - when enabled but not available - shows accessibility dialog`() = runTest {
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
    fun `onAppClicked - when recordAppLaunchUseCase fails - still launches app and shows error`() = runTest {
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
            type = EventType.CALENDAR
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
    fun `onFlingRight - when app assigned but not installed - UseCase returns NoAction`() = runTest {
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
            type = EventType.ALARM
        )
        val meeting = TimeBasedEvent(
            triggerTimeMillis = now + 7200000, // in 2 Stunden
            title = "Meeting",
            type = EventType.CALENDAR
        )

        // Der UseCase gibt chronologisch sortierte Events zurück
        whenever(observeTimeBasedEventsUseCase.invoke(any()))
            .thenReturn(flowOf(listOf(alarm, meeting)))

        setupViewModel()
        advanceUntilIdle()

        val state = viewModel.uiState.value
        assertEquals(2, state.timeBasedEvents.size)
        assertEquals(EventType.ALARM, state.timeBasedEvents[0].type)
        assertEquals(EventType.CALENDAR, state.timeBasedEvents[1].type)
    }

    @Test
    fun `init - when only alarm enabled - shows only alarm`() = runTest {
        val alarm = TimeBasedEvent(
            triggerTimeMillis = System.currentTimeMillis() + 3600000,
            title = "Alarm",
            type = EventType.ALARM
        )

        // Der UseCase gibt nur Alarm zurück (weil Calendar deaktiviert)
        whenever(observeTimeBasedEventsUseCase.invoke(any()))
            .thenReturn(flowOf(listOf(alarm)))

        setupViewModel()
        advanceUntilIdle()

        val state = viewModel.uiState.value
        assertEquals(1, state.timeBasedEvents.size)
        assertEquals(EventType.ALARM, state.timeBasedEvents[0].type)
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

}