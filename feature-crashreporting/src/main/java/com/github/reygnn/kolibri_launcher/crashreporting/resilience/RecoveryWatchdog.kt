package com.github.reygnn.kolibri_launcher.crashreporting.resilience

import android.os.Handler
import android.os.Looper
import android.os.Process
import android.util.Log
import androidx.annotation.VisibleForTesting
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.system.exitProcess

/**
 * Self-defense watchdog: on a main-looper stall > [timeoutMs] it CAPTURES the
 * stall and then kills the process so the OS restarts the HOME launcher cleanly
 * (ACRA_SPEC.md C.2, G2). This app is the HOME launcher, so a hung main thread
 * means a dead home button for the whole device — killing gets the user their
 * device back in seconds rather than waiting ~10 s for the system.
 *
 * ## Kill AND capture (not either/or)
 *
 * A `Process.killProcess` produces no `REASON_ANR`, so [AnrReporter] never sees
 * a self-killed stall (C3/X1). Therefore the watchdog captures the report
 * itself, BEFORE the kill: `mainThread.stackTrace` folded into a
 * [WatchdogStallException] and handed to the [capture] seam (wired to
 * `reportToAcra` → AcraTree). That is a small file write on the DAEMON thread, not
 * the hung main thread; the persisted report survives the kill and is sent
 * out-of-process. Capture is swallowed — the kill has priority (ST1).
 *
 * ## Loop-guard
 *
 * A *deterministic* post-`onCreate` wedge would re-trip on every clean restart
 * → a kill-restart loop on the HOME process. [loopGuard] counts recent kills;
 * once its threshold is hit the watchdog captures but does NOT kill (fallback to
 * "report only"), breaking the loop.
 *
 * ## Why a raw [Thread], not a coroutine
 *
 * The watchdog must work *when something is broken* — specifically when the main
 * looper is hung. A `Thread` with a daemon flag has strictly fewer dependencies
 * than a coroutine on a custom dispatcher (whose scheduler could itself be in an
 * inconsistent state at exactly the wrong moment). Don't "modernise" this to
 * coroutines — the recovery path must have fewer moving parts than what it
 * watches.
 *
 * ## Lifecycle
 *
 * Single instance, started once per process from `KolibriLauncherApp.onCreate`
 * via `mainHandler.post { start() }` — so the first tick lands *after* onCreate
 * returned the main thread to its dispatch loop, otherwise heavy cold-start work
 * could legitimately block past [timeoutMs] and self-trip.
 *
 * @param timeoutMs how long the main looper may be silent before "hung". Default
 *   8 s — between the system's ~5 s input-ANR threshold and the ~10 s broadcast
 *   threshold (§6). @param loopGuard cross-restart kill counter. @param capture
 *   sink for the stall report (prod: `reportToAcra`). @param mainThread the thread
 *   whose stack is captured. @param killSwitch what to do on trip (prod:
 *   `killProcess` + `exitProcess(10)`); injected so tests verify the path
 *   without killing the JVM.
 */
class RecoveryWatchdog(
    private val timeoutMs: Long = DEFAULT_TIMEOUT_MS,
    private val loopGuard: LoopGuard,
    private val capture: (Throwable) -> Unit,
    private val mainThread: Thread = Looper.getMainLooper().thread,
    private val killSwitch: () -> Unit = {
        Process.killProcess(Process.myPid())
        exitProcess(10)
    },
) : Thread("kolibri-anr-recovery") {

    init { isDaemon = true }

    private val mainHandler = Handler(Looper.getMainLooper())

    override fun run() {
        while (!isInterrupted) {
            val ticked = AtomicBoolean(false)
            mainHandler.post { ticked.set(true) }
            try {
                sleep(timeoutMs)
            } catch (_: InterruptedException) {
                return // clean stop
            }
            if (!ticked.get()) {
                onStallDetected()
                return
            }
        }
    }

    /**
     * The trip sequence (steps 0–3, §6), extracted from the sleep/tick loop so
     * it is JVM-testable with injected [loopGuard]/[capture]/[killSwitch].
     */
    @VisibleForTesting
    internal fun onStallDetected() {
        // 0. Loop-guard: a deterministic re-trip suppresses the kill.
        val suppressKill = loopGuard.shouldSuppressKill()

        // 1–2. Capture BEFORE the kill (C3/X1). Swallowed — kill has priority
        // (ST1). Log.e, not the injected capture, for the swallow, so a broken
        // capture can't recurse into itself.
        try {
            capture(WatchdogStallException(mainThread.stackTrace))
        } catch (t: Throwable) {
            Log.e(TAG, "Stall capture failed", t)
        }

        // 3. Kill, unless the loop-guard broke the loop.
        if (!suppressKill) {
            loopGuard.recordKill()
            killSwitch()
        }
    }

    private companion object {
        const val DEFAULT_TIMEOUT_MS = 8_000L
        const val TAG = "RecoveryWatchdog"
    }
}

/**
 * Synthetic throwable carrying a captured main-looper stall into the delivery
 * path. Its stack trace is the *main thread's* stack at capture time (set in
 * [init]) — that is the wedge, not this daemon-thread frame.
 */
class WatchdogStallException(mainStack: Array<StackTraceElement>) :
    RuntimeException("Main looper stalled (watchdog capture)") {
    init {
        stackTrace = mainStack
    }
}
