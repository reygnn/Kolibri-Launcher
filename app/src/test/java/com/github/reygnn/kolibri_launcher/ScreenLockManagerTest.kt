package com.github.reygnn.kolibri_launcher

import app.cash.turbine.test
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import org.junit.Before
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse

@ExperimentalCoroutinesApi
class ScreenLockManagerTest {

    private lateinit var screenLockManager: ScreenLockManager

    @Before
    fun setup() {
        screenLockManager = ScreenLockManager()
    }

    // ========== EXISTING TESTS ==========

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

    @Test
    fun `requestLock - when service is available - emits lock request`() = runTest {
        screenLockManager.setServiceState(true)

        screenLockManager.lockRequestFlow.test {
            screenLockManager.requestLock()
            assertEquals(Unit, awaitItem())
        }
    }

    @Test
    fun `requestLock - when service is NOT available - does NOT emit lock request`() = runTest {
        screenLockManager.setServiceState(false)

        screenLockManager.lockRequestFlow.test {
            screenLockManager.requestLock()
            expectNoEvents()
        }
    }

    // ========== NEW CRASH-RESISTANCE TESTS ==========

    @Test
    fun `setServiceState - rapid state changes - handles correctly`() = runTest {
        screenLockManager.isLockingAvailableFlow.test {
            assertEquals(false, awaitItem())

            // Rapid toggles
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
    fun `requestLock - called multiple times when available - emits multiple events`() = runTest {
        screenLockManager.setServiceState(true)

        screenLockManager.lockRequestFlow.test {
            screenLockManager.requestLock()
            assertEquals(Unit, awaitItem())

            screenLockManager.requestLock()
            assertEquals(Unit, awaitItem())

            screenLockManager.requestLock()
            assertEquals(Unit, awaitItem())
        }
    }

    @Test
    fun `requestLock - called when service becomes unavailable - stops emitting`() = runTest {
        screenLockManager.setServiceState(true)

        screenLockManager.lockRequestFlow.test {
            screenLockManager.requestLock()
            assertEquals(Unit, awaitItem())

            // Service becomes unavailable
            screenLockManager.setServiceState(false)

            // Request should not emit
            screenLockManager.requestLock()
            expectNoEvents()
        }
    }

    @Test
    fun `requestLock - before service state is ever set - does not emit`() = runTest {
        // Don't call setServiceState at all
        screenLockManager.lockRequestFlow.test {
            screenLockManager.requestLock()
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
    fun `lockRequestFlow - multiple collectors - all receive events`() = runTest {
        screenLockManager.setServiceState(true)

        screenLockManager.lockRequestFlow.test {
            screenLockManager.lockRequestFlow.test {
                screenLockManager.requestLock()

                // Both should receive
                assertEquals(Unit, awaitItem())
            }

            assertEquals(Unit, awaitItem())
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
    fun `requestLock - rapid requests when available - all emit`() = runTest {
        screenLockManager.setServiceState(true)

        screenLockManager.lockRequestFlow.test {
            // 100 rapid requests
            repeat(100) {
                screenLockManager.requestLock()
            }

            // All should emit
            repeat(100) {
                assertEquals(Unit, awaitItem())
            }
        }
    }

    @Test
    fun `requestLock - interleaved with service state changes - handles correctly`() = runTest {
        screenLockManager.lockRequestFlow.test {
            // Service available
            screenLockManager.setServiceState(true)
            screenLockManager.requestLock()
            assertEquals(Unit, awaitItem())

            // Service unavailable
            screenLockManager.setServiceState(false)
            screenLockManager.requestLock()
            expectNoEvents()

            // Service available again
            screenLockManager.setServiceState(true)
            screenLockManager.requestLock()
            assertEquals(Unit, awaitItem())
        }
    }

    @Test
    fun `isLockingAvailableFlow - collector cancelled - does not affect other collectors`() = runTest {
        screenLockManager.isLockingAvailableFlow.test {
            awaitItem()
            cancel()
        }

        // New collector should still work
        screenLockManager.isLockingAvailableFlow.test {
            assertEquals(false, awaitItem())

            screenLockManager.setServiceState(true)
            assertEquals(true, awaitItem())
        }
    }

    @Test
    fun `lockRequestFlow - collector cancelled - does not affect other collectors`() = runTest {
        screenLockManager.setServiceState(true)

        screenLockManager.lockRequestFlow.test {
            screenLockManager.requestLock()
            awaitItem()
            cancel()
        }

        // New collector should still work
        screenLockManager.lockRequestFlow.test {
            screenLockManager.requestLock()
            assertEquals(Unit, awaitItem())
        }
    }
}