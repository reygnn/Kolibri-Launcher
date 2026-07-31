package com.github.reygnn.kolibri_launcher.crashreporting.resilience

import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Pins the [RecoveryWatchdog] trip sequence via [RecoveryWatchdog.onStallDetected]
 * — the logic extracted from the sleep/tick loop (C.8). Runs under Robolectric
 * because constructing the watchdog builds a main-looper `Handler` and the
 * capture-failure swallow logs via `android.util.Log.e`.
 */
@RunWith(RobolectricTestRunner::class)
class RecoveryWatchdogTest {

    private val loopGuard = mockk<LoopGuard>(relaxed = true)
    private val captured = mutableListOf<Throwable>()
    private var kills = 0

    private fun watchdog(capture: (Throwable) -> Unit = { captured += it }) = RecoveryWatchdog(
        loopGuard = loopGuard,
        capture = capture,
        mainThread = Thread.currentThread(),
        killSwitch = { kills++ },
    )

    @Test
    fun `first trip captures a stall exception and kills`() {
        every { loopGuard.shouldSuppressKill() } returns false

        watchdog().onStallDetected()

        assertEquals(1, captured.size)
        assertTrue(captured.single() is WatchdogStallException)
        assertEquals(1, kills)
        verify(exactly = 1) { loopGuard.recordKill() }
    }

    @Test
    fun `a suppressed trip captures but does not kill`() {
        every { loopGuard.shouldSuppressKill() } returns true

        watchdog().onStallDetected()

        assertEquals(1, captured.size) // capture still happens
        assertEquals(0, kills) // loop-guard broke the loop
        verify(exactly = 0) { loopGuard.recordKill() }
    }

    @Test
    fun `a capture failure is swallowed and the kill still fires`() {
        every { loopGuard.shouldSuppressKill() } returns false

        // Must not throw (ST1): the kill has priority over the capture.
        watchdog(capture = { throw RuntimeException("acra down") }).onStallDetected()

        assertEquals(1, kills)
        verify(exactly = 1) { loopGuard.recordKill() }
    }
}
