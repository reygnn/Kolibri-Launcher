package com.github.reygnn.kolibri_launcher.data

import app.cash.turbine.test
import com.github.reygnn.kolibri_launcher.rule.MainDispatcherRule
import com.github.reygnn.kolibri_launcher.rule.TimberRule
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Rule
import org.junit.Test

/**
 * Tests für [ScreenLockRepositoryImpl].
 *
 * Hinweis zu MockK:
 *   [ScreenLockRepositoryImpl] hat einen parameterlosen Konstruktor und keinerlei
 *   Dependencies — es ist ein reiner In-Memory-State-Holder mit
 *   `MutableStateFlow` / `MutableSharedFlow`. Daher wird hier kein MockK
 *   verwendet; es gibt nichts zu stubben oder zu verifizieren außer
 *   beobachtbarem Flow-Verhalten.
 *
 * Convention-Compliance (siehe TESTING_CONVENTIONS.kt):
 *   - Single dispatcher via [MainDispatcherRule]
 *   - Top-level `runTest { }`, kein eigener TestScope/StandardTestDispatcher
 *   - Korrektes `rule`-Package (Singular) für [TimberRule] und
 *     [MainDispatcherRule]
 *   - JUnit-Asserts (`org.junit.Assert.*`), nicht `kotlin.test.*`
 */
@ExperimentalCoroutinesApi
class ScreenLockRepositoryImplTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    @get:Rule
    val timberRule = TimberRule()

    private lateinit var screenLockManager: ScreenLockRepositoryImpl

    @org.junit.Before
    fun setup() {
        screenLockManager = ScreenLockRepositoryImpl()
    }

    // ========== isLockingAvailableFlow — Basisverhalten ==========

    @Test
    fun `isLockingAvailableFlow - initially returns false`() = runTest {
        assertFalse(screenLockManager.isLockingAvailableFlow.value)
    }

    @Test
    fun `isLockingAvailableFlow - emits true when service state is set to true`() = runTest {
        screenLockManager.isLockingAvailableFlow.test {
            assertEquals(false, awaitItem())

            screenLockManager.setServiceState(true)

            assertEquals(true, awaitItem())
        }
    }

    @Test
    fun `isLockingAvailableFlow - emits false when service state is set to false`() = runTest {
        screenLockManager.setServiceState(true)

        screenLockManager.isLockingAvailableFlow.test {
            assertEquals(true, awaitItem())

            screenLockManager.setServiceState(false)

            assertEquals(false, awaitItem())
        }
    }

    // ========== Crash-Resistance ==========

    @Test
    fun `setServiceState - rapid state changes - handles correctly`() = runTest {
        screenLockManager.isLockingAvailableFlow.test {
            assertEquals(false, awaitItem())

            screenLockManager.setServiceState(true)
            assertEquals(true, awaitItem())

            screenLockManager.setServiceState(false)
            assertEquals(false, awaitItem())

            screenLockManager.setServiceState(true)
            assertEquals(true, awaitItem())

            screenLockManager.setServiceState(false)
            assertEquals(false, awaitItem())
        }
    }

    @Test
    fun `setServiceState - called with same value multiple times - emits only once`() = runTest {
        screenLockManager.isLockingAvailableFlow.test {
            assertEquals(false, awaitItem())

            screenLockManager.setServiceState(true)
            assertEquals(true, awaitItem())

            // Setting same value again should not emit
            screenLockManager.setServiceState(true)
            expectNoEvents()

            screenLockManager.setServiceState(true)
            expectNoEvents()
        }
    }

    @Test
    fun `isLockingAvailableFlow - multiple collectors - all receive updates`() = runTest {
        screenLockManager.isLockingAvailableFlow.test {
            assertEquals(false, awaitItem())

            screenLockManager.isLockingAvailableFlow.test {
                assertEquals(false, awaitItem())

                screenLockManager.setServiceState(true)

                // Both collectors should receive the update
                assertEquals(true, awaitItem())
            }

            assertEquals(true, awaitItem())
        }
    }

    @Test
    fun `setServiceState - alternating true false - emits correctly`() = runTest {
        screenLockManager.isLockingAvailableFlow.test {
            assertEquals(false, awaitItem())

            repeat(10) { i ->
                val newState = i % 2 == 0
                screenLockManager.setServiceState(newState)
                assertEquals(newState, awaitItem())
            }
        }
    }

    @Test
    fun `isLockingAvailableFlow - collector cancelled - does not affect other collectors`() =
        runTest {
            screenLockManager.isLockingAvailableFlow.test {
                awaitItem()
                cancel()
            }

            screenLockManager.isLockingAvailableFlow.test {
                assertEquals(false, awaitItem())

                screenLockManager.setServiceState(true)
                assertEquals(true, awaitItem())
            }
        }

    // ========== Open Notifications ==========

    @Test
    fun `requestOpenNotifications - when service is available - emits request`() = runTest {
        screenLockManager.setServiceState(true)

        screenLockManager.openNotificationsRequestFlow.test {
            screenLockManager.requestOpenNotifications()
            assertEquals(Unit, awaitItem())
        }
    }

    @Test
    fun `requestOpenNotifications - when service is NOT available - does NOT emit request`() =
        runTest {
            screenLockManager.setServiceState(false)

            screenLockManager.openNotificationsRequestFlow.test {
                screenLockManager.requestOpenNotifications()
                expectNoEvents()
            }
        }

    // ========== purgeRepository ==========

    @Test
    fun `purgeRepository - does nothing and does not crash`() = runTest {
        // Act
        screenLockManager.purgeRepository()

        // Assert
        // Wir können hier nur prüfen, ob keine Exception fliegt.
        // Da der Manager keinen persistierten State hat, gibt es nichts zu verifizieren.
        // Der Test dient der Code-Coverage und Sicherheit.
    }
}
