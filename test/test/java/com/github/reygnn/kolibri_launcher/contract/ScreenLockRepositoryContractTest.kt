package com.github.reygnn.kolibri_launcher.contract

import app.cash.turbine.test
import com.github.reygnn.kolibri_launcher.domain.repository.ScreenLockRepository
import com.github.reygnn.kolibri_launcher.fakes.FakeScreenLockRepository
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Contract Tests für ScreenLockRepository.
 *
 * Dieses Repository koordiniert Screen Lock und Notification-Anfragen
 * zwischen ViewModel und Accessibility Service.
 */
abstract class ScreenLockRepositoryContractTest {

    abstract fun createRepository(): ScreenLockRepository

    // ===========================================
    // SERVICE STATE
    // ===========================================

    @Test
    fun `setServiceState - updates flow to true`() = runTest {
        val repo = createRepository()
        repo.setServiceState(false) // Start mit false

        repo.setServiceState(true)

        assertTrue(repo.isLockingAvailableFlow.value)
    }

    @Test
    fun `setServiceState - updates flow to false`() = runTest {
        val repo = createRepository()
        repo.setServiceState(true)

        repo.setServiceState(false)

        assertFalse(repo.isLockingAvailableFlow.value)
    }

    // ===========================================
    // REQUEST LOCK
    // ===========================================

    @Test
    fun `requestLock - emits when service available`() = runTest {
        val repo = createRepository()
        repo.setServiceState(true)

        repo.lockRequestFlow.test {
            repo.requestLock()
            awaitItem() // Sollte Unit emittieren
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `requestLock - does not emit when service unavailable`() = runTest {
        val repo = createRepository()
        repo.setServiceState(false)

        repo.lockRequestFlow.test {
            repo.requestLock()
            expectNoEvents() // Sollte nichts emittieren
        }
    }

    // ===========================================
    // REQUEST OPEN NOTIFICATIONS
    // ===========================================

    @Test
    fun `requestOpenNotifications - emits when service available`() = runTest {
        val repo = createRepository()
        repo.setServiceState(true)

        repo.openNotificationsRequestFlow.test {
            repo.requestOpenNotifications()
            awaitItem()
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `requestOpenNotifications - does not emit when service unavailable`() = runTest {
        val repo = createRepository()
        repo.setServiceState(false)

        repo.openNotificationsRequestFlow.test {
            repo.requestOpenNotifications()
            expectNoEvents()
        }
    }

    // ===========================================
    // PURGE REPOSITORY
    // ===========================================

    @Test
    fun `purgeRepository - does not throw`() = runTest {
        val repo = createRepository()
        repo.setServiceState(true)

        repo.purgeRepository()

        assertTrue(true) // Kein Crash
    }
}

/**
 * Verifiziert den Fake
 */
class FakeScreenLockRepositoryContractTest : ScreenLockRepositoryContractTest() {
    override fun createRepository(): ScreenLockRepository = FakeScreenLockRepository()
}