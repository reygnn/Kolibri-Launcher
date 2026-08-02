package com.github.reygnn.kolibri_launcher.data

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.preferencesOf
import androidx.datastore.preferences.core.stringPreferencesKey
import app.cash.turbine.test
import com.github.reygnn.kolibri_launcher.core.AppConstants
import com.github.reygnn.kolibri_launcher.fakes.FakeDataStore
import com.github.reygnn.kolibri_launcher.rule.MainDispatcherRule
import com.github.reygnn.kolibri_launcher.rule.TimberRule
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.json.JSONArray
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

/**
 * ============================================================================
 * FAVORITES ORDER MANAGER — SHARE-IN INFRASTRUKTUR-TEST
 * ============================================================================
 *
 * Gegenstück zu [FavoritesRepositoryImplShareInTest] für die Order-Impl.
 * [FavoritesOrderRepositoryImplContractTest] läuft mit `externalScope = null`
 * (umgeht `shareIn`); dieser Test schaut sich das an, was der Contract weglässt:
 * das `WhileSubscribed` + `replay = 1`-Staleness-Verhalten des Hot-Flows.
 *
 * Enthält (bewusst schlank) genau den diskriminierenden Regressionstest für den
 * Backup-Stale-Replay-Fix (`getFavoriteComponentsOrderSnapshot`). Für das
 * `runCurrent`/`advanceTimeBy`-Timing-Muster und warum
 * `CoroutineScope(mainDispatcherRule.testDispatcher + SupervisorJob())` statt
 * `backgroundScope`, siehe [FavoritesRepositoryImplShareInTest] und
 * TESTING_CONVENTIONS.kt Regel 4.
 * ============================================================================
 */
@OptIn(ExperimentalCoroutinesApi::class)
class FavoritesOrderRepositoryImplShareInTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    @get:Rule
    val timberRule = TimberRule()

    private val orderKey = stringPreferencesKey("favorites_order_components_list_json")

    private val compA = "com.example.a/.MainActivity"
    private val compB = "com.example.b/.MainActivity"

    private fun buildManager(
        dataStore: DataStore<Preferences>,
        scope: CoroutineScope,
        sharingStrategy: SharingStarted
    ): FavoritesOrderRepositoryImpl = FavoritesOrderRepositoryImpl.createForTesting(
        dataStore = dataStore,
        externalScope = scope,
        sharingStrategy = sharingStrategy
    )

    /**
     * Discriminating regression test for the backup-stale-replay fix
     * (`getFavoriteComponentsOrderSnapshot`). Same shape as the favorites twin:
     * warm the shared flow to an OLD order, let `WhileSubscribed` drop the
     * upstream (replay=1 retains OLD), then change the store to a NEW order with
     * NO active subscriber. Asserting BOTH the fresh snapshot (NEW) and the stale
     * flow replay (OLD) makes the discrimination self-proving: a buggy impl
     * reading `favoriteComponentsOrderFlow.first()` would return OLD and fail.
     */
    @Test
    fun `getFavoriteComponentsOrderSnapshot returns the fresh store value while the shared flow replay is stale`() = runTest {
        val scope = CoroutineScope(mainDispatcherRule.testDispatcher + SupervisorJob())
        val fake = FakeDataStore()
        fake.setInitialData(preferencesOf(orderKey to JSONArray(listOf(compA)).toString())) // OLD
        val manager = buildManager(
            fake, scope, SharingStarted.WhileSubscribed(AppConstants.FLOW_SHARING_TIMEOUT_MS)
        )

        // Warm the shared flow so replay=1 caches OLD, then unsubscribe.
        manager.favoriteComponentsOrderFlow.test {
            assertEquals(listOf(compA), awaitItem())
            cancelAndIgnoreRemainingEvents()
        }
        testScheduler.runCurrent()

        // Let WhileSubscribed drop the upstream; the replay cache (OLD) is retained.
        advanceTimeBy(AppConstants.FLOW_SHARING_TIMEOUT_MS + 1000)
        testScheduler.runCurrent()

        // Change the store with NO active subscriber — the flow never observes it,
        // so its replay stays OLD. Exactly the backup-screen situation.
        manager.saveOrder(listOf(compB)) // NEW
        advanceUntilIdle()

        // Authoritative snapshot returns the fresh store value...
        assertEquals(listOf(compB), manager.getFavoriteComponentsOrderSnapshot())
        // ...while the hot flow's replay is provably stale (returns OLD).
        assertEquals(listOf(compA), manager.favoriteComponentsOrderFlow.first())

        scope.cancel()
    }
}
