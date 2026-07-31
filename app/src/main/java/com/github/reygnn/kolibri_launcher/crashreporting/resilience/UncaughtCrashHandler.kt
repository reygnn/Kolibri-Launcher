package com.github.reygnn.kolibri_launcher.crashreporting.resilience

import android.os.Process
import android.util.Log
import timber.log.Timber
import kotlin.system.exitProcess

/**
 * The uncaught-crash handler — one path, both builds (ACRA_SPEC.md C.1, G1).
 *
 * Wraps [defaultHandler] (ACRA's `ErrorReporterImpl`, registered in
 * `attachBaseContext`). It adds only two things that both RELEASE and DEBUG
 * want, then delegates the critical persist+schedule+kill to ACRA:
 *  - an OOM `System.gc()` BEFORE ACRA allocates a report (best-effort headroom
 *    so an OOM crash can still be built and persisted);
 *  - a plain `Timber.e` log (Rule 9: this is crash-infra; `silentError` would
 *    throw in DEBUG straight back into the path it is the safety net for).
 *
 * **No flush window.** ACRA sends out-of-process, the report is a file that
 * survives the kill, and `ErrorReporter` exposes no completion callback (§13) —
 * so there is nothing to flush here. The old `Thread.sleep(500)` is gone.
 *
 * **The kill is ACRA's; ours is a backstop.** ACRA's `ProcessFinisher` already
 * `exitProcess(10)`s in both builds (C2). [killSwitch] is only reached if ACRA
 * did NOT kill (an `endApplication` veto, or the default handler threw).
 *
 * Install AFTER `ACRA.init` (§12·2) so [defaultHandler] is ACRA's reporter, not
 * the pre-ACRA handler — otherwise the wrapper delegates past the reporter and
 * no report is produced.
 */
class UncaughtCrashHandler(
    private val defaultHandler: Thread.UncaughtExceptionHandler?,
    private val killSwitch: () -> Unit = {
        Process.killProcess(Process.myPid())
        exitProcess(10)
    },
) : Thread.UncaughtExceptionHandler {

    override fun uncaughtException(thread: Thread, throwable: Throwable) {
        if (throwable is OutOfMemoryError) {
            try {
                // Best-effort: free memory BEFORE ACRA allocates its report.
                System.gc()
            } catch (ignored: Throwable) {
                // Even GC can fail under memory pressure — ignore.
            }
        }

        Timber.e(throwable, "UNCAUGHT EXCEPTION in thread: ${thread.name}")

        try {
            // = ACRA: persist the report, schedule the out-of-process send, and
            // exitProcess(10). This is what actually kills in the normal case.
            defaultHandler?.uncaughtException(thread, throwable)
        } catch (t: Throwable) {
            // Log.e, not Timber, to avoid re-entering a possibly-broken tree.
            Log.e(TAG, "Default (ACRA) uncaught handler failed", t)
        }

        // Backstop: only reached if ACRA did NOT terminate (veto / threw).
        // Deterministic termination — no zombie (C2).
        killSwitch()
    }

    private companion object {
        const val TAG = "UncaughtCrashHandler"
    }
}
