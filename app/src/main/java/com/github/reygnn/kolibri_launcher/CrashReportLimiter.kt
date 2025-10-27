/*
 * Copyright (C) 2025 reygnn (Ulrich Kaufmann)
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */

package com.github.reygnn.kolibri_launcher

import android.content.Context
import android.content.SharedPreferences
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
    fun init(context: Context) {
        try {
            synchronized(lock) {
                if (prefs == null) {
                    prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                    performCleanupIfNeeded()
                }
            }
        } catch (e: Throwable) {
            Timber.e(e, "Failed to initialize CrashReportLimiter")
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
                        preferences.edit()
                            .putLong(reportKey, now)
                            .apply()
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
     * Uses exception class name and first relevant stack trace element.
     */
    private fun generateReportKey(exception: Throwable): String {
        return try {
            val className = exception::class.simpleName ?: "UnknownException"

            // Find first stack trace element from app package
            val relevantTrace = exception.stackTrace
                ?.firstOrNull { it.className.contains("com.github.reygnn.kolibri_launcher") }
                ?: exception.stackTrace?.firstOrNull()

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

                Timber.d("Performing CrashReportLimiter cleanup...")

                val editor = preferences.edit()
                val allEntries = preferences.all
                var removedCount = 0

                for ((key, value) in allEntries) {
                    if (key == LAST_CLEANUP_KEY) continue

                    if (value is Long) {
                        val age = now - value
                        if (age > CLEANUP_INTERVAL_MS) {
                            editor.remove(key)
                            removedCount++
                        }
                    }
                }

                editor.putLong(LAST_CLEANUP_KEY, now)
                editor.apply()

                Timber.d("Cleanup complete - removed $removedCount old entries")
            }
        } catch (e: Throwable) {
            Timber.e(e, "Error during cleanup")
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
                preferences.edit().clear().apply()
                Timber.w("All report limits have been reset")
            }
        } catch (e: Throwable) {
            Timber.e(e, "Failed to reset limits")
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