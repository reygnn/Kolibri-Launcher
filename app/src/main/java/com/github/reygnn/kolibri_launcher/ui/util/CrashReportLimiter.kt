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
     * Initialize the limiter with application context.
     * Should be called once during app startup.
     */
    /**
     * Async initialization to avoid StrictMode violations on startup.
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
                            Timber.Forest.e(e, "Failed to load preferences in background")
                        }
                    }
                }
            }
        } catch (e: Throwable) {
            Timber.Forest.e(e, "Failed to launch init coroutine")
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
                Timber.Forest.w("CrashReportLimiter not initialized - allowing report")
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
                        Timber.Forest.d("Report allowed for: ${exception::class.simpleName}")
                    } catch (e: Throwable) {
                        Timber.Forest.e(e, "Failed to save report timestamp")
                    }
                } else {
                    val hoursRemaining = ((REPORT_COOLDOWN_MS - (now - lastSent)) / (60 * 60 * 1000)).toInt()
                    Timber.Forest.d("Report blocked (cooldown active): ${exception::class.simpleName} - $hoursRemaining hours remaining")
                }

                shouldSend
            }
        } catch (e: Throwable) {
            Timber.Forest.e(e, "Error in shouldSendReport - allowing report by default")
            true // Fail-open: allow report if limiter fails
        }
    }

    /**
     * Generate a unique key for an exception type.
     * Uses exception class name and first relevant stack trace element.
     */
    private fun generateReportKey(exception: Throwable): String {
        return try {
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

                Timber.Forest.d("Performing CrashReportLimiter cleanup...")

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

                Timber.Forest.d("Cleanup complete - removed $removedCount old entries")
            }
        } catch (e: Throwable) {
            Timber.Forest.e(e, "Error during cleanup")
        }
    }

    /**
     * Reset all limits (useful for testing or debugging).
     * Should only be called from debug builds.
     */
    fun resetAllLimits() {
        try {
            val preferences = prefs ?: return

            synchronized(lock) {
                preferences.edit { clear() }
                Timber.Forest.w("All report limits have been reset")
            }
        } catch (e: Throwable) {
            Timber.Forest.e(e, "Failed to reset limits")
        }
    }

    /**
     * Get statistics about blocked reports (for debugging).
     */
    fun getStatistics(): String {
        return try {
            val preferences = prefs ?: return "Limiter not initialized"

            synchronized(lock) {
                val allEntries = preferences.all.filter { it.key != LAST_CLEANUP_KEY }
                val now = System.currentTimeMillis()
                val activeBlocks = allEntries.count { (_, value) ->
                    value is Long && (now - value) < REPORT_COOLDOWN_MS
                }

                "Total tracked: ${allEntries.size}, Active blocks: $activeBlocks"
            }
        } catch (e: Throwable) {
            "Error getting statistics"
        }
    }
}