package com.github.reygnn.nyx_launcher

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.github.reygnn.launcher.core.AppConstants
import com.github.reygnn.launcher.core.AppUpdateSignal
import com.github.reygnn.launcher.core.InstalledAppsRepository
import com.github.reygnn.launcher.core.PackageEvent
import com.github.reygnn.launcher.core.TimberWrapper
import com.github.reygnn.nyx_launcher.data.icon.FolderIconRenderer
import com.github.reygnn.nyx_launcher.data.icon.IconLoader
import com.github.reygnn.nyx_launcher.home.model.ReconcileResult
import com.github.reygnn.nyx_launcher.home.usecase.ReconcileHomeLayoutUseCase
import com.github.reygnn.nyx_launcher.testing.MainDispatcherRule
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Pins the reconcile-trigger contract of [PackageEventCoordinator]: a package
 * event storm is coalesced to a single debounced [ReconcileHomeLayoutUseCase]
 * call, while the cold-start catch-up runs immediately (bypasses the debounce);
 * and every package event refreshes the shared installed-apps loader so the
 * no-prune "missing" tile state stays live.
 *
 * Robolectric only for the real [android.content.pm.LauncherApps] behind
 * `getSystemService` in [PackageEventCoordinator.start].
 *
 * Dispatcher: the project convention — ONE source, [MainDispatcherRule]'s
 * [kotlinx.coroutines.test.StandardTestDispatcher], passed both to the SUT (as its
 * `dispatcher`) and to `runTest(...)`, so virtual time is unified and there is no
 * second scheduler (TESTING_CONVENTIONS.kt).
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class PackageEventCoordinatorTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val iconLoader = mockk<IconLoader>(relaxed = true)
    private val folderRenderer = mockk<FolderIconRenderer>(relaxed = true)
    private val reconcile = mockk<ReconcileHomeLayoutUseCase>()
    // relaxed: triggerAppsUpdate() is a suspend-Unit fn (needs relaxed, not relaxUnitFun).
    private val installedAppsRepository = mockk<InstalledAppsRepository>(relaxed = true)
    // Real (empty) bus: the debounce tests drive requestReconcile() directly, the
    // loader-refresh test drives it end-to-end by sending a real PackageEvent.
    private val appUpdateSignal = AppUpdateSignal()

    private val coordinator = PackageEventCoordinator(
        context = context,
        iconLoader = iconLoader,
        folderRenderer = folderRenderer,
        reconcile = reconcile,
        appUpdateSignal = appUpdateSignal,
        installedAppsRepository = installedAppsRepository,
        dispatcher = mainDispatcherRule.dispatcher,
    )

    // The collector guards a throwing reconcile via TimberWrapper.silentError, which
    // would crash-in-DEBUG. Neutralize that so the throw-survival test can assert the
    // recovery path instead of the JVM dying (guards against any test flipping the
    // shared TimberWrapper.isDebugBuild flag).
    @Before
    fun preventCrashInDebug() {
        TimberWrapper.preventCrashForTesting.set(true)
    }

    @After
    fun restoreCrashInDebug() {
        TimberWrapper.preventCrashForTesting.set(false)
    }

    @Test
    fun cold_start_reconcile_is_immediate_not_debounced() = runTest(mainDispatcherRule.dispatcher) {
        coEvery { reconcile() } returns ReconcileResult.Unchanged

        coordinator.start()
        runCurrent() // no virtual-time advance: the debounce window has NOT elapsed

        coVerify(exactly = 1) { reconcile() }
    }

    @Test
    fun event_storm_within_window_coalesces_to_a_single_reconcile() = runTest(mainDispatcherRule.dispatcher) {
        coEvery { reconcile() } returns ReconcileResult.Unchanged

        coordinator.start()
        advanceUntilIdle() // consume the immediate cold-start reconcile
        coVerify(exactly = 1) { reconcile() }

        // Space the emits in VIRTUAL TIME by less than the window, so the collector
        // actually drains between them: this exercises the debounce (each emit resets
        // the timer), not just the 1-slot conflation buffer. Without .debounce(...) the
        // collector would drain the buffer on every step and fire a reconcile each time,
        // so the assertions below would see far more than one coalesced call.
        val step = AppConstants.APP_RELOAD_DEBOUNCE_MS / 2
        repeat(4) {
            coordinator.requestReconcile()
            advanceTimeBy(step)
        }

        // The window has been reset on every emit and has NOT yet elapsed since the last
        // one, so no package-event reconcile has fired: still only the cold-start call.
        coVerify(exactly = 1) { reconcile() }

        advanceTimeBy(AppConstants.APP_RELOAD_DEBOUNCE_MS + 1) // let the window elapse
        advanceUntilIdle()

        // cold-start (1) + exactly one coalesced reconcile for the whole storm (1)
        coVerify(exactly = 2) { reconcile() }
    }

    @Test
    fun a_throwing_reconcile_does_not_kill_the_collector() = runTest(mainDispatcherRule.dispatcher) {
        // First (cold-start) reconcile throws; the guarded collector must survive so a
        // later package event still reconciles — no silent permanent loss of reconciles.
        coEvery { reconcile() } answers { throw RuntimeException("datastore boom") } andThen
            ReconcileResult.Unchanged

        coordinator.start()
        advanceUntilIdle() // cold-start reconcile runs and throws (caught + logged)

        coordinator.requestReconcile()
        advanceTimeBy(AppConstants.APP_RELOAD_DEBOUNCE_MS + 1)
        advanceUntilIdle()

        coVerify(exactly = 2) { reconcile() } // survived the throw; the later event reconciled
    }

    @Test
    fun package_event_refreshes_the_shared_loader_and_evicts_the_icon() = runTest(mainDispatcherRule.dispatcher) {
        // The no-prune "missing" model (root TODO.md, Windows-shortcut) is only LIVE if a
        // package event re-enumerates the shared installed-apps loader: HomeViewModel
        // .installedKeys reads that loader to grey/un-grey tiles, and the drawer reads it
        // on open. The reconcile no longer enumerates, so this coordinator must fire
        // InstalledAppsRepository.triggerAppsUpdate() itself (the documented
        // PackageUpdateReceiver → AppUpdateSignal → triggerAppsUpdate funnel). Without it a
        // tile never greys while home stays foreground — the bug this test pins.
        coEvery { reconcile() } returns ReconcileResult.Unchanged

        coordinator.start()
        advanceUntilIdle() // collectors subscribe; drain the cold-start reconcile

        appUpdateSignal.send(PackageEvent.Removed("com.example.gone"))
        advanceUntilIdle()

        coVerify(exactly = 1) { installedAppsRepository.triggerAppsUpdate() }
        verify(exactly = 1) { iconLoader.evict("com.example.gone") } // targeted eviction still happens
    }
}
