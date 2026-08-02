package com.github.reygnn.kolibri_launcher.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.preferencesOf
import androidx.datastore.preferences.core.stringSetPreferencesKey
import app.cash.turbine.test
import com.github.reygnn.kolibri_launcher.core.AppConstants
import com.github.reygnn.kolibri_launcher.fakes.FakeDataStore
import com.github.reygnn.kolibri_launcher.rule.MainDispatcherRule
import com.github.reygnn.kolibri_launcher.rule.TimberRule
import io.mockk.mockk
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import java.util.concurrent.atomic.AtomicInteger

/**
 * ============================================================================
 * FAVORITES MANAGER — SHARE-IN INFRASTRUKTUR-TESTS
 * ============================================================================
 *
 * Ergänzt [FavoritesRepositoryImplContractTest]: Der Contract-Test läuft mit
 * `externalScope = null` und umgeht damit den `shareIn`-Layer, weil er
 * Interface-Semantik prüft — nicht Sharing-Verhalten. Dieser Test hier
 * schaut sich genau das an, was der Contract-Test weglässt:
 *
 *   1. Update-Propagation durch den Shared-Flow, wenn der Scheduler gedreht
 *      wird (`advanceUntilIdle`).
 *   2. Hot-Sharing: zwei Subscriber, ein einziges Upstream-Abo auf dem
 *      DataStore (zählbar via [CountingDataStore]).
 *   3. Replay = 1: Späte Subscriber sehen den letzten Wert sofort, aber
 *      keine Historie davor.
 *   4. `WhileSubscribed(timeout)`-Lifecycle: Upstream bleibt innerhalb des
 *      Timeouts nach dem letzten Unsubscribe aktiv, droppt danach, und
 *      wird bei erneuter Subscription neu aufgebaut.
 *
 * Warum `CoroutineScope(mainDispatcherRule.testDispatcher + SupervisorJob())`
 * statt `backgroundScope`? → Siehe TESTING_CONVENTIONS.kt Regel 4.
 * ============================================================================
 */
@OptIn(ExperimentalCoroutinesApi::class)
class FavoritesRepositoryImplShareInTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    @get:Rule
    val timberRule = TimberRule()

    private val favoritesKey = stringSetPreferencesKey("favorites_components_set")

    private val compA = "com.example.a/.MainActivity"
    private val compB = "com.example.b/.MainActivity"

    /**
     * DataStore-Dekorator, der Subscriptions auf `data` zählt.
     *
     * Dadurch lässt sich "hot sharing" hart beweisen: Wenn zwei Collector auf
     * `favoriteComponentsFlow` laufen, der Counter aber bei 1 bleibt, teilen
     * sie sich garantiert ein einziges Upstream-Abo. Ohne `shareIn` würde der
     * Counter pro Subscription hochgehen.
     */
    private class CountingDataStore(
        private val delegate: FakeDataStore
    ) : DataStore<Preferences> {

        val subscriptionCount = AtomicInteger(0)

        // `data` wird EINMAL ausgewertet — wir behalten genau eine Flow-Instanz,
        // auf die jeder Collector subscribt.
        override val data: Flow<Preferences> =
            delegate.data.onStart { subscriptionCount.incrementAndGet() }

        override suspend fun updateData(
            transform: suspend (Preferences) -> Preferences
        ): Preferences = delegate.updateData(transform)
    }

    private fun buildManager(
        dataStore: DataStore<Preferences>,
        scope: CoroutineScope,
        sharingStrategy: SharingStarted
    ): FavoritesRepositoryImpl = FavoritesRepositoryImpl(
        dataStore = dataStore,
        externalScope = scope,
        sharingStrategy = sharingStrategy
    )

    // ========================================================================
    // 1. UPDATE-PROPAGATION
    // ========================================================================

    /**
     * Der "Anti-Bug"-Test: Genau das, was den Contract-Test mit shareIn vorher
     * rot gemacht hat. Mit `advanceUntilIdle` zwischen Write und Read sieht
     * der Shared-Flow-Subscriber die Änderung korrekt.
     */
    @Test
    fun `write plus advanceUntilIdle makes the update visible through shared flow`() = runTest {
        val scope = CoroutineScope(mainDispatcherRule.testDispatcher + SupervisorJob())
        val fake = FakeDataStore()
        val manager = buildManager(fake, scope, SharingStarted.Lazily)

        manager.favoriteComponentsFlow.test {
            assertEquals(emptySet<String>(), awaitItem())

            manager.addFavoriteComponent(compA)
            advanceUntilIdle()

            assertEquals(setOf(compA), awaitItem())
            cancelAndIgnoreRemainingEvents()
        }

        scope.cancel()
    }

    // ========================================================================
    // 2. HOT SHARING
    // ========================================================================

    /**
     * Zwei aktive Subscriber auf derselben Manager-Instanz → der DataStore
     * wird genau EINMAL abonniert. Ohne `shareIn` wäre der Counter bei 2.
     */
    @Test
    fun `Lazily - two concurrent subscribers share a single upstream subscription`() = runTest {
        val scope = CoroutineScope(mainDispatcherRule.testDispatcher + SupervisorJob())
        val counting = CountingDataStore(FakeDataStore())
        val manager = buildManager(counting, scope, SharingStarted.Lazily)

        manager.favoriteComponentsFlow.test {
            awaitItem() // Sub 1: initial
            advanceUntilIdle()

            manager.favoriteComponentsFlow.test {
                awaitItem() // Sub 2: Replay-Wert
                advanceUntilIdle()

                assertEquals(
                    "Upstream darf pro Manager-Instanz nur einmal abonniert werden",
                    1,
                    counting.subscriptionCount.get()
                )

                cancelAndIgnoreRemainingEvents()
            }

            cancelAndIgnoreRemainingEvents()
        }

        scope.cancel()
    }

    /**
     * Zwei aktive Subscriber → ein einzelnes Write → beide sehen exakt
     * dieselben Emissions in derselben Reihenfolge.
     */
    @Test
    fun `hot sharing - both subscribers see the same emission sequence`() = runTest {
        val scope = CoroutineScope(mainDispatcherRule.testDispatcher + SupervisorJob())
        val fake = FakeDataStore()
        val manager = buildManager(fake, scope, SharingStarted.Lazily)

        manager.favoriteComponentsFlow.test {
            assertEquals(emptySet<String>(), awaitItem()) // outer: initial

            manager.favoriteComponentsFlow.test {
                assertEquals(emptySet<String>(), awaitItem()) // inner: Replay

                manager.addFavoriteComponent(compA)
                advanceUntilIdle()

                assertEquals(setOf(compA), awaitItem()) // inner: Update
                cancelAndIgnoreRemainingEvents()
            }

            // outer war die ganze Zeit aktiv und hat das Update ebenfalls geholt.
            assertEquals(setOf(compA), awaitItem())
            cancelAndIgnoreRemainingEvents()
        }

        scope.cancel()
    }

    // ========================================================================
    // 3. REPLAY = 1
    // ========================================================================

    /**
     * Spät einsteigender Subscriber bekommt sofort den aktuellen Wert aus dem
     * Replay-Buffer — ohne auf die nächste Emission warten zu müssen.
     */
    @Test
    fun `late subscriber immediately receives the replayed value`() = runTest {
        val scope = CoroutineScope(mainDispatcherRule.testDispatcher + SupervisorJob())
        val fake = FakeDataStore()
        fake.setInitialData(preferencesOf(favoritesKey to setOf(compA)))
        val manager = buildManager(fake, scope, SharingStarted.Lazily)

        // Erst einen Subscriber starten — danach bleibt der Upstream bei Lazily
        // aktiv und der Replay-Buffer gefüllt.
        manager.favoriteComponentsFlow.test {
            assertEquals(setOf(compA), awaitItem())
            cancelAndIgnoreRemainingEvents()
        }

        // Update ohne aktive Subscriber (mit Lazily egal — bleibt aktiv).
        manager.addFavoriteComponent(compB)
        advanceUntilIdle()

        // Neuer Subscriber → sollte den aktuellen Stand direkt aus Replay
        // bekommen, ohne auf neue Emissions zu warten.
        val replayed = manager.favoriteComponentsFlow.first()
        assertEquals(setOf(compA, compB), replayed)

        scope.cancel()
    }

    /**
     * `replay = 1` heißt: nur der letzte Wert wird gecached, nicht die ganze
     * Historie. Ein spät einsteigender Subscriber bekommt den aktuellen Stand
     * und NICHT die drei Emissions, die davor passiert sind.
     */
    @Test
    fun `replay equals one - late subscriber gets only the latest value not history`() = runTest {
        val scope = CoroutineScope(mainDispatcherRule.testDispatcher + SupervisorJob())
        val fake = FakeDataStore()
        val manager = buildManager(fake, scope, SharingStarted.Lazily)

        // Subscriber der ersten Stunde — treibt den Upstream und sorgt für
        // mehrere aufeinanderfolgende Emissions.
        manager.favoriteComponentsFlow.test {
            awaitItem() // empty
            manager.addFavoriteComponent(compA)
            advanceUntilIdle()
            awaitItem() // {compA}
            manager.addFavoriteComponent(compB)
            advanceUntilIdle()
            awaitItem() // {compA, compB}
            cancelAndIgnoreRemainingEvents()
        }

        // Später Subscriber — darf NUR den letzten Wert sehen. `expectNoEvents`
        // fängt die Regressionen ab, die passieren würden, wenn jemand `replay`
        // versehentlich auf `Int.MAX_VALUE` hochdreht.
        manager.favoriteComponentsFlow.test {
            assertEquals(setOf(compA, compB), awaitItem())
            expectNoEvents()
            cancelAndIgnoreRemainingEvents()
        }

        scope.cancel()
    }

    // ========================================================================
    // 4. WHILE-SUBSCRIBED LIFECYCLE
    // ========================================================================

    /**
     * `WhileSubscribed(5000)`: nach dem letzten Unsubscribe läuft der Upstream
     * noch 5s weiter. Ein Re-Subscribe innerhalb dieses Fensters wiederverwendet
     * das bestehende Abo — der Subscription-Counter bleibt bei 1.
     *
     * Damit wird der Sinn der ganzen Konstruktion validiert: schnelle
     * Navigation zwischen Screens soll kein teures DataStore-Reconnect
     * auslösen.
     *
     * ACHTUNG — `testScheduler.runCurrent()` statt `advanceUntilIdle()`:
     * `advanceUntilIdle()` läuft durch ALLE geplanten Tasks durch, auch durch
     * den von `WhileSubscribed` nach dem Unsubscribe gesetzten `delay(timeout)`.
     * Das würde den Timeout künstlich sofort ablaufen lassen und den Upstream
     * droppen, lange bevor wir mit `advanceTimeBy` die Test-Semantik setzen.
     * `runCurrent()` prozessiert nur Tasks der aktuellen virtuellen Zeit
     * (0ms) und lässt den Delay stehen.
     */
    @Test
    fun `WhileSubscribed - upstream is reused on resubscribe within timeout`() = runTest {
        val scope = CoroutineScope(mainDispatcherRule.testDispatcher + SupervisorJob())
        val counting = CountingDataStore(FakeDataStore())
        val manager = buildManager(
            counting,
            scope,
            SharingStarted.WhileSubscribed(AppConstants.FLOW_SHARING_TIMEOUT_MS)
        )

        manager.favoriteComponentsFlow.test {
            awaitItem()
            cancelAndIgnoreRemainingEvents()
        }
        testScheduler.runCurrent()
        assertEquals(
            "Erster Subscriber muss genau ein Upstream-Abo ausgelöst haben",
            1,
            counting.subscriptionCount.get()
        )

        // Innerhalb des Timeouts bleiben — 1 Sekunde vor Ablauf.
        advanceTimeBy(AppConstants.FLOW_SHARING_TIMEOUT_MS - 1000)

        manager.favoriteComponentsFlow.test {
            awaitItem()
            cancelAndIgnoreRemainingEvents()
        }
        testScheduler.runCurrent()
        assertEquals(
            "Resubscribe innerhalb des Timeouts darf KEIN neues Upstream-Abo erzeugen",
            1,
            counting.subscriptionCount.get()
        )

        scope.cancel()
    }

    /**
     * `WhileSubscribed(5000)`: 5s+ nach dem letzten Unsubscribe droppt der
     * Upstream. Der nächste Subscriber triggert ein komplett neues Abo —
     * Counter geht auf 2.
     *
     * Gleiches `runCurrent`-Muster wie im Reuse-Test: wir wollen die
     * Kausalkette sauber haben — der Upstream wird durch
     * `advanceTimeBy(timeout + 1000)` gedroppt, nicht schon vorher durch ein
     * versehentliches `advanceUntilIdle`.
     */
    @Test
    fun `WhileSubscribed - upstream restarts on resubscribe after timeout`() = runTest {
        val scope = CoroutineScope(mainDispatcherRule.testDispatcher + SupervisorJob())
        val counting = CountingDataStore(FakeDataStore())
        val manager = buildManager(
            counting,
            scope,
            SharingStarted.WhileSubscribed(AppConstants.FLOW_SHARING_TIMEOUT_MS)
        )

        manager.favoriteComponentsFlow.test {
            awaitItem()
            cancelAndIgnoreRemainingEvents()
        }
        testScheduler.runCurrent()
        assertEquals(1, counting.subscriptionCount.get())

        // Deutlich über das Timeout hinaus — 1 Sekunde Puffer reicht.
        advanceTimeBy(AppConstants.FLOW_SHARING_TIMEOUT_MS + 1000)

        manager.favoriteComponentsFlow.test {
            awaitItem()
            cancelAndIgnoreRemainingEvents()
        }
        testScheduler.runCurrent()
        assertEquals(
            "Nach Timeout muss der Upstream neu abonniert werden",
            2,
            counting.subscriptionCount.get()
        )

        scope.cancel()
    }

    // ========================================================================
    // 5. AUTHORITATIVE SNAPSHOT vs STALE REPLAY (backup-stale-read regression)
    // ========================================================================

    /**
     * Discriminating regression test for the backup-stale-replay fix
     * (`getFavoriteComponentsSnapshot`). Reproduces the exact production
     * condition: the shared flow is warmed to an OLD value, its
     * `WhileSubscribed` upstream then drops (replay=1 retains OLD), and the store
     * changes to a NEW value with NO active subscriber — so the replay cache is
     * stale while the store is fresh.
     *
     * We assert BOTH sides so the discrimination is self-proving, without
     * touching production code:
     *  - the authoritative snapshot returns the fresh store value (NEW), and
     *  - the hot flow's replay is provably stale (OLD).
     * A buggy impl reading `favoriteComponentsFlow.first()` would therefore
     * return OLD and fail the snapshot assertion — exactly the pre-fix bug. The
     * plain ImplTest "reads current value without a warm subscriber" test cannot
     * catch this, because it never warms-then-stalls the flow.
     */
    @Test
    fun `getFavoriteComponentsSnapshot returns the fresh store value while the shared flow replay is stale`() = runTest {
        val scope = CoroutineScope(mainDispatcherRule.testDispatcher + SupervisorJob())
        val fake = FakeDataStore()
        fake.setInitialData(preferencesOf(favoritesKey to setOf(compA))) // OLD
        val manager = buildManager(
            fake, scope, SharingStarted.WhileSubscribed(AppConstants.FLOW_SHARING_TIMEOUT_MS)
        )

        // Warm the shared flow so replay=1 caches OLD, then unsubscribe.
        manager.favoriteComponentsFlow.test {
            assertEquals(setOf(compA), awaitItem())
            cancelAndIgnoreRemainingEvents()
        }
        testScheduler.runCurrent()

        // Let WhileSubscribed drop the upstream; the replay cache (OLD) is retained.
        advanceTimeBy(AppConstants.FLOW_SHARING_TIMEOUT_MS + 1000)
        testScheduler.runCurrent()

        // Change the store with NO active subscriber — the flow never observes it,
        // so its replay stays OLD. Exactly the backup-screen situation.
        manager.saveFavoriteComponents(listOf(compB)) // NEW
        advanceUntilIdle()

        // Authoritative snapshot returns the fresh store value...
        assertEquals(setOf(compB), manager.getFavoriteComponentsSnapshot())
        // ...while the hot flow's replay is provably stale (returns OLD). This is
        // the discrimination: a buggy impl reading the flow would return OLD here.
        assertEquals(setOf(compA), manager.favoriteComponentsFlow.first())

        scope.cancel()
    }
}
