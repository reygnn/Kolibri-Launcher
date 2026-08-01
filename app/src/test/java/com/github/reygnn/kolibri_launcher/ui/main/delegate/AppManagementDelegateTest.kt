package com.github.reygnn.kolibri_launcher.ui.main.delegate

import android.content.Context
import androidx.arch.core.executor.testing.InstantTaskExecutorRule
import androidx.lifecycle.SavedStateHandle
import com.github.reygnn.kolibri_launcher.R
import com.github.reygnn.kolibri_launcher.core.AppConstants
import com.github.reygnn.kolibri_launcher.domain.model.AppInfo
import com.github.reygnn.kolibri_launcher.domain.model.FavoriteAppsResult
import com.github.reygnn.kolibri_launcher.domain.usecase.CheckAppUsageUseCase
import com.github.reygnn.kolibri_launcher.domain.usecase.GetAutoLaunchSettingUseCase
import com.github.reygnn.kolibri_launcher.domain.usecase.GetAutoShowKeyboardSettingUseCase
import com.github.reygnn.kolibri_launcher.domain.usecase.GetDrawerAppsUseCase
import com.github.reygnn.kolibri_launcher.domain.usecase.GetFavoriteAppsUseCase
import com.github.reygnn.kolibri_launcher.domain.usecase.HideAppUseCase
import com.github.reygnn.kolibri_launcher.domain.usecase.ObserveHomeSettingsUseCase
import com.github.reygnn.kolibri_launcher.domain.usecase.ObserveInstalledAppsUseCase
import com.github.reygnn.kolibri_launcher.domain.usecase.RecordAppLaunchUseCase
import com.github.reygnn.kolibri_launcher.domain.usecase.RefreshAppsUseCase
import com.github.reygnn.kolibri_launcher.domain.usecase.ResetAppUsageUseCase
import com.github.reygnn.kolibri_launcher.domain.usecase.ShowAppUseCase
import com.github.reygnn.kolibri_launcher.domain.usecase.ToggleFavoriteUseCase
import com.github.reygnn.kolibri_launcher.domain.usecase.ToggleSortOrderUseCase
import com.github.reygnn.kolibri_launcher.rule.MainDispatcherRule
import com.github.reygnn.kolibri_launcher.rule.TimberRule
import com.github.reygnn.kolibri_launcher.ui.base.UiEvent
import com.github.reygnn.kolibri_launcher.domain.model.UiState
import com.github.reygnn.kolibri_launcher.core.AppUpdateSignal
import com.github.reygnn.kolibri_launcher.core.PackageEvent
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class AppManagementDelegateTest {

    @get:Rule
    val instantTaskExecutorRule = InstantTaskExecutorRule()

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    @get:Rule
    val timberRule = TimberRule()

    private val sentEvents = mutableListOf<UiEvent>()

    private lateinit var context: Context

    // UseCases
    private lateinit var getFavoriteAppsUseCase: GetFavoriteAppsUseCase
    private lateinit var getDrawerAppsUseCase: GetDrawerAppsUseCase
    private lateinit var hideAppUseCase: HideAppUseCase
    private lateinit var showAppUseCase: ShowAppUseCase
    private lateinit var toggleFavoriteUseCase: ToggleFavoriteUseCase
    private lateinit var toggleSortOrderUseCase: ToggleSortOrderUseCase
    private lateinit var recordAppLaunchUseCase: RecordAppLaunchUseCase
    private lateinit var refreshAppsUseCase: RefreshAppsUseCase
    private lateinit var resetAppUsageUseCase: ResetAppUsageUseCase
    private lateinit var observeInstalledAppsUseCase: ObserveInstalledAppsUseCase
    private lateinit var observeHomeSettingsUseCase: ObserveHomeSettingsUseCase
    private lateinit var getAutoLaunchSettingUseCase: GetAutoLaunchSettingUseCase
    private lateinit var getAutoShowKeyboardSettingUseCase: GetAutoShowKeyboardSettingUseCase
    private lateinit var checkAppUsageUseCase: CheckAppUsageUseCase
    private lateinit var appUpdateSignal: AppUpdateSignal

    private val testApp: AppInfo = mockk {
        every { packageName } returns "com.test.app"
        every { displayName } returns "Test App"
    }

    @Before
    fun setUp() {
        sentEvents.clear()

        context = mockk {
            every { getString(any<Int>(), any<Any>()) } returns "formatted string"
            every { getString(any<Int>()) } returns "string"
        }

        getFavoriteAppsUseCase = mockk {
            every { favoriteApps } returns MutableStateFlow(UiState.Loading)
        }
        getDrawerAppsUseCase = mockk(relaxed = true)

        hideAppUseCase = mockk()
        coEvery { hideAppUseCase(any<AppInfo>()) } returns Unit

        showAppUseCase = mockk()
        coEvery { showAppUseCase(any<AppInfo>()) } returns Unit

        toggleFavoriteUseCase = mockk(relaxed = true)

        toggleSortOrderUseCase = mockk()
        coEvery { toggleSortOrderUseCase() } returns Unit

        recordAppLaunchUseCase = mockk()
        coEvery { recordAppLaunchUseCase(any<AppInfo>()) } returns Unit

        refreshAppsUseCase = mockk()
        coEvery { refreshAppsUseCase() } returns Unit

        resetAppUsageUseCase = mockk()
        coEvery { resetAppUsageUseCase(any<AppInfo>()) } returns Unit

        observeInstalledAppsUseCase = mockk()
        every { observeInstalledAppsUseCase() } returns emptyFlow()

        observeHomeSettingsUseCase = mockk()
        every { observeHomeSettingsUseCase() } returns emptyFlow()
        getAutoLaunchSettingUseCase = mockk(relaxed = true)
        getAutoShowKeyboardSettingUseCase = mockk(relaxed = true)
        checkAppUsageUseCase = mockk(relaxed = true)
        appUpdateSignal = mockk {
            every { events } returns MutableSharedFlow(extraBufferCapacity = 1)
        }
    }

    /**
     * Creates a DelegateScope using the same UnconfinedTestDispatcher from MainDispatcherRule.
     * This matches the pattern used in the monolithic LauncherViewModelTest.
     */
    private fun createDelegateScope() = DelegateScope(
        coroutineScope = CoroutineScope(mainDispatcherRule.testDispatcher + SupervisorJob()),
        mainDispatcher = mainDispatcherRule.testDispatcher,
        eventSender = { event -> sentEvents.add(event) }
    )

    private fun createDelegate(
        savedStateHandle: SavedStateHandle = SavedStateHandle(),
        getFavoriteAppsUseCase: GetFavoriteAppsUseCase = this.getFavoriteAppsUseCase,
        hideAppUseCase: HideAppUseCase = this.hideAppUseCase,
        showAppUseCase: ShowAppUseCase = this.showAppUseCase,
        recordAppLaunchUseCase: RecordAppLaunchUseCase = this.recordAppLaunchUseCase,
        toggleFavoriteUseCase: ToggleFavoriteUseCase = this.toggleFavoriteUseCase,
        toggleSortOrderUseCase: ToggleSortOrderUseCase = this.toggleSortOrderUseCase,
        resetAppUsageUseCase: ResetAppUsageUseCase = this.resetAppUsageUseCase,
        appUpdateSignal: AppUpdateSignal = this.appUpdateSignal,
        scope: DelegateScope = createDelegateScope()
    ) = AppManagementDelegate(
        context = context,
        getFavoriteAppsUseCase = getFavoriteAppsUseCase,
        getDrawerAppsUseCase = getDrawerAppsUseCase,
        hideAppUseCase = hideAppUseCase,
        showAppUseCase = showAppUseCase,
        toggleFavoriteUseCase = toggleFavoriteUseCase,
        toggleSortOrderUseCase = toggleSortOrderUseCase,
        recordAppLaunchUseCase = recordAppLaunchUseCase,
        refreshAppsUseCase = refreshAppsUseCase,
        resetAppUsageUseCase = resetAppUsageUseCase,
        observeInstalledAppsUseCase = observeInstalledAppsUseCase,
        observeHomeSettingsUseCase = observeHomeSettingsUseCase,
        getAutoLaunchSettingUseCase = getAutoLaunchSettingUseCase,
        getAutoShowKeyboardSettingUseCase = getAutoShowKeyboardSettingUseCase,
        checkAppUsageUseCase = checkAppUsageUseCase,
        appUpdateSignal = appUpdateSignal,
        savedStateHandle = savedStateHandle,
        scope = scope
    )

    // ===========================================
    // INITIAL STATE
    // ===========================================

    @Test
    fun `initial favoriteAppsState is Loading`() {
        val delegate = createDelegate()
        assertEquals(UiState.Loading, delegate.favoriteAppsState.value)
    }

    @Test
    fun `initial appDrawerSearchQuery is empty`() {
        val delegate = createDelegate()
        assertEquals("", delegate.appDrawerSearchQuery.value)
    }

    // ===========================================
    // APP CLICK
    // ===========================================

    @Test
    fun `onAppClicked sends LaunchApp event`() = runTest {
        val delegate = createDelegate()

        delegate.onAppClicked(testApp)
        advanceUntilIdle()

        assertTrue(sentEvents.any { it is UiEvent.LaunchApp })
        coVerify { recordAppLaunchUseCase.invoke(testApp) }
    }

    @Test
    fun `onAppClicked records launch and refreshes`() = runTest {
        val delegate = createDelegate()

        delegate.onAppClicked(testApp)
        advanceUntilIdle()

        coVerify { recordAppLaunchUseCase.invoke(testApp) }
        coVerify { refreshAppsUseCase.invoke() }
    }

    @Test
    fun `onAppClicked sends error toast when recording fails`() = runTest {
        val failingRecordUseCase: RecordAppLaunchUseCase = mockk()
        coEvery { failingRecordUseCase(any<AppInfo>()) } throws RuntimeException("DB error")

        val delegate = createDelegate(recordAppLaunchUseCase = failingRecordUseCase)

        delegate.onAppClicked(testApp)
        advanceUntilIdle()

        assertTrue(sentEvents.any { it is UiEvent.LaunchApp })
        assertTrue(sentEvents.any { it is UiEvent.ShowToast })
    }

    // ===========================================
    // TOGGLE FAVORITE
    // ===========================================

    @Test
    fun `onToggleFavorite shows success toast on success`() = runTest {
        coEvery { toggleFavoriteUseCase(any<AppInfo>(), any<Int>()) } returns
                ToggleFavoriteUseCase.Result.Success.Added

        val delegate = createDelegate()

        delegate.onToggleFavorite(testApp)
        advanceUntilIdle()

        assertTrue(sentEvents.any { it is UiEvent.ShowToastFromString })
    }

    @Test
    fun `onToggleFavorite shows error toast on error`() = runTest {
        coEvery { toggleFavoriteUseCase(any<AppInfo>(), any<Int>()) } returns
                ToggleFavoriteUseCase.Result.Error.LimitReached(maxFavorites = 5)

        val delegate = createDelegate()

        delegate.onToggleFavorite(testApp)
        advanceUntilIdle()

        assertTrue(sentEvents.any { it is UiEvent.ShowToastFromString })
    }

    @Test
    fun `onToggleFavorite shows generic error toast on exception`() = runTest {
        coEvery { toggleFavoriteUseCase(any<AppInfo>(), any<Int>()) } throws RuntimeException("Boom")

        val delegate = createDelegate()

        delegate.onToggleFavorite(testApp)
        advanceUntilIdle()

        assertTrue(sentEvents.any { it is UiEvent.ShowToast })
    }

    // ===========================================
    // HIDE / SHOW APP
    // ===========================================

    @Test
    fun `onHideApp calls useCase and shows toast`() = runTest {
        val delegate = createDelegate()

        delegate.onHideApp(testApp)
        advanceUntilIdle()

        coVerify { hideAppUseCase.invoke(testApp) }
        assertTrue(sentEvents.any { it is UiEvent.ShowToastFromString })
    }

    @Test
    fun `onHideApp shows error toast on failure`() = runTest {
        coEvery { hideAppUseCase(any<AppInfo>()) } throws RuntimeException("Fail")

        val delegate = createDelegate()

        delegate.onHideApp(testApp)
        advanceUntilIdle()

        assertTrue(sentEvents.any { it is UiEvent.ShowToast })
    }

    @Test
    fun `onHideApp shows toast on success`() = runTest {
        val delegate = createDelegate()

        delegate.onHideApp(testApp)
        advanceUntilIdle()

        assertTrue(sentEvents.any { it is UiEvent.ShowToastFromString })
    }

    @Test
    fun `onShowApp shows error toast on failure`() = runTest {
        coEvery { showAppUseCase(any<AppInfo>()) } throws RuntimeException("Fail")

        val delegate = createDelegate()

        delegate.onShowApp(testApp)
        advanceUntilIdle()

        assertTrue(sentEvents.any { it is UiEvent.ShowToast })
    }

    // ===========================================
    // RESET USAGE
    // ===========================================

    @Test
    fun `onResetAppUsage calls useCase and shows toast`() = runTest {
        val delegate = createDelegate()

        delegate.onResetAppUsage(testApp)
        advanceUntilIdle()

        coVerify { resetAppUsageUseCase.invoke(testApp) }
        assertTrue(sentEvents.any { it is UiEvent.ShowToastFromString })
    }

    @Test
    fun `onResetAppUsage shows error toast on failure`() = runTest {
        coEvery { resetAppUsageUseCase(any<AppInfo>()) } throws RuntimeException("Fail")

        val delegate = createDelegate()

        delegate.onResetAppUsage(testApp)
        advanceUntilIdle()

        assertTrue(sentEvents.any { it is UiEvent.ShowToast })
    }

    // ===========================================
    // SORT ORDER
    // ===========================================

    @Test
    fun `toggleSortOrder calls useCase`() = runTest {
        val delegate = createDelegate()

        delegate.toggleSortOrder()
        advanceUntilIdle()

        coVerify { toggleSortOrderUseCase.invoke() }
    }

    @Test
    fun `toggleSortOrder shows error toast on failure`() = runTest {
        coEvery { toggleSortOrderUseCase() } throws RuntimeException("Fail")

        val delegate = createDelegate()

        delegate.toggleSortOrder()
        advanceUntilIdle()

        assertTrue(sentEvents.any { it is UiEvent.ShowToast })
    }

    // ===========================================
    // SEARCH QUERY
    // ===========================================

    @Test
    fun `onAppDrawerSearchQueryChanged updates query`() {
        val delegate = createDelegate()

        delegate.onAppDrawerSearchQueryChanged("test")

        assertEquals("test", delegate.appDrawerSearchQuery.value)
    }

    @Test
    fun `onAppDrawerClosed resets query to empty`() {
        val delegate = createDelegate()

        delegate.onAppDrawerSearchQueryChanged("test")
        delegate.onAppDrawerClosed()

        assertEquals("", delegate.appDrawerSearchQuery.value)
    }

    // ===========================================
    // REFRESH
    // ===========================================

    @Test
    fun `refreshInstalledApps calls refreshAppsUseCase`() = runTest {
        val delegate = createDelegate()

        delegate.refreshInstalledApps()
        advanceUntilIdle()

        coVerify { refreshAppsUseCase.invoke() }
    }

    // ===========================================
    // SETTINGS QUERIES
    // ===========================================

    @Test
    fun `isAutoLaunchEnabled delegates to useCase`() = runTest {
        coEvery { getAutoLaunchSettingUseCase() } returns true

        val delegate = createDelegate()

        assertTrue(delegate.isAutoLaunchEnabled())
    }

    @Test
    fun `isAutoShowKeyboardEnabled delegates to useCase`() = runTest {
        coEvery { getAutoShowKeyboardSettingUseCase() } returns false

        val delegate = createDelegate()

        assertFalse(delegate.isAutoShowKeyboardEnabled())
    }

    @Test
    fun `hasUsageData delegates to useCase`() = runTest {
        coEvery { checkAppUsageUseCase(any<String>()) } returns true

        val delegate = createDelegate()

        assertTrue(delegate.hasUsageData("com.test.app"))
    }

    // ===========================================
    // ERROR HELPERS
    // ===========================================

    @Test
    fun `onAppInfoError sends toast event`() = runTest {
        val delegate = createDelegate()

        delegate.onAppInfoError()
        advanceUntilIdle()

        assertTrue(sentEvents.any { it is UiEvent.ShowToast })
    }

    @Test
    fun `onFavoriteAppsError sends string toast event`() = runTest {
        val delegate = createDelegate()

        delegate.onFavoriteAppsError("Something went wrong")
        advanceUntilIdle()

        assertTrue(sentEvents.any { it is UiEvent.ShowToastFromString })
    }

    // ===========================================
    // FAVORITES OBSERVATION
    // ===========================================

    @Test
    fun `start observes favorites and updates state`() = runTest {
        val favResult = FavoriteAppsResult(
            apps = listOf(testApp),
            isFallback = false
        )
        val favFlow = MutableStateFlow<UiState<FavoriteAppsResult>>(
            UiState.Success(favResult)
        )
        val favUseCase: GetFavoriteAppsUseCase = mockk {
            every { favoriteApps } returns favFlow
        }

        val delegate = createDelegate(getFavoriteAppsUseCase = favUseCase)

        delegate.start(isTestMode = true)
        advanceUntilIdle()

        val state = delegate.favoriteAppsState.value
        assertTrue(state is UiState.Success)
        assertEquals(favResult, (state as UiState.Success).data)
    }

    @Test
    fun `start shows fallback toast only once`() = runTest {
        val favResult = FavoriteAppsResult(
            apps = listOf(testApp),
            isFallback = true
        )
        val favFlow = MutableStateFlow<UiState<FavoriteAppsResult>>(
            UiState.Success(favResult)
        )
        val favUseCase: GetFavoriteAppsUseCase = mockk {
            every { favoriteApps } returns favFlow
        }

        val handle = SavedStateHandle()
        val delegate = createDelegate(
            savedStateHandle = handle,
            getFavoriteAppsUseCase = favUseCase
        )

        sentEvents.clear()
        delegate.start(isTestMode = true)
        advanceUntilIdle()

        val toastCount = sentEvents.count { it is UiEvent.ShowToast }
        assertEquals(1, toastCount)
        assertTrue(handle.get<Boolean>(AppConstants.KEY_FALLBACK_TOAST_SHOWN) == true)
    }

    // ===========================================
    // APP UPDATE SIGNAL
    // ===========================================

    @Test
    fun `start listens for app updates in non-test mode`() = runTest {
        val updateFlow = MutableSharedFlow<PackageEvent>()
        val signal: AppUpdateSignal = mockk {
            every { events } returns updateFlow
        }

        val delegate = createDelegate(appUpdateSignal = signal)

        delegate.start(isTestMode = false)
        advanceUntilIdle()

        // Emit an update signal
        updateFlow.emit(PackageEvent.Added("com.test.app"))
        advanceUntilIdle()

        // refreshAppsUseCase should have been called
        coVerify { refreshAppsUseCase.invoke() }
    }
}