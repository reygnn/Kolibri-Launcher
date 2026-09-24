package com.github.reygnn.launcher.common.data.installedapps

import app.cash.turbine.test
import com.github.reygnn.launcher.common.data.TimberRule
import com.github.reygnn.launcher.core.AppConstants
import com.github.reygnn.launcher.core.AppEnumerator
import com.github.reygnn.launcher.core.AppInfo
import com.github.reygnn.launcher.core.AppLoad
import com.github.reygnn.launcher.core.testing.MainDispatcherRuleBase
import com.google.common.truth.Truth.assertThat
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert
import org.junit.Rule
import org.junit.Test

/**
 * JVM test of the shared installed-apps **motor** [InstalledAppsRepositoryImpl]
 * (SHARED_INSTALLED_APPS_SPEC §3) — the fail-closed error envelope (SIA-INV-2),
 * the value-honest empty policy (§9.2), the priming emit + debounce coalescing
 * (DEBOUNCE_SPEC / DBNC-INV-1, DBNC-INV-4), and the trigger fail-safe. This is the
 * "JVM test of the fail-closed/debounce motor" the loader's `NO CONTRACT TEST
 * (ADR)` marker (core/InstalledApps.kt) relies on; the enumeration seam itself is
 * covered separately by [LauncherAppsEnumeratorTest] + the on-device parity guard.
 *
 * Ported from Kolibri's retired `InstalledAppsRepositoryImplTest` when the motor
 * moved to `:common-data`, adapted to the [AppEnumerator] seam (the old
 * `processResolveInfoList` PackageManager path is gone; enumeration is now the
 * enumerator's job, so those cases live in [LauncherAppsEnumeratorTest]).
 *
 * Single dispatcher via [MainDispatcherRuleBase] (convention: one dispatcher
 * source, passed to both `runTest` and the code under test). The dispatcher backs
 * the motor's own sharing scope, so `WhileSubscribed` + `debounce` run on virtual
 * time.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class InstalledAppsRepositoryImplTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRuleBase(StandardTestDispatcher())

    @get:Rule
    val timberRule = TimberRule()

    private val enumerator = mockk<AppEnumerator>()

    private fun repository(trigger: MutableSharedFlow<Unit>): InstalledAppsRepositoryImpl =
        InstalledAppsRepositoryImpl(
            enumerator = enumerator,
            appsUpdateTrigger = trigger,
            ioDispatcher = mainDispatcherRule.testDispatcher,
        )

    // ========== FAIL-CLOSED / EMPTY ENVELOPE (SIA-INV-2, §9.2) ==========

    @Test
    fun `getInstalledApps - enumerate success - emits Loaded with the raw list`() =
        runTest(mainDispatcherRule.testDispatcher) {
            val apps = listOf(
                AppInfo("Zeta", "Zeta", "com.example.z", "com.example.z.Main"),
                AppInfo("Alpha", "Alpha", "com.example.a", "com.example.a.Main"),
            )
            coEvery { enumerator.enumerate() } returns apps

            repository(MutableSharedFlow(extraBufferCapacity = 16)).getInstalledApps().test {
                // The stateIn initial value, delivered before the priming load runs.
                assertThat(awaitItem()).isEqualTo(AppLoad.Loaded(emptyList()))
                // Priming emit → enumerate() → Loaded(fresh), raw and unsorted (SIA-INV-3).
                assertThat(awaitItem()).isEqualTo(AppLoad.Loaded(apps))
                cancelAndIgnoreRemainingEvents()
            }
        }

    @Test
    fun `getInstalledApps - enumerate throws - emits Failed not Loaded empty`() =
        runTest(mainDispatcherRule.testDispatcher) {
            val boom = RuntimeException("enumeration failed")
            coEvery { enumerator.enumerate() } throws boom

            repository(MutableSharedFlow(extraBufferCapacity = 16)).getInstalledApps().test {
                assertThat(awaitItem()).isEqualTo(AppLoad.Loaded(emptyList())) // initial
                // SIA-INV-2: a load error is a VALUE (Failed), never collapsed into
                // Loaded(emptyList()) — that would render downstream retry dead.
                val failed = awaitItem()
                assertThat(failed).isInstanceOf(AppLoad.Failed::class.java)
                assertThat((failed as AppLoad.Failed).cause).isEqualTo(boom)
                cancelAndIgnoreRemainingEvents()
            }
        }

    @Test
    fun `getInstalledApps - enumerate returns empty - stays Loaded empty and never Failed`() =
        runTest(mainDispatcherRule.testDispatcher) {
            coEvery { enumerator.enumerate() } returns emptyList()

            repository(MutableSharedFlow(extraBufferCapacity = 16)).getInstalledApps().test {
                // §9.2: empty is a legitimate Loaded(empty), not a failure. The fresh
                // Loaded(empty) is value-equal to the initial, so the StateFlow
                // conflates it — the guard is that NO Failed (or other value) appears.
                assertThat(awaitItem()).isEqualTo(AppLoad.Loaded(emptyList()))
                advanceUntilIdle()
                expectNoEvents()
                cancelAndIgnoreRemainingEvents()
            }
            coVerify { enumerator.enumerate() }
        }

    // ========== DEBOUNCE / PRIMING (DEBOUNCE_SPEC) ==========

    @Test
    fun `reloadTriggers primes immediately and is not delayed by the window`() =
        runTest(mainDispatcherRule.testDispatcher) {
            val trigger = MutableSharedFlow<Unit>(extraBufferCapacity = 16)

            repository(MutableSharedFlow(extraBufferCapacity = 16)).reloadTriggers(trigger).test {
                Assert.assertEquals(Unit, awaitItem())
                // DBNC-INV-1: the priming emit lands at virtual t=0 — NOT after the
                // debounce window. The value alone is not enough (runTest auto-advances
                // the clock while parked on awaitItem, so a delayed-priming regression
                // would still deliver Unit); the clock assertion is the actual guard.
                Assert.assertEquals(0L, testScheduler.currentTime)
                expectNoEvents()
                cancelAndIgnoreRemainingEvents()
            }
        }

    @Test
    fun `reloadTriggers coalesces a burst of triggers into one reload`() =
        runTest(mainDispatcherRule.testDispatcher) {
            val trigger = MutableSharedFlow<Unit>(extraBufferCapacity = 16)

            repository(MutableSharedFlow(extraBufferCapacity = 16)).reloadTriggers(trigger).test {
                Assert.assertEquals("priming", Unit, awaitItem())

                // A burst within the window: nothing until the quiet period elapses.
                repeat(5) { trigger.emit(Unit) }
                expectNoEvents()

                // DBNC-INV-4: exactly one reload after the window.
                advanceTimeBy(AppConstants.APP_RELOAD_DEBOUNCE_MS + 1)
                Assert.assertEquals("one coalesced reload", Unit, awaitItem())
                expectNoEvents()

                cancelAndIgnoreRemainingEvents()
            }
        }

    // ========== TRIGGER FAIL-SAFE ==========

    @Test
    fun `triggerAppsUpdate emits an event to the trigger flow`() =
        runTest(mainDispatcherRule.testDispatcher) {
            val trigger = mockk<MutableSharedFlow<Unit>>()
            coEvery { trigger.emit(Unit) } returns Unit

            repository(trigger).triggerAppsUpdate()

            coVerify { trigger.emit(Unit) }
        }

    @Test
    fun `triggerAppsUpdate - when flow emit fails - does not crash`() =
        runTest(mainDispatcherRule.testDispatcher) {
            val trigger = mockk<MutableSharedFlow<Unit>>()
            coEvery { trigger.emit(Unit) } throws RuntimeException("Flow error")

            // Must not throw: update failures are swallowed (breadcrumb only).
            repository(trigger).triggerAppsUpdate()

            coVerify { trigger.emit(Unit) }
        }

    // ========== PURGE ==========

    @Test
    fun `purgeRepository - does nothing and does not crash`() =
        runTest(mainDispatcherRule.testDispatcher) {
            val trigger = mockk<MutableSharedFlow<Unit>>()

            repository(trigger).purgeRepository()

            // No re-enumeration is triggered by a purge (the list is system-owned).
            coVerify(exactly = 0) { trigger.emit(Unit) }
        }
}
