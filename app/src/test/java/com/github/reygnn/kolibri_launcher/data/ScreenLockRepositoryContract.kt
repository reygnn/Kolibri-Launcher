package com.github.reygnn.kolibri_launcher.data

import app.cash.turbine.test
import com.github.reygnn.kolibri_launcher.domain.repository.ScreenLockRepository
import com.github.reygnn.kolibri_launcher.rule.MainDispatcherRule
import com.github.reygnn.kolibri_launcher.rule.TimberRule
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/**
 * ============================================================================
 * SCREEN LOCK REPOSITORY — CONTRACT TEST
 * ============================================================================
 *
 * Siehe [FavoritesRepositoryContract] für Hintergrund und Konventionen.
 *
 * Das [ScreenLockRepository]-Interface kombiniert State (
 * [ScreenLockRepository.isLockingAvailableFlow]) und zwei Event-Streams
 * ([ScreenLockRepository.lockRequestFlow], [ScreenLockRepository.openNotificationsRequestFlow]).
 * Beide Implementierungen benutzen `MutableSharedFlow<Unit>` ohne Buffer für
 * die Events — `emit` hängt also wenn kein Subscriber zuhört. Tests benutzen
 * deshalb durchgängig Turbine, um vor dem Emit einen Collector aufzubauen.
 *
 * KERN-VERTRAGS-PROPERTY:
 *   `requestLock()` und `requestOpenNotifications()` emittieren NUR, wenn der
 *   Service via `setServiceState(true)` als verfügbar gemeldet wurde. Das ist
 *   eine Sicherheits-Eigenschaft des Interfaces: ohne aktiven Accessibility-
 *   Service darf gar nicht erst der Versuch unternommen werden, ein Lock zu
 *   triggern. Beide Implementierungen halten das ein.
 *
 * NICHT IM CONTRACT — bewusste Drifts:
 *
 *   1. **Initial-Wert von `isLockingAvailableFlow`:**
 *      - Manager: startet mit `false` (Service noch nicht verbunden).
 *      - Fake: startet mit `true` (Test-Convenience).
 *      Bewusste Drift, im Fake-KDoc explizit dokumentiert. Tests setzen den
 *      Service-State immer explizit, statt den Default zu erwarten.
 *
 *   2. **`purgeRepository()`:**
 *      - Manager: explizit No-Op (flüchtiger Runtime-State, nicht persistiert).
 *      - Fake: setzt isLockingAvailable auf `true` (= Fake-Default, nicht
 *        Manager-Default!).
 *      Bewusste Drift. Kein Contract-Test.
 *
 * @see FakeScreenLockRepositoryContractTest
 * @see ScreenLockRepositoryImplContractTest
 * ============================================================================
 */
@OptIn(ExperimentalCoroutinesApi::class)
abstract class ScreenLockRepositoryContract {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    @get:Rule
    val timberRule = TimberRule()

    protected abstract fun createRepository(): ScreenLockRepository

    // ---------- setServiceState + isLockingAvailableFlow ----------

    @Test
    fun `setServiceState true reflects in isLockingAvailableFlow`() = runTest {
        val repo = createRepository()
        repo.setServiceState(true)
        assertTrue(repo.isLockingAvailableFlow.value)
    }

    @Test
    fun `setServiceState false reflects in isLockingAvailableFlow`() = runTest {
        val repo = createRepository()
        repo.setServiceState(false)
        assertFalse(repo.isLockingAvailableFlow.value)
    }

    @Test
    fun `setServiceState toggles correctly`() = runTest {
        val repo = createRepository()
        repo.setServiceState(true)
        assertTrue(repo.isLockingAvailableFlow.value)
        repo.setServiceState(false)
        assertFalse(repo.isLockingAvailableFlow.value)
        repo.setServiceState(true)
        assertTrue(repo.isLockingAvailableFlow.value)
    }

    // ---------- requestLock — emittiert nur wenn available ----------

    @Test
    fun `requestLock emits when service is available`() = runTest {
        val repo = createRepository()
        repo.setServiceState(true)

        repo.lockRequestFlow.test {
            repo.requestLock()
            // awaitItem() würde bei nicht-emittiertem Event hängen → Test-Fail.
            // Da wir Unit emittieren, prüfen wir, dass _irgendetwas_ kommt.
            assertEquals(Unit, awaitItem())
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `requestLock does not emit when service is unavailable`() = runTest {
        val repo = createRepository()
        repo.setServiceState(false)

        repo.lockRequestFlow.test {
            repo.requestLock()
            // expectNoEvents bestätigt: keine Emission.
            expectNoEvents()
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `requestLock multiple times when available emits multiple events`() = runTest {
        val repo = createRepository()
        repo.setServiceState(true)

        repo.lockRequestFlow.test {
            repo.requestLock()
            assertEquals(Unit, awaitItem())
            repo.requestLock()
            assertEquals(Unit, awaitItem())
            repo.requestLock()
            assertEquals(Unit, awaitItem())
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `requestLock stops emitting when service becomes unavailable mid-stream`() = runTest {
        val repo = createRepository()
        repo.setServiceState(true)

        repo.lockRequestFlow.test {
            repo.requestLock()
            assertEquals(Unit, awaitItem())

            repo.setServiceState(false)

            repo.requestLock()
            expectNoEvents()
            cancelAndIgnoreRemainingEvents()
        }
    }

    // ---------- requestOpenNotifications — Spiegel von requestLock ----------

    @Test
    fun `requestOpenNotifications emits when service is available`() = runTest {
        val repo = createRepository()
        repo.setServiceState(true)

        repo.openNotificationsRequestFlow.test {
            repo.requestOpenNotifications()
            assertEquals(Unit, awaitItem())
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `requestOpenNotifications does not emit when service is unavailable`() = runTest {
        val repo = createRepository()
        repo.setServiceState(false)

        repo.openNotificationsRequestFlow.test {
            repo.requestOpenNotifications()
            expectNoEvents()
            cancelAndIgnoreRemainingEvents()
        }
    }

    // ---------- Stream-Unabhängigkeit ----------

    /**
     * Vertrags-Eigenschaft: lockRequest und openNotificationsRequest sind
     * separate Event-Streams. requestLock darf nicht in
     * openNotificationsRequestFlow emittieren und umgekehrt.
     */
    @Test
    fun `requestLock does not emit on openNotificationsRequestFlow`() = runTest {
        val repo = createRepository()
        repo.setServiceState(true)

        repo.openNotificationsRequestFlow.test {
            repo.requestLock()
            expectNoEvents()
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `requestOpenNotifications does not emit on lockRequestFlow`() = runTest {
        val repo = createRepository()
        repo.setServiceState(true)

        repo.lockRequestFlow.test {
            repo.requestOpenNotifications()
            expectNoEvents()
            cancelAndIgnoreRemainingEvents()
        }
    }
}
