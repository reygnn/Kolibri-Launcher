package com.github.reygnn.kolibri_launcher.crashreporting.resilience

import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Pins the [RecoveryWatchdog] trip sequence via [RecoveryWatchdog.onStallDetected]
 * — the logic extracted from the sleep/tick loop (C.8). Runs under Robolectric
 * because constructing the watchdog builds a main-looper `Handler`.
 *
 * Capture and kill record into a single ordered [events] list, so the tests pin
 * the ORDER, not just that both happened — the defining C3/X1 invariant is that
 * capture must run BEFORE the kill (a `Process.killProcess` emits no REASON_ANR,
 * so the stall would be invisible if the kill went first).
 */
@RunWith(RobolectricTestRunner::class)
class RecoveryWatchdogTest {

    private val loopGuard = mockk<LoopGuard>(relaxed = true)
    private val events = mutableListOf<String>()

    private fun watchdog(capture: (Throwable) -> Unit = { events += "capture:${it::class.simpleName}" }) =
        RecoveryWatchdog(
            loopGuard = loopGuard,
            capture = capture,
            mainThread = Thread.currentThread(),
            killSwitch = { events += "kill" },
        )

    @Test
    fun `first trip captures a stall exception BEFORE it kills`() {
        every { loopGuard.shouldSuppressKill() } returns false

        watchdog().onStallDetected()

        // Ordering is the invariant: capture:WatchdogStallException must precede
        // kill (C3/X1). A kill-first refactor would break this exact assertion.
        assertEquals(listOf("capture:WatchdogStallException", "kill"), events)
        verify(exactly = 1) { loopGuard.recordKill() }
    }

    @Test
    fun `a suppressed trip captures but does not kill`() {
        every { loopGuard.shouldSuppressKill() } returns true

        watchdog().onStallDetected()

        assertEquals(listOf("capture:WatchdogStallException"), events)
        verify(exactly = 0) { loopGuard.recordKill() }
    }

    @Test
    fun `a capture failure is swallowed and the kill still fires`() {
        every { loopGuard.shouldSuppressKill() } returns false

        // Capture throws (so it never records); the kill must still run after
        // the swallow (ST1 — kill has priority).
        watchdog(capture = { throw RuntimeException("acra is down") }).onStallDetected()

        assertEquals(listOf("kill"), events)
        verify(exactly = 1) { loopGuard.recordKill() }
    }
}
