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
 * The [ScreenLockRepository] interface combines state
 * ([ScreenLockRepository.isLockingAvailableFlow]) with one event stream
 * ([ScreenLockRepository.openNotificationsRequestFlow]).
 * Both implementations use a `MutableSharedFlow<Unit>` without a buffer
 * for the event — `emit` therefore suspends when no subscriber is
 * listening. Tests consistently use Turbine to build a collector before
 * emitting.
 *
 * CORE CONTRACT PROPERTY:
 *   `requestOpenNotifications()` emits ONLY when the service has been
 *   reported available via `setServiceState(true)`. This is a safety
 *   property of the interface: without an active accessibility service
 *   no attempt to open the notification panel may even be started. Both
 *   implementations honor this.
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

    // ---------- requestOpenNotifications — emittiert nur wenn available ----------

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

    @Test
    fun `requestOpenNotifications multiple times when available emits multiple events`() = runTest {
        val repo = createRepository()
        repo.setServiceState(true)

        repo.openNotificationsRequestFlow.test {
            repo.requestOpenNotifications()
            assertEquals(Unit, awaitItem())
            repo.requestOpenNotifications()
            assertEquals(Unit, awaitItem())
            repo.requestOpenNotifications()
            assertEquals(Unit, awaitItem())
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `requestOpenNotifications stops emitting when service becomes unavailable mid-stream`() = runTest {
        val repo = createRepository()
        repo.setServiceState(true)

        repo.openNotificationsRequestFlow.test {
            repo.requestOpenNotifications()
            assertEquals(Unit, awaitItem())

            repo.setServiceState(false)

            repo.requestOpenNotifications()
            expectNoEvents()
            cancelAndIgnoreRemainingEvents()
        }
    }
}
