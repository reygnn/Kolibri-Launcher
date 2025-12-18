package com.github.reygnn.kolibri_launcher.core

import com.github.reygnn.kolibri_launcher.BuildConfig
import timber.log.Timber
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Wrapper für Timber mit Build-Type-abhängigem Verhalten.
 *
 * ENTWICKLUNG (DEBUG): Fehler werfen Exception → Sofortiger Crash → Bug sofort sichtbar
 * PRODUKTION (RELEASE): Fehler nur loggen → App läuft weiter → User merkt nichts
 *
 * Das löst das "Fail Fast vs. Fail Safe" Dilemma:
 * - Während der Entwicklung soll der Launcher LAUT crashen
 * - In Produktion soll der Launcher STILL weiterlaufen
 */
object TimberWrapper {

    const val SILENT_LOG_TAG = "SILENT_ERROR"

    // Ein Schalter für Tests. Standardmässig false (aus).
    // AtomicBoolean für Thread-Safety, falls Tests parallel laufen.
    // val reicht, da AtomicBoolean intern mutable ist.
    val preventCrashForTesting = AtomicBoolean(false)

    /**
     * Loggt einen Fehler, der nur im Logcat erscheinen soll.
     * In DEBUG-Builds wird zusätzlich eine Exception geworfen für sofortige Sichtbarkeit.
     */
    fun silentError(throwable: Throwable, message: String) {
        Timber.Forest.tag(SILENT_LOG_TAG).e(throwable, message)
        crashInDebug(throwable, message)
    }

    fun silentError(message: String) {
        Timber.Forest.tag(SILENT_LOG_TAG).e(message)
        crashInDebug(null, message)
    }

    fun silentError(throwable: Throwable) {
        Timber.Forest.tag(SILENT_LOG_TAG).e(throwable)
        crashInDebug(throwable, throwable.message ?: "Unknown error")
    }

    /**
     * In Debug-Builds: Exception werfen für sofortige Crash-Sichtbarkeit.
     * In Release-Builds: Nichts tun (Fehler wurde bereits geloggt).
     */
    private fun crashInDebug(cause: Throwable?, message: String) {
        // Wenn der Test-Modus aktiv ist, brich hier ab -> Kein Crash!
        if (preventCrashForTesting.get()) {
            return
        }

        if (BuildConfig.DEBUG) {
            throw RuntimeException("SILENT_ERROR caught: $message", cause)
        }
    }
}