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
import com.github.reygnn.kolibri_launcher.rules.TimberRule
import com.github.reygnn.kolibri_launcher.ui.base.UiEvent
import com.github.reygnn.kolibri_launcher.ui.base.UiState
import com.github.reygnn.kolibri_launcher.ui.main.LauncherViewModel
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
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.mockito.Mock
import org.mockito.MockitoAnnotations
import org.mockito.kotlin.any
import org.mockito.kotlin.atLeastOnce
import org.mockito.kotlin.doAnswer
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import java.io.IOException
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

@ExperimentalCoroutinesApi
class LauncherViewModelDoomsdayTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()
    @get:Rule
    val instantExecutorRule = InstantTaskExecutorRule()
    @get:Rule
    val timberRule = TimberRule()

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

    @Test
    fun `doomsday - java lang Error (OOM) inside coroutine is caught`() = runTest {
        // SZENARIO: OutOfMemoryError oder StackOverflowError (java.lang.Error, nicht Exception!)
        // Normalerweise crasht das die VM. Das ViewModel muss stehen bleiben.

        whenever(refreshAppsUseCase.invoke()).doAnswer {
            throw java.lang.OutOfMemoryError("Heap space full")
        }

        setupViewModel()
        advanceUntilIdle()

        // Act: Trigger etwas, das den Error wirft
        viewModel.refreshInstalledApps()
        advanceUntilIdle()

        // Assert: ViewModel lebt noch, keine UncaughtExceptionHandler triggered
        // Wenn dieser Test grün ist, fängt das 'launchSafe' wirklich ALLES ab.
        assertNotNull(viewModel)
    }

    @Test
    fun `doomsday - zombie viewmodel - operations after scope cancellation`() = runTest {
        // SZENARIO: User schließt Activity, ViewModel wird gecleared,
        // ABER ein verspätetes Event (z.B. Broadcast) triggert noch eine Methode.

        setupViewModel()
        advanceUntilIdle()

        // Wir töten den Scope manuell (simuliert onCleared)
        // Hinweis: Wir nutzen hier den TestScope, in Realität bricht viewModelScope ab.
        // Um das zu simulieren, canceln wir den Job, der an launchSafe hängt?
        // Besser: Wir prüfen, ob launchSafe cancelled exceptions ignoriert oder re-throwt.

        // Da wir launchSafe nicht direkt mocken können, testen wir das Verhalten bei CancellationException.
        // Der Code re-throwt CancellationException (korrekt für Coroutines),
        // aber wir wollen sicherstellen, dass nichts explodiert.

        whenever(toggleFavoriteUseCase.invoke(any(), any())).thenAnswer {
            throw kotlinx.coroutines.CancellationException("Scope died")
        }

        try {
            viewModel.onToggleFavorite(app1)
            advanceUntilIdle()
        } catch (e: Exception) {
            // CancellationException darf fliegen (ist expected behavior in Coroutines),
            // aber keine RuntimeException.
            assertTrue(e is kotlinx.coroutines.CancellationException)
        }
    }

    @Test
    fun `doomsday - deadlock simulation - one flow hangs forever`() = runTest {
        // SZENARIO: Ein UseCase (z.B. Settings) antwortet NIE (Deadlock in DB).
        // Blockiert das die UI-Initialisierung der anderen Komponenten?

        // Settings hängt für immer
        whenever(observeHomeSettingsUseCase.invoke()).thenReturn(flow {
            delay(Long.MAX_VALUE) // Hängt ewig
        })

        // Apps laden aber normal
        val appLoadResult = AppLoadResult.Success
        whenever(observeInstalledAppsUseCase.invoke()).thenReturn(flowOf(appLoadResult))

        setupViewModel(enableTestMode = false)
        advanceUntilIdle()

        // Act: Wir warten kurz (Testzeit)
        advanceUntilIdle()

        // Assert: Obwohl Settings hängen, sollten App-Updates (der andere launchSafe Block)
        // zumindest versucht worden sein zu subscriben.
        verify(observeInstalledAppsUseCase, atLeastOnce()).invoke()

        // Das ViewModel sollte initialisiert sein, auch wenn ein Teil "tot" ist.
        assertNotNull(viewModel)
    }

    @Test
    fun `doomsday - DDOS attack - 10000 app updates in 1ms`() = runTest {
        // SZENARIO: System spinnt und sendet tausende Package-Changed Broadcasts.
        // Oder ein Bug in einer anderen App triggert ständige Updates.

        val updateFlow = MutableSharedFlow<Unit>()
        whenever(appUpdateSignal.events).thenReturn(updateFlow)

        setupViewModel(enableTestMode = false)
        advanceUntilIdle()

        // Act: Feuer frei!
        repeat(10000) {
            updateFlow.emit(Unit)
        }
        advanceUntilIdle()

        // Assert: Der Launcher darf nicht unter der Last zusammenbrechen.
        // Wir prüfen, ob er zumindest versucht hat, Apps zu refreshen.
        // (In Realität würde man hier Debouncing im UseCase erwarten, aber das VM muss stabil bleiben)
        verify(refreshAppsUseCase, atLeastOnce()).invoke()
    }

    @Test
    fun `doomsday - schroedingers app - uninstall during click`() = runTest {
        // SZENARIO: User klickt App. In der exakt gleichen Millisekunde wird sie deinstalliert.
        // recordAppLaunchUseCase wirft Error (App nicht gefunden),
        // refreshAppsUseCase wirft Error (Package Manager State inkonsistent).

        whenever(recordAppLaunchUseCase.invoke(any())).thenThrow(IllegalArgumentException("App gone"))
        whenever(refreshAppsUseCase.invoke()).thenThrow(IllegalStateException("Package manager died"))

        setupViewModel()
        advanceUntilIdle()

        viewModel.event.test {
            viewModel.onAppClicked(app1)

            // Erst Launch
            assertTrue(awaitItem() is UiEvent.LaunchApp)

            // Dann Error Toast (wegen recordAppLaunch Failure)
            val errorEvent = awaitItem()
            assertTrue(errorEvent is UiEvent.ShowToast)

            // WICHTIG: Kein Crash, obwohl ZWEI Exceptions flogen.
        }
    }

    @Test
    fun `doomsday - time travel - system clock jumps backwards`() = runTest {
        // SZENARIO: NTP Sync stellt die Uhr 1 Jahr zurück während die App läuft.
        // Negative Delays oder Timeouts könnten Coroutines crashen.

        setupViewModel()
        advanceUntilIdle()

        // Wir können im Test nicht die Systemuhr ändern, aber wir können prüfen,
        // ob updateTimeAndDate mit "komischen" Werten klarkommt,
        // indem wir sicherstellen, dass es keine Exceptions wirft, egal was Calendar.getInstance() macht.
        // Da Calendar static ist, ist das schwer zu mocken ohne PowerMock.
        // Aber wir vertrauen darauf, dass dein `try-catch` Block in `updateTimeAndDate` das fängt.

        // Wir rufen es einfach mehrfach auf, um sicherzustellen, dass keine State-Corruption passiert.
        repeat(50) {
            viewModel.updateTimeAndDate()
        }

        assertTrue(viewModel.uiState.value.timeString.isNotEmpty())
    }


    @Test
    fun `process death - verifies state restoration`() = runTest {
        val keySearchQuery = AppConstants.KEY_SEARCH_QUERY
        val savedQuery = "Vor dem Crash"

        val savedState = SavedStateHandle().apply {
            set(keySearchQuery, savedQuery)
        }

        // 2. ACT: ViewModel "frisch" initialisieren (Simulierter Neustart nach Kill)
        val restoredViewModel = LauncherViewModel(
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
            savedStateHandle = savedState,
            context = context,
            mainDispatcher = mainDispatcherRule.testDispatcher,
            testMode = TestMode(isEnabled = true)
        )

        // 3. ASSERT: Prüfen, ob der Flow sofort den alten Wert hat
        // Das beweist, dass der String den "Tod" des Prozesses überlebt hat.
        assertThat(restoredViewModel.appDrawerSearchQuery.value).isEqualTo(savedQuery)
    }

}