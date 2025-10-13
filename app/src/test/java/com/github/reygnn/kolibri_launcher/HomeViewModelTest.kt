package com.github.reygnn.kolibri_launcher

import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.os.BatteryManager
import androidx.lifecycle.asLiveData
import app.cash.turbine.test
import androidx.arch.core.executor.testing.InstantTaskExecutorRule
import androidx.lifecycle.asFlow
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Before
import org.junit.Ignore
import org.junit.Rule
import org.junit.Test
import org.mockito.Mock
import org.mockito.MockitoAnnotations
import org.mockito.kotlin.any
import org.mockito.kotlin.doAnswer
import org.mockito.kotlin.never
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import java.io.IOException
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

@ExperimentalCoroutinesApi
class HomeViewModelTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    @get:Rule
    val instantExecutorRule = InstantTaskExecutorRule()

    @Mock private lateinit var installedAppsManager: InstalledAppsRepository
    @Mock private lateinit var appUpdateSignal: AppUpdateSignal
    @Mock private lateinit var installedAppsStateManager: InstalledAppsStateRepository
    @Mock private lateinit var getFavoriteAppsUseCase: GetFavoriteAppsUseCaseRepository
    @Mock private lateinit var getDrawerAppsUseCase: GetDrawerAppsUseCaseRepository
    @Mock private lateinit var context: Context
    @Mock private lateinit var favoritesManager: FavoritesRepository
    @Mock private lateinit var settingsManager: SettingsRepository
    @Mock private lateinit var appUsageManager: AppUsageRepository
    @Mock private lateinit var screenLockManager: ScreenLockRepository
    @Mock private lateinit var appVisibilityManager: AppVisibilityRepository

    private lateinit var viewModel: HomeViewModel

    private val app1 = AppInfo("App A", "App A", "com.a", "MainActivity")
    private val app2 = AppInfo("App B", "App B", "com.b", "MainActivity")
    private val testApps = listOf(app1, app2)

    @Before
    fun setup() {
        MockitoAnnotations.openMocks(this)

        whenever(context.registerReceiver(any(), any(), any())).thenReturn(null)
        whenever(getFavoriteAppsUseCase.favoriteApps).thenReturn(flowOf(UiState.Loading))
        whenever(getDrawerAppsUseCase.drawerApps).thenReturn(
            MutableStateFlow<List<AppInfo>>(emptyList()).asLiveData()  // <-- Expliziter Typ
        )
        whenever(installedAppsManager.getInstalledApps()).thenReturn(flowOf(testApps))
        whenever(appUpdateSignal.events).thenReturn(MutableSharedFlow())
        whenever(settingsManager.sortOrderFlow).thenReturn(flowOf(SortOrder.ALPHABETICAL))
        whenever(settingsManager.readabilityModeFlow).thenReturn(flowOf("auto"))
        whenever(settingsManager.doubleTapToLockEnabledFlow).thenReturn(flowOf(false))
        whenever(screenLockManager.isLockingAvailableFlow).thenReturn(MutableStateFlow(false))
        whenever(installedAppsStateManager.getCurrentApps()).thenReturn(emptyList())
        whenever(context.getString(any())).thenReturn("Test String")
        whenever(context.getString(any(), any())).thenReturn("Test String with args")
    }

    private fun setupViewModel() {
        viewModel = HomeViewModel(
            installedAppsManager,
            appUpdateSignal,
            installedAppsStateManager,
            getFavoriteAppsUseCase,
            getDrawerAppsUseCase,
            context,
            favoritesManager,
            settingsManager,
            appUsageManager,
            screenLockManager,
            appVisibilityManager,
            mainDispatcher = mainDispatcherRule.testDispatcher
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
            assertEquals(2, (state as UiState.Success).data.apps.size)
            assertFalse(state.data.isFallback)
        }
    }

    @Test
    fun `init - with fallback favorites - shows toast once`() = runTest {
        val fallbackApps = FavoriteAppsResult(testApps, isFallback = true)
        whenever(getFavoriteAppsUseCase.favoriteApps).thenReturn(
            flowOf(UiState.Success(fallbackApps))
        )

        setupViewModel()

        viewModel.eventFlow.test {
            advanceUntilIdle()

            val event = awaitItem()
            assertTrue(event is UiEvent.ShowToast)
            assertEquals(R.string.welcome_toast_fallback_favorites, (event as UiEvent.ShowToast).messageResId)
        }
    }

    @Test
    fun `onFlingUp - emits ShowAppDrawer event`() = runTest {
        setupViewModel()
        advanceUntilIdle()

        viewModel.eventFlow.test {
            viewModel.onFlingUp()

            val event = awaitItem()
            assertTrue(event is UiEvent.ShowAppDrawer)
        }
    }

    @Test
    fun `onLongPress - emits ShowSettings event`() = runTest {
        setupViewModel()
        advanceUntilIdle()

        viewModel.eventFlow.test {
            viewModel.onLongPress()

            val event = awaitItem()
            assertTrue(event is UiEvent.ShowSettings)
        }
    }

    @Test
    fun `onTimeDoubleClick - emits OpenClock event`() = runTest {
        setupViewModel()
        advanceUntilIdle()

        viewModel.eventFlow.test {
            viewModel.onTimeDoubleClick()

            val event = awaitItem()
            assertTrue(event is UiEvent.OpenClock)
        }
    }

    @Test
    fun `onDateDoubleClick - emits OpenCalendar event`() = runTest {
        setupViewModel()
        advanceUntilIdle()

        viewModel.eventFlow.test {
            viewModel.onDateDoubleClick()

            val event = awaitItem()
            assertTrue(event is UiEvent.OpenCalendar)
        }
    }

    @Test
    fun `onBatteryDoubleClick - emits OpenBatterySettings event`() = runTest {
        setupViewModel()
        advanceUntilIdle()

        viewModel.eventFlow.test {
            viewModel.onBatteryDoubleClick()

            val event = awaitItem()
            assertTrue(event is UiEvent.OpenBatterySettings)
        }
    }

    @Test
    fun `onAppClicked - emits LaunchApp event and records usage`() = runTest {
        setupViewModel()
        advanceUntilIdle()

        viewModel.eventFlow.test {
            viewModel.onAppClicked(app1)

            val launchEvent = awaitItem()
            assertTrue(launchEvent is UiEvent.LaunchApp)
            assertEquals(app1, (launchEvent as UiEvent.LaunchApp).app)

            advanceUntilIdle()

            // The ViewModel also emits RefreshAppDrawer
            val refreshEvent = awaitItem()
            assertTrue(refreshEvent is UiEvent.RefreshAppDrawer)

            verify(appUsageManager).recordPackageLaunch(app1.packageName)
        }
    }

    @Test
    fun `onToggleFavorite - when not favorite - adds to favorites`() = runTest {
        whenever(favoritesManager.isFavoriteComponent(app1.componentName)).thenReturn(false)
        whenever(favoritesManager.toggleFavoriteComponent(app1.componentName)).thenReturn(true)

        setupViewModel()
        advanceUntilIdle()

        viewModel.eventFlow.test {
            viewModel.onToggleFavorite(app1, 0)

            advanceUntilIdle()

            val event = awaitItem()
            assertTrue(event is UiEvent.ShowToastFromString)
            verify(favoritesManager).toggleFavoriteComponent(app1.componentName)
        }
    }

    @Test
    fun `onToggleFavorite - when already favorite - removes from favorites`() = runTest {
        whenever(favoritesManager.isFavoriteComponent(app1.componentName)).thenReturn(true)
        whenever(favoritesManager.toggleFavoriteComponent(app1.componentName)).thenReturn(false)

        setupViewModel()
        advanceUntilIdle()

        viewModel.eventFlow.test {
            viewModel.onToggleFavorite(app1, 5)

            advanceUntilIdle()

            val event = awaitItem()
            assertTrue(event is UiEvent.ShowToastFromString)
        }
    }

    @Test
    fun `onToggleFavorite - when limit reached - shows limit message`() = runTest {
        whenever(favoritesManager.isFavoriteComponent(app1.componentName)).thenReturn(false)

        setupViewModel()
        advanceUntilIdle()

        viewModel.eventFlow.test {
            viewModel.onToggleFavorite(app1, AppConstants.MAX_FAVORITES_ON_HOME)

            val event = awaitItem()
            assertTrue(event is UiEvent.ShowToastFromString)
            verify(favoritesManager, never()).toggleFavoriteComponent(any())
        }
    }

    @Test
    fun `onHideApp - hides app and shows confirmation`() = runTest {
        whenever(appVisibilityManager.hideComponent(app1.componentName)).thenReturn(true)

        setupViewModel()
        advanceUntilIdle()

        viewModel.eventFlow.test {
            viewModel.onHideApp(app1)

            advanceUntilIdle()

            val event = awaitItem()
            assertTrue(event is UiEvent.ShowToastFromString)
            verify(appVisibilityManager).hideComponent(app1.componentName)
        }
    }

    @Test
    fun `onShowApp - shows app and displays confirmation`() = runTest {
        whenever(appVisibilityManager.showComponent(app1.componentName)).thenReturn(true)

        setupViewModel()
        advanceUntilIdle()

        viewModel.eventFlow.test {
            viewModel.onShowApp(app1)

            advanceUntilIdle()

            val event = awaitItem()
            assertTrue(event is UiEvent.ShowToastFromString)
            verify(appVisibilityManager).showComponent(app1.componentName)
        }
    }

    @Test
    fun `onResetAppUsage - resets usage and shows confirmation`() = runTest {
        setupViewModel()
        advanceUntilIdle()

        viewModel.eventFlow.test {
            viewModel.onResetAppUsage(app1)

            advanceUntilIdle()

            val event = awaitItem()
            assertTrue(event is UiEvent.ShowToastFromString)
            verify(appUsageManager).removeUsageDataForPackage(app1.packageName)
        }
    }

    @Test
    fun `toggleSortOrder - toggles between ALPHABETICAL and TIME_WEIGHTED_USAGE`() = runTest {
        whenever(settingsManager.sortOrderFlow).thenReturn(flowOf(SortOrder.ALPHABETICAL))

        setupViewModel()
        advanceUntilIdle()

        viewModel.toggleSortOrder()
        advanceUntilIdle()

        verify(settingsManager).setSortOrder(SortOrder.TIME_WEIGHTED_USAGE)
    }

    @Test
    fun `onDoubleTapToLock - when enabled and available - requests lock`() = runTest {
        whenever(settingsManager.doubleTapToLockEnabledFlow).thenReturn(flowOf(true))
        whenever(screenLockManager.isLockingAvailableFlow).thenReturn(MutableStateFlow(true))

        setupViewModel()
        advanceUntilIdle()

        viewModel.onDoubleTapToLock()
        advanceUntilIdle()

        verify(screenLockManager).requestLock()
    }

    @Test
    fun `onDoubleTapToLock - when enabled but not available - shows accessibility dialog`() = runTest {
        whenever(settingsManager.doubleTapToLockEnabledFlow).thenReturn(flowOf(true))
        whenever(screenLockManager.isLockingAvailableFlow).thenReturn(MutableStateFlow(false))

        setupViewModel()
        advanceUntilIdle()

        viewModel.eventFlow.test {
            viewModel.onDoubleTapToLock()
            advanceUntilIdle()

            val event = awaitItem()
            assertTrue(event is UiEvent.ShowAccessibilityDialog)
        }
    }

    @Test
    fun `onDoubleTapToLock - when disabled - shows enable toast once`() = runTest {
        whenever(settingsManager.doubleTapToLockEnabledFlow).thenReturn(flowOf(false))

        setupViewModel()
        advanceUntilIdle()

        viewModel.eventFlow.test {
            viewModel.onDoubleTapToLock()
            advanceUntilIdle()

            val event = awaitItem()
            assertTrue(event is UiEvent.ShowToast)
            assertEquals(R.string.toast_enable_double_tap_to_lock, (event as UiEvent.ShowToast).messageResId)

            // Second call should not emit event
            viewModel.onDoubleTapToLock()
            advanceUntilIdle()
            expectNoEvents()
        }
    }

    @Test
    fun `updateTimeAndDate - updates time and date strings`() = runTest {
        setupViewModel()
        advanceUntilIdle()

        viewModel.updateTimeAndDate()

        val state = viewModel.uiState.value
        assertTrue(state.timeString.isNotEmpty())
        assertTrue(state.dateString.isNotEmpty())
    }

    @Test
    fun `updateBatteryLevelFromIntent - with valid data - updates battery percentage`() = runTest {
        setupViewModel()
        advanceUntilIdle()

        viewModel.updateBatteryLevel(level = 75, scale = 100)

        assertEquals("75%", viewModel.uiState.value.batteryString)
    }

    @Test
    @Ignore("Requires Robolectric or instrumentation test for Intent support")
    fun `updateBatteryLevelFromIntent - integration test with real intent`() = runTest {
        setupViewModel()
        advanceUntilIdle()

        val intent = Intent().apply {
            putExtra(BatteryManager.EXTRA_LEVEL, 75)
            putExtra(BatteryManager.EXTRA_SCALE, 100)
        }

        viewModel.updateBatteryLevelFromIntent(intent)

        assertEquals("75%", viewModel.uiState.value.batteryString)
    }

    @Test
    fun `updateBatteryLevel - with valid data - updates battery percentage`() = runTest {
        setupViewModel()
        advanceUntilIdle()

        viewModel.updateBatteryLevel(75, 100)

        assertEquals("75%", viewModel.uiState.value.batteryString)
    }

    @Test
    fun `updateBatteryLevel - with invalid level - does not update`() = runTest {
        setupViewModel()
        advanceUntilIdle()

        val previousBattery = viewModel.uiState.value.batteryString

        viewModel.updateBatteryLevel(-1, 100)

        assertEquals(previousBattery, viewModel.uiState.value.batteryString)
    }

    @Test
    fun `updateBatteryLevel - with zero scale - does not update`() = runTest {
        setupViewModel()
        advanceUntilIdle()

        val previousBattery = viewModel.uiState.value.batteryString

        viewModel.updateBatteryLevel(75, 0)

        assertEquals(previousBattery, viewModel.uiState.value.batteryString)
    }

    @Test
    fun `updateUiColorsFromWallpaper - with auto mode - uses default colors`() = runTest {
        whenever(settingsManager.readabilityModeFlow).thenReturn(flowOf("auto"))

        setupViewModel()
        advanceUntilIdle()

        viewModel.updateUiColorsFromWallpaper(null)
        advanceUntilIdle()

        val colors = viewModel.uiColorsState.value
        assertEquals(Color.WHITE, colors.textColor)
        assertEquals(Color.BLACK, colors.shadowColor)
    }

    @Test
    fun `drawerApps - emits drawer apps from use case`() = runTest {
        val drawerAppsFlow = MutableStateFlow<List<AppInfo>>(testApps)
        whenever(getDrawerAppsUseCase.drawerApps).thenReturn(drawerAppsFlow.asLiveData())

        setupViewModel()
        advanceUntilIdle()

        viewModel.drawerApps.asFlow().test {
            val apps = awaitItem()
            assertEquals(2, apps.size)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `sortOrder - emits sort order from settings`() = runTest {
        whenever(settingsManager.sortOrderFlow).thenReturn(flowOf(SortOrder.TIME_WEIGHTED_USAGE))

        setupViewModel()
        advanceUntilIdle()

        viewModel.sortOrder.asFlow().test {
            val order = awaitItem()
            assertEquals(SortOrder.TIME_WEIGHTED_USAGE, order)
        }
    }

    // ========== CRASH-RESISTANCE TESTS ==========

    @Test
    fun `init - when getFavoriteAppsUseCase throws IOException - handles gracefully`() = runTest {
        whenever(getFavoriteAppsUseCase.favoriteApps).thenReturn(flow {
            throw IOException("Cannot load favorites")
        })

        setupViewModel()
        advanceUntilIdle()

        viewModel.favoriteAppsState.test {
            val state = awaitItem()
            assertNotNull(state)
        }
    }

    @Test
    fun `init - when installedAppsManager throws exception - uses cached apps`() = runTest {
        whenever(installedAppsManager.getInstalledApps()).thenReturn(flow {
            throw IOException("Database error")
        })
        whenever(installedAppsStateManager.getCurrentApps()).thenReturn(testApps)

        setupViewModel()
        advanceUntilIdle()

        verify(installedAppsStateManager).updateApps(testApps)
    }

    @Test
    fun `init - when installedAppsManager fails and no cache - uses empty list`() = runTest {
        whenever(installedAppsManager.getInstalledApps()).thenReturn(flow {
            throw RuntimeException("Critical error")
        })
        whenever(installedAppsStateManager.getCurrentApps()).thenReturn(emptyList())

        setupViewModel()
        advanceUntilIdle()

        verify(installedAppsStateManager).updateApps(emptyList())
    }

    @Test
    fun `onAppClicked - when recordPackageLaunch fails - still launches app`() = runTest {
        whenever(appUsageManager.recordPackageLaunch(any())).doAnswer {
            throw IOException("Cannot record")
        }

        setupViewModel()
        advanceUntilIdle()

        viewModel.eventFlow.test {
            viewModel.onAppClicked(app1)

            val event = awaitItem()
            assertTrue(event is UiEvent.LaunchApp)
        }
    }

    @Test
    fun `onToggleFavorite - when toggleFavoriteComponent throws - emits error`() = runTest {
        whenever(favoritesManager.isFavoriteComponent(any())).thenReturn(false)
        whenever(favoritesManager.toggleFavoriteComponent(any())).doAnswer {
            throw IOException("Cannot toggle")
        }

        setupViewModel()
        advanceUntilIdle()

        viewModel.eventFlow.test {
            viewModel.onToggleFavorite(app1, 0)

            val event = awaitItem()
            assertTrue(event is UiEvent.ShowToast)
        }
    }

    @Test
    fun `onHideApp - when hideComponent throws - emits error`() = runTest {
        whenever(appVisibilityManager.hideComponent(any())).doAnswer {
            throw IOException("Cannot hide")
        }

        setupViewModel()
        advanceUntilIdle()

        viewModel.eventFlow.test {
            viewModel.onHideApp(app1)

            val event = awaitItem()
            assertTrue(event is UiEvent.ShowToast)
        }
    }

    @Test
    fun `onShowApp - when showComponent throws - emits error`() = runTest {
        setupViewModel()
        advanceUntilIdle()

        whenever(appVisibilityManager.showComponent(any())).doAnswer {
            throw IOException("Cannot show")
        }

        viewModel.eventFlow.test {
            viewModel.onShowApp(app1)

            val event = awaitItem()
            assertTrue(event is UiEvent.ShowToast)
        }
    }

    @Test
    fun `toggleSortOrder - when setSortOrder throws - does not crash`() = runTest {
        whenever(settingsManager.setSortOrder(any())).doAnswer {
            throw IOException("Cannot save")
        }

        setupViewModel()
        advanceUntilIdle()

        viewModel.toggleSortOrder()

        assertNotNull(viewModel)
    }

    @Test
    fun `updateBatteryLevelFromIntent - with null intent - does not crash`() = runTest {
        setupViewModel()
        advanceUntilIdle()

        viewModel.updateBatteryLevelFromIntent(null)

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

        val previousBattery = viewModel.uiState.value.batteryString

        viewModel.updateBatteryLevelFromIntent(intent)

        assertEquals(previousBattery, viewModel.uiState.value.batteryString)
    }

    @Test
    fun `init - when cleanupFavoriteComponents throws - still updates apps`() = runTest {
        whenever(favoritesManager.cleanupFavoriteComponents(any())).doAnswer {
            throw IOException("Cleanup failed")
        }

        setupViewModel()
        advanceUntilIdle()

        verify(installedAppsStateManager).updateApps(testApps)
    }

    @Test
    fun `onAppInfoError - emits ShowToast event`() = runTest {
        setupViewModel()
        advanceUntilIdle()

        viewModel.eventFlow.test {
            viewModel.onAppInfoError()

            val event = awaitItem()
            assertTrue(event is UiEvent.ShowToast)
            assertEquals(R.string.error_app_info_open, (event as UiEvent.ShowToast).messageResId)
        }
    }

    @Test
    fun `onFavoriteAppsError - emits ShowToastFromString event`() = runTest {
        setupViewModel()
        advanceUntilIdle()

        viewModel.eventFlow.test {
            viewModel.onFavoriteAppsError("Test error message")

            val event = awaitItem()
            assertTrue(event is UiEvent.ShowToastFromString)
        }
    }

    @Test
    fun `refreshDynamicUiData - updates time date and battery`() = runTest {
        setupViewModel()
        advanceUntilIdle()

        viewModel.refreshDynamicUiData()

        val state = viewModel.uiState.value
        assertTrue(state.timeString.isNotEmpty())
        assertTrue(state.dateString.isNotEmpty())
    }
}