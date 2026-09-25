package com.github.reygnn.launcher.core

import com.github.reygnn.launcher.core.testing.MainDispatcherRuleBase
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/**
 * Pins the shared [SyncInstalledAppsToHolder] — the ONE cross-launcher pump that feeds
 * the in-RAM holder from the loader with the keep-last-good arbitration (SIA-INV-5, now
 * used by both nyx and kolibri). The load-bearing cases are the retention ones: a
 * recorded empty snapshot must NOT wipe the holder's last-good, and a failure must leave
 * the holder untouched — the exact guarantees that stop a transient empty/failed reload
 * from blanking a consumer on either launcher.
 *
 * Single-dispatcher convention: one [StandardTestDispatcher] from the rule, passed to
 * `runTest`; the collector runs on `backgroundScope` and is driven by `advanceUntilIdle`.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class SyncInstalledAppsToHolderTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRuleBase(StandardTestDispatcher())

    /** Hot loader fake: a [MutableStateFlow] of [AppLoad] that never completes. */
    private class FakeLoader(initial: AppLoad) : InstalledAppsRepository {
        val flow = MutableStateFlow(initial)
        override fun getInstalledApps(): Flow<AppLoad> = flow
        override suspend fun triggerAppsUpdate() = Unit
        override suspend fun purgeRepository() = Unit
    }

    /** Holder fake with the same last-good fallback as the impl (SIA-INV-5). */
    private class FakeHolder : InstalledAppsStateRepository {
        private val state = MutableStateFlow<List<AppInfo>>(emptyList())
        private var lastGood: List<AppInfo> = emptyList()
        override val rawAppsFlow: StateFlow<List<AppInfo>> = state
        override fun updateApps(newApps: List<AppInfo>) {
            if (newApps.isNotEmpty()) lastGood = newApps
            state.value = newApps
        }
        override fun getCurrentApps(): List<AppInfo> = state.value.ifEmpty { lastGood }
        override suspend fun purgeRepository() { state.value = emptyList(); lastGood = emptyList() }
    }

    private fun app(name: String) = AppInfo(name, name, "pkg.$name", "pkg.$name.Main")
    private val apps = listOf(app("A"), app("B"))

    @Test
    fun `loaded non-empty feeds the holder and emits Loaded`() =
        runTest(mainDispatcherRule.testDispatcher) {
            val loader = FakeLoader(AppLoad.Loaded(apps))
            val holder = FakeHolder()
            val seen = mutableListOf<SyncInstalledAppsToHolder.Outcome>()
            backgroundScope.launchCollect(SyncInstalledAppsToHolder(loader, holder), seen)
            advanceUntilIdle()

            assertEquals(apps, holder.rawAppsFlow.value)
            assertEquals(SyncInstalledAppsToHolder.Outcome.Loaded, seen.last())
        }

    @Test
    fun `a recorded empty snapshot keeps the holder's last-good`() =
        runTest(mainDispatcherRule.testDispatcher) {
            val loader = FakeLoader(AppLoad.Loaded(apps))
            val holder = FakeHolder()
            val seen = mutableListOf<SyncInstalledAppsToHolder.Outcome>()
            backgroundScope.launchCollect(SyncInstalledAppsToHolder(loader, holder), seen)
            advanceUntilIdle()

            loader.flow.value = AppLoad.Loaded(emptyList())
            advanceUntilIdle()

            // rawAppsFlow reflects the genuine empty; getCurrentApps() keeps last-good.
            assertTrue(holder.rawAppsFlow.value.isEmpty())
            assertEquals(apps, holder.getCurrentApps())
            assertEquals(SyncInstalledAppsToHolder.Outcome.EmptyLoaded, seen.last())
        }

    @Test
    fun `failure with no cache emits FailedNoCache`() =
        runTest(mainDispatcherRule.testDispatcher) {
            val cause = RuntimeException("enumeration boom")
            val loader = FakeLoader(AppLoad.Failed(cause))
            val holder = FakeHolder()
            val seen = mutableListOf<SyncInstalledAppsToHolder.Outcome>()
            backgroundScope.launchCollect(SyncInstalledAppsToHolder(loader, holder), seen)
            advanceUntilIdle()

            val outcome = seen.last()
            assertTrue(outcome is SyncInstalledAppsToHolder.Outcome.FailedNoCache)
            assertEquals(cause, (outcome as SyncInstalledAppsToHolder.Outcome.FailedNoCache).cause)
            assertTrue(holder.getCurrentApps().isEmpty())
        }

    @Test
    fun `failure after a good load keeps last-good and stays quiet`() =
        runTest(mainDispatcherRule.testDispatcher) {
            val loader = FakeLoader(AppLoad.Loaded(apps))
            val holder = FakeHolder()
            val seen = mutableListOf<SyncInstalledAppsToHolder.Outcome>()
            backgroundScope.launchCollect(SyncInstalledAppsToHolder(loader, holder), seen)
            advanceUntilIdle()

            loader.flow.value = AppLoad.Failed(RuntimeException("glitch"))
            advanceUntilIdle()

            assertEquals(SyncInstalledAppsToHolder.Outcome.FailedKeptLastGood, seen.last())
            assertEquals(apps, holder.getCurrentApps())
        }

    private fun kotlinx.coroutines.CoroutineScope.launchCollect(
        sync: SyncInstalledAppsToHolder,
        into: MutableList<SyncInstalledAppsToHolder.Outcome>,
    ) {
        kotlinx.coroutines.launch { sync.outcomes().collect { into += it } }
    }
}
