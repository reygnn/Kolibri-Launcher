package com.github.reygnn.kolibri_launcher.core

import com.github.reygnn.kolibri_launcher.BuildConfig
import timber.log.Timber
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.system.exitProcess

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
        Timber.tag(SILENT_LOG_TAG).e(throwable, message)
        crashInDebug(throwable, message)
    }

    fun silentError(message: String) {
        Timber.tag(SILENT_LOG_TAG).e(message)
        crashInDebug(null, message)
    }

    fun silentError(throwable: Throwable) {
        Timber.tag(SILENT_LOG_TAG).e(throwable)
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

    // ============================================================================
    // SILENT DEATH — kontrolliertes Sterben in Lügen-Pfaden
    // ============================================================================
    //
    // Ergänzung zu [silentError] für Pfade, in denen ein silent-fail nicht
    // tolerierbar ist, weil die App danach in einem Zustand weiterlaufen würde,
    // der dem User Korrektheit vortäuscht (halb-migrierte DataStore, falsch
    // angenommener "First Launch", Activity ohne ViewModel, Endlos-Restart-
    // Schleife einer Home-Activity nach finish()).
    //
    // Verhalten:
    // - preventCrashForTesting==true: wirft RuntimeException, damit Tests
    //   den Pfad assert-en können statt die JVM zu beenden.
    // - sonst (DEBUG wie RELEASE): loggt FATAL, gibt dem ACRA-Sender ~100 ms
    //   zum Flushen, dann exitProcess(1). Android startet HOME ggf. neu —
    //   aber sauber, nicht in einem zombiehaft halb-toten State.
    //
    // Warum auch DEBUG exit statt throw: die Aufrufer von silentDeath sind
    // typischerweise von paranoiden äußeren catch(Throwable)-Blöcken umgeben
    // (KolibriLauncherApp, DataMigrationManager). Ein DEBUG-Throw wird dort
    // geschluckt, der Senior sieht nichts Fatales, und die App läuft mit
    // demselben lügenden State weiter, den silentDeath verhindern soll.
    // exitProcess(1) ist uncatchable und damit der einzige verlässliche Tod.
    // Die Symmetrie zu silentError (das in DEBUG wirft) geht damit verloren —
    // bewusst, weil silentDeath semantisch ein anderes Tier ist (Tod statt
    // Fail-Safe). ACRA liefert in beiden Build-Typen den FATAL-Report.
    //
    // Return-Typ Nothing: der Compiler weiß, dass nach silentDeath kein
    // Code mehr ausgeführt wird — keine if-else Verrenkungen am Aufrufort.

    fun silentDeath(message: String): Nothing {
        die(cause = null, message = message)
    }

    fun silentDeath(throwable: Throwable, message: String): Nothing {
        die(cause = throwable, message = message)
    }

    private fun die(cause: Throwable?, message: String): Nothing {
        // Loggen muss vor allem anderen passieren — wenn das Logging selbst
        // failt, bringen weder ACRA-Flush noch exit den User-relevanten
        // Hinweis ins ACRA-Backend.
        try {
            if (cause != null) {
                Timber.tag(SILENT_LOG_TAG).e(cause, "FATAL: $message")
            } else {
                Timber.tag(SILENT_LOG_TAG).e("FATAL: $message")
            }
        } catch (ignored: Throwable) {
            // Last-resort Log via Android-Log; kein Throw nach oben.
            try {
                android.util.Log.e(SILENT_LOG_TAG, "FATAL: $message", cause)
            } catch (ignored2: Throwable) {
                // Wenn auch das failt, gibt's nichts mehr zu retten.
            }
        }

        // Nur Tests werfen — sie wollen den Pfad assert-en, nicht die JVM
        // beenden. Alle echten Builds (DEBUG wie RELEASE) sterben unten via
        // exitProcess, weil ein Throw von äußeren catch(Throwable)-Blöcken
        // geschluckt würde und silentDeath dann genau das nicht erreicht,
        // wozu es da ist: den lügenden State zu beenden.
        if (preventCrashForTesting.get()) {
            throw RuntimeException("SILENT_DEATH: $message", cause)
        }

        // Dem ACRA-Sender (Worker-Thread) eine kleine Chance geben,
        // den FATAL-Report rauszupusten, bevor der Process stirbt. 100 ms
        // ist ein Kompromiss — kürzer und der Sender steht noch im Queue;
        // länger und der User sieht eine fühlbare Pause.
        try {
            Thread.sleep(100)
        } catch (ignored: InterruptedException) {
            // Best-effort flush — bei Interrupt einfach weiter zum Exit.
        }

        exitProcess(1)
    }
}