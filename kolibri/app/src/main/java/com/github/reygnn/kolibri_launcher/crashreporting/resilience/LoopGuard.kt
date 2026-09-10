package com.github.reygnn.kolibri_launcher.crashreporting.resilience

import java.io.File

/**
 * Cross-restart guard against a watchdog kill-restart loop (ACRA_SPEC.md C.3,
 * G2). A deterministic post-`onCreate` wedge would re-trip the watchdog on every
 * clean restart; the guard counts recent self-kills and, once
 * [maxKills] have happened within [windowMs], tells [RecoveryWatchdog] to
 * *capture only* and stop killing — breaking the loop instead of restarting the
 * device forever.
 *
 * ## Why a plain file (SPEC-DECISION C-1)
 *
 * The counter must be **cross-restart** (each kill is a new process) and
 * **written synchronously from the daemon thread at kill time** (the process is
 * about to die). That is exactly the constraint the deleted `CrashReportLimiter`
 * used `SharedPreferences` for — but re-introducing that exception right after
 * Belang B removed it would be a step back, and DataStore is suspend-only
 * (unusable synchronously at kill time). So the store is a small plain [File]
 * (timestamp lines, ring over the last [maxKills]+1) under `noBackupFilesDir` —
 * synchronous `java.io`, survives the kill, and `noBackup` so a device transfer
 * does not carry a stale kill history. Pure telemetry, not decision state, so
 * the X2 multi-process concern (consent) does not apply.
 *
 * Every path swallows its own failure: the guard sits on the kill path and must
 * never crash the watchdog. A read failure reads as "no recent kills" (so the
 * kill still fires — recovery has priority); a write failure just loses one
 * timestamp.
 */
class LoopGuard(
    private val store: File,
    private val windowMs: Long = DEFAULT_WINDOW_MS,
    private val maxKills: Int = DEFAULT_MAX_KILLS,
    private val now: () -> Long = { System.currentTimeMillis() },
) {

    /**
     * True when [maxKills] or more self-kills have been recorded within the last
     * [windowMs]. The watchdog then captures the stall but does NOT kill,
     * breaking the restart loop.
     */
    fun shouldSuppressKill(): Boolean {
        val cutoff = now() - windowMs
        return readTimestamps().count { it >= cutoff } >= maxKills
    }

    /** Records a self-kill at [now]. Keeps only the most recent [maxKills]+1. */
    fun recordKill() {
        val updated = (readTimestamps() + now()).takeLast(maxKills + 1)
        writeTimestamps(updated)
    }

    private fun readTimestamps(): List<Long> = try {
        if (!store.exists()) emptyList() else store.readLines().mapNotNull { it.trim().toLongOrNull() }
    } catch (t: Throwable) {
        // Crash-infra: on the kill path, an unreadable store reads as "no recent
        // kills" so recovery still fires. Never crash the watchdog.
        emptyList()
    }

    private fun writeTimestamps(timestamps: List<Long>) {
        try {
            store.writeText(timestamps.joinToString(separator = "\n"))
        } catch (t: Throwable) {
            // Losing one timestamp is benign; never crash the kill path.
        }
    }

    private companion object {
        const val DEFAULT_WINDOW_MS = 60_000L
        const val DEFAULT_MAX_KILLS = 3
    }
}
