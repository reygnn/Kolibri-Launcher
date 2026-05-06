package com.github.reygnn.kolibri_launcher.ui.util

import android.os.Handler
import android.os.Looper
import com.google.common.truth.Truth.assertThat
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * Tests the recovery-side behavior of [RecoveryWatchdog]. The watchdog
 * is a raw [Thread] (the convention break is documented in the class
 * KDoc) so the tests use [CountDownLatch] for synchronization rather
 * than coroutine-test machinery — same fewer-moving-parts argument as
 * the production code.
 *
 * Robolectric is required because [Looper.getMainLooper] returns null
 * on a plain JVM. We never use Robolectric to *manipulate* the looper
 * — see the per-test setup for how the "main is alive" vs "main is hung"
 * cases are simulated. ShadowLooper-idle tricks would couple the tests
 * to Robolectric's scheduling internals; the latch approach works on
 * any timing model that delivers `Handler.post` to a running looper.
 */
@RunWith(RobolectricTestRunner::class)
class RecoveryWatchdogTest {

    private val watchdogs = mutableListOf<RecoveryWatchdog>()

    @After fun tearDown() {
        // Watchdogs are daemons so they'd die with the JVM, but
        // interrupting also stops them cleanly between tests so a slow
        // test doesn't bleed a still-ticking thread into the next one.
        watchdogs.forEach { it.interrupt() }
        watchdogs.forEach { it.join(1_000) }
    }

    private fun watchdog(
        timeoutMs: Long,
        killSwitch: () -> Unit,
    ): RecoveryWatchdog =
        RecoveryWatchdog(timeoutMs = timeoutMs, killSwitch = killSwitch)
            .also { watchdogs += it }

    @Test
    fun `main looper alive - killSwitch never fires across multiple tick cycles`() {
        val killCount = AtomicInteger(0)
        val mainPump = Handler(Looper.getMainLooper())

        val w = watchdog(timeoutMs = 50) { killCount.incrementAndGet() }
        w.start()

        // Simulate a healthy main looper: idle Robolectric's queue
        // repeatedly so the watchdog's posted tick Runnables actually
        // run. Repeat for ~6 tick cycles (300 ms) so we catch any
        // accidental periodic kill.
        repeat(60) {
            Shadows.shadowOf(Looper.getMainLooper()).idle()
            // Sleep on the test thread, NOT the main thread. The test
            // thread is what runs Robolectric's manual idle() call;
            // the watchdog ticks against the main looper independently.
            Thread.sleep(5)
        }

        assertThat(killCount.get()).isEqualTo(0)
    }

    @Test
    fun `main looper hung - killSwitch fires exactly once`() {
        val fired = CountDownLatch(1)
        val killCount = AtomicInteger(0)

        val w = watchdog(timeoutMs = 50) {
            killCount.incrementAndGet()
            fired.countDown()
        }
        w.start()

        // Don't idle the main looper — the posted tick Runnable sits
        // in the queue, never runs, watchdog observes ticked=false on
        // the next sleep wake-up, fires killSwitch.
        val firedInTime = fired.await(2_000, TimeUnit.MILLISECONDS)

        assertThat(firedInTime).isTrue()
        // Watchdog returns from run() immediately after killSwitch in
        // the production path. Even with the test double that doesn't
        // kill the JVM, the loop exits — so killCount stays at 1.
        // Give the thread a moment to settle before asserting count.
        w.join(500)
        assertThat(killCount.get()).isEqualTo(1)
    }

    @Test
    fun `interrupt - thread stops cleanly without firing killSwitch`() {
        val killCount = AtomicInteger(0)

        // Long timeout so the only realistic exit path is interrupt:
        // 10 s sleep means the watchdog is parked in sleep() when we
        // interrupt, exercising the InterruptedException catch.
        val w = watchdog(timeoutMs = 10_000) { killCount.incrementAndGet() }
        w.start()

        // Let the watchdog enter its first sleep before interrupting,
        // otherwise the interrupt may land between iterations rather
        // than during sleep(). 50 ms is plenty for a Thread.start()
        // followed by Handler.post() + Thread.sleep() to enter the
        // parked state.
        Thread.sleep(50)
        w.interrupt()
        w.join(1_000)

        assertThat(w.isAlive).isFalse()
        assertThat(killCount.get()).isEqualTo(0)
    }
}
