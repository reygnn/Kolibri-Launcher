package com.github.reygnn.kolibri_launcher.ui.util

import android.os.Handler
import android.os.Looper
import android.os.Process
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.system.exitProcess

/**
 * Self-defense watchdog: kills the process if the main thread stops
 * dispatching for [timeoutMs]. Pairs with
 * [com.github.reygnn.kolibri_launcher.ui.util.AnrReporter] — the reporter
 * captures *what* happened (post-mortem via `ApplicationExitInfo` on the
 * next launch); this watchdog ensures the *recovery* happens fast
 * (process restart in seconds rather than waiting 10–15 s for the system
 * to give up on a hung HOME launcher).
 *
 * ## Why a raw [Thread], not a coroutine
 *
 * Deliberate convention break — see TESTING_CONVENTIONS.kt for the
 * project-wide note. The watchdog must work *when something is broken*,
 * specifically when the main looper is hung (the very condition it
 * detects). A coroutine that runs on a custom dispatcher is fine in
 * isolation, but if the kotlinx scheduler itself ever ends up in an
 * inconsistent state — internal queue corruption, contended dispatcher
 * shutdown during a JVM-level error, anything that's normally invisible
 * — the watchdog goes silent at exactly the moment we need it. A
 * `Thread` with a daemon flag has strictly fewer dependencies: it talks
 * to the JVM scheduler directly, runs `Thread.sleep` (system call),
 * posts to the main `Handler` (Android-platform primitive). Same shape
 * as the old ANRWatchDog 1.4.0; same reasoning.
 *
 * Don't "modernise" this to coroutines later. The whole point is that
 * the recovery path has fewer moving parts than what it watches.
 *
 * ## Why this doesn't report
 *
 * The kill is silent: no Timber log to ACRA, no synthetic exception, no
 * file write. [AnrReporter] picks up the resulting exit on the next app
 * start by querying `ApplicationExitInfo`. Adding reporting here would
 * (a) duplicate the AnrReporter work, and (b) require I/O on the very
 * code path that's already proven to be hung — a recipe for more hangs.
 *
 * **Caveat — AEI reason classification under self-kill:** when *we* send
 * `SIGKILL` via [Process.killProcess] at [timeoutMs], the resulting
 * `ApplicationExitInfo` record is classified as `REASON_SIGNALED`, NOT
 * `REASON_ANR`. Per the Android docs on
 * `ApplicationExitInfo.getTraceInputStream`, the system *does* still
 * attach the ANR trace to such records when it had detected the ANR
 * before the kill ("ANR trace data may be associated with an
 * ApplicationExitInfo instance even though that instance's exit reason
 * is not 'ANR'") — but [AnrReporter]'s filter (`reason == REASON_ANR`)
 * skips them, so the report goes uncollected.
 *
 * Trade-off accepted: self-defense is the load-bearing job here. Real
 * system-driven kills (we don't fire, system times out at ~10 s) keep
 * `REASON_ANR` and remain the common case anyway. Reporting was *never*
 * the busy path in this project — single-digit ANRs in the project's
 * history.
 *
 * Future-self note: if self-kill ANRs ever show up often enough that
 * we want them in our reports, the fix is in [AnrReporter], not here —
 * extend the filter to also include `REASON_SIGNALED` records that
 * carry a non-null `traceInputStream`. Watch out: `traceInputStream`
 * is single-read, so the materialisation has to happen inside
 * `AnrReporter.toReport` (or be cached on the same iteration —
 * filtering on it directly invalidates it for the consumer). Out of
 * scope for this PR.
 *
 * ## Why 8 s default
 *
 * 8 s sits between two natural Android boundaries:
 *  - 5 s: the system's own ANR detection threshold (input dispatch /
 *    foreground service). By 5 s the system has already started its
 *    ANR-handling, so [AnrReporter] gets its data even if we self-kill
 *    a few seconds later.
 *  - ~10–15 s: when the system gives up on the unresponsive HOME
 *    activity and offers the user the "App not responding — Wait /
 *    Close" dialog or kills the process outright.
 *
 * Self-killing at 8 s gets the launcher back on the screen seconds
 * faster than waiting for the system, with no worse user-visible state
 * (the user already saw a frozen launcher for 8 s — recovery beats
 * watching the freeze drag on).
 *
 * ## Lifecycle
 *
 * Single instance, started once per process from
 * `KolibriLauncherApp.onCreate`. Daemon flag means the watchdog dies
 * with the process — no explicit teardown needed. Started via
 * `mainHandler.post { watchdog.start() }` rather than directly so the
 * first tick lands *after* `onCreate` has returned the main thread to
 * its dispatch loop; otherwise the heavy bootstrap work (DataStore
 * reads, migrations, receiver registration) could legitimately block
 * past [timeoutMs] and trigger a kill-restart-loop on a HOME process
 * that the OS keeps eagerly relaunching.
 *
 * @param timeoutMs how long the main looper may be silent before we
 *     declare "hung" and self-kill. Default 8 s; see "Why 8 s" above.
 * @param killSwitch what to do when the threshold trips. Production
 *     default is `Process.killProcess(myPid)` plus a defensive
 *     `exitProcess(10)` (combo: SIGKILL almost always lands first, the
 *     `exitProcess` is a fail-safe path if for any reason the SIGKILL
 *     is buffered or no-op'd by the OS). Injected so tests can verify
 *     "the kill path was reached" without actually killing the JVM.
 */
class RecoveryWatchdog(
    private val timeoutMs: Long = DEFAULT_TIMEOUT_MS,
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
                // Clean stop path. Re-set the interrupted flag is not
                // needed — we exit immediately, no further loop iteration.
                return
            }
            if (!ticked.get()) {
                // Main looper didn't dispatch the tick within timeoutMs.
                // Pull the trigger and exit the loop. killSwitch is
                // expected to terminate the process; the `return` is
                // belt-and-braces so the test doubles that don't kill
                // also stop the thread cleanly.
                killSwitch()
                return
            }
        }
    }

    private companion object {
        const val DEFAULT_TIMEOUT_MS = 8_000L
    }
}
