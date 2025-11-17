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
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.yield
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

    // UseCases, die für die Fragmente benötigt werden
    @Mock
    private lateinit var getAutoLaunchSettingUseCase: GetAutoLaunchSettingUseCase
    @Mock
    private lateinit var getAutoShowKeyboardSettingUseCase: GetAutoShowKeyboardSettingUseCase
    @Mock
    private lateinit var checkAppUsageUseCase: CheckAppUsageUseCase
    @Mock
    private lateinit var observeHomeSettingsUseCase: ObserveHomeSettingsUseCase // Wichtig für 'sortOrder'
    @Mock
    private lateinit var getTextShadowEnabledUseCase: GetTextShadowEnabledUseCase

    // Helfer, die bleiben
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

        // Wichtig: Der 'sortOrder'-Test braucht das
        whenever(observeHomeSettingsUseCase.invoke()).thenReturn(flowOf(HomeSettings()))
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
        whenever(toggleFavoriteUseCase.invoke(app1, AppConstants.MAX_FAVORITES_ON_HOME))
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
        verify(toggleFavoriteUseCase).invoke(app1, AppConstants.MAX_FAVORITES_ON_HOME)
    }

    @Test
    fun `onToggleFavorite - when limit reached - calls UseCase and shows limit message`() = runTest {
        // Mocke das ERGEBNIS des UseCase
        whenever(toggleFavoriteUseCase.invoke(app1, AppConstants.MAX_FAVORITES_ON_HOME))
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
        verify(toggleFavoriteUseCase).invoke(app1, AppConstants.MAX_FAVORITES_ON_HOME)
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
}