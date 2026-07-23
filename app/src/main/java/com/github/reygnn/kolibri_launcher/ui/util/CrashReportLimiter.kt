package com.github.reygnn.kolibri_launcher.ui.util

import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import timber.log.Timber

/**
 * ACRA Report Spam Protection
 *
 * Limits crash reports to 1 per exception type per day to prevent backend overload.
 * Uses persistent storage to maintain limits across app restarts.
 *
 * Features:
 * - Persistent storage using SharedPreferences
 * - Automatic cleanup of old entries
 * - Thread-safe operations
 * - Ultra crash-safe (all operations wrapped in try-catch)
 *
 * ## Why this class uses SharedPreferences (CLAUDE.md Rule 5 exception)
 *
 * Rule 5 says DataStore is the only app storage. This class is the one
 * explicit exception left (a second one used to exist for
 * `DataMigrationManager`'s version flag; the manager has since been
 * removed, leaving this file as the sole opt-out).
 *
 * The key constraint is that [shouldSendReport] is called *synchronously*
 * from the ACRA crash handler, which runs on a plain thread, not a
 * coroutine. DataStore's read/write API is `suspend`-only — there is no
 * sync alternative. Two ways to bridge the gap, both worse than just
 * keeping SharedPreferences:
 *
 *   1. Wrap each access in `runBlocking { dataStore.data.first() }`.
 *      That's the StrictMode bug we want to avoid in the first place,
 *      and on a crash hot path it would block the handler thread on
 *      disk I/O — exactly when we want to be fast.
 *
 *   2. Maintain an in-memory `ConcurrentMap<String, Long>` cache,
 *      hydrated once on init from DataStore, with fire-and-forget
 *      async write-through. That doubles the source of truth (cache
 *      + disk), opens race conditions on rapid successive crashes,
 *      and adds a meaningful chunk of new code for what amounts to
 *      ephemeral telemetry timestamps.
 *
 * The persisted data here is *not user state* — it's a 24-hour cooldown
 * table for crash-report deduplication. Losing it on an app update or
 * a crash mid-write is acceptable: worst case, one extra crash report
 * gets sent. The Rule 5 spirit is "user state lives in DataStore so
 * backup/restore covers it"; this data has no business in a backup.
 *
 * If you want to "clean this up" by migrating to DataStore, read this
 * KDoc and CLAUDE.md Rule 5 first. The decision is documented; the
 * sync-call constraint from ACRA is real.
 */
object CrashReportLimiter {

    private const val PREFS_NAME = "acra_report_limiter"
    private const val REPORT_COOLDOWN_MS = 24 * 60 * 60 * 1000L // 24 hours
    private const val CLEANUP_INTERVAL_MS = 7 * 24 * 60 * 60 * 1000L // 7 days
    private const val LAST_CLEANUP_KEY = "last_cleanup_timestamp"

    @Volatile
    private var prefs: SharedPreferences? = null
    private val lock = Any()

    /**
     * Initialize the limiter with application context. Should be called once
     * during app startup. Initialization runs asynchronously to avoid
     * StrictMode violations on the main thread.
     */
    fun init(context: Context) {
        try {
            // WICHTIG: Startet im Hintergrund. Kein Blockieren des Main Threads mehr!
            CoroutineScope(Dispatchers.IO).launch {
                synchronized(lock) {
                    if (prefs == null) {
                        try {
                            // Disk Read passiert jetzt hier im Hintergrund
                            prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                            performCleanupIfNeeded()
                        } catch (e: Exception) {
                            Timber.e(e, "Failed to load preferences in background")
                        }
                    }
                }
            }
        } catch (e: Throwable) {
            Timber.e(e, "Failed to launch init coroutine")
        }
    }

    /**
     * Check if a report should be sent based on cooldown period.
     *
     * @param exception The exception to check
     * @return true if report should be sent, false if within cooldown period
     */
    fun shouldSendReport(exception: Throwable): Boolean {
        return try {
            val preferences = prefs
            if (preferences == null) {
                Timber.w("CrashReportLimiter not initialized - allowing report")
                return true
            }

            synchronized(lock) {
                val reportKey = generateReportKey(exception)
                val lastSent = preferences.getLong(reportKey, 0L)
                val now = System.currentTimeMillis()

                val shouldSend = (now - lastSent) > REPORT_COOLDOWN_MS

                if (shouldSend) {
                    try {
                        preferences.edit {
                            putLong(reportKey, now)
                        }
                        Timber.d("Report allowed for: ${exception::class.simpleName}")
                    } catch (e: Throwable) {
                        Timber.e(e, "Failed to save report timestamp")
                    }
                } else {
                    val hoursRemaining = ((REPORT_COOLDOWN_MS - (now - lastSent)) / (60 * 60 * 1000)).toInt()
                    Timber.d("Report blocked (cooldown active): ${exception::class.simpleName} - $hoursRemaining hours remaining")
                }

                shouldSend
            }
        } catch (e: Throwable) {
            Timber.e(e, "Error in shouldSendReport - allowing report by default")
            true // Fail-open: allow report if limiter fails
        }
    }

    /**
     * Generate a unique key for an exception type.
     *
     * Exceptions implementing [CustomReportKey] supply their own dedup
     * identity (see that interface for why). Everything else is keyed by
     * class name and the first relevant stack trace element.
     */
    private fun generateReportKey(exception: Throwable): String {
        return try {
            if (exception is CustomReportKey) {
                "report_${exception.reportKey}"
            } else {
                val className = exception::class.simpleName ?: "UnknownException"

                // Find first stack trace element from app package
                val relevantTrace = exception.stackTrace
                    .firstOrNull { it.className.contains("com.github.reygnn.kolibri_launcher") }
                    ?: exception.stackTrace.firstOrNull()

                val location = if (relevantTrace != null) {
                    "${relevantTrace.className}.${relevantTrace.methodName}:${relevantTrace.lineNumber}"
                } else {
                    "unknown_location"
                }

                "report_${className}_${location.hashCode()}"
            }
        } catch (e: Throwable) {
            // Fallback to simple key if anything fails
            "report_${exception::class.simpleName}_${exception.message?.hashCode() ?: 0}"
        }
    }

    /**
     * Remove entries older than CLEANUP_INTERVAL to prevent unlimited growth.
     * Runs automatically when needed.
     */
    private fun performCleanupIfNeeded() {
        try {
            val preferences = prefs ?: return

            synchronized(lock) {
                val lastCleanup = preferences.getLong(LAST_CLEANUP_KEY, 0L)
                val now = System.currentTimeMillis()

                if (now - lastCleanup < CLEANUP_INTERVAL_MS) {
                    return // Cleanup not needed yet
                }

                Timber.d("Performing CrashReportLimiter cleanup...")

                val allEntries = preferences.all
                var removedCount = 0

                preferences.edit {
                    for ((key, value) in allEntries) {
                        if (key == LAST_CLEANUP_KEY) continue

                        if (value is Long) {
                            val age = now - value
                            if (age > CLEANUP_INTERVAL_MS) {
                                remove(key)
                                removedCount++
                            }
                        }
                    }
                    putLong(LAST_CLEANUP_KEY, now)
                }

                Timber.d("Cleanup complete - removed $removedCount old entries")
            }
        } catch (e: Throwable) {
            Timber.e(e, "Error during cleanup")
        }
    }

    /**
     * Reset all crash-report cooldown timestamps stored by this limiter.
     *
     * Wired into Settings → Developer Commands → "Reset ACRA cooldown
     * timer" so the maintainer can force the next crash to submit
     * immediately, even when a same-class crash was already reported
     * within the 24h dedup window. Safe to call in release builds — only
     * the deduplication state is cleared, no user data is touched.
     */
    fun resetAllLimits() {
        try {
            val preferences = prefs ?: return

            synchronized(lock) {
                preferences.edit { clear() }
                Timber.w("All report limits have been reset")
            }
        } catch (e: Throwable) {
            Timber.e(e, "Failed to reset limits")
        }
    }

}

/**
 * Opt-in hook for exceptions that must supply their own dedup identity to
 * [CrashReportLimiter].
 *
 * The default key ([CrashReportLimiter.generateReportKey]) is
 * `class-simple-name + first app-package stack frame`. That is wrong for
 * *synthetic* exceptions that share one class and one construction site:
 * the post-mortem ANR carrier is always `AnrException` thrown from the same
 * line, so every genuinely-distinct hang would hash to the same key and
 * collapse under a single 24h cooldown bucket — only the first ANR since the
 * last launch would ever leave the device.
 *
 * Implementors return a stable per-signature key instead, so distinct events
 * each report while identical repeats still dedup within the cooldown window.
 */
interface CustomReportKey {
    val reportKey: String
}