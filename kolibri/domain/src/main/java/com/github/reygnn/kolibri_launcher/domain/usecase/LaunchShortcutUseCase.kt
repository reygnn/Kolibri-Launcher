package com.github.reygnn.kolibri_launcher.domain.usecase

import com.github.reygnn.kolibri_launcher.domain.model.LauncherShortcut
import com.github.reygnn.kolibri_launcher.domain.service.ShortcutLaunchException
import com.github.reygnn.kolibri_launcher.domain.service.ShortcutLauncherService
import javax.inject.Inject

/**
 * Use Case für das Starten von App-Shortcuts.
 *
 * Kapselt die gesamte Logik für Shortcut-Launches inklusive
 * Validierung und Error-Handling. Vollständig unit-testbar.
 *
 * @param launcherService Der Service für den eigentlichen Launch
 */
class LaunchShortcutUseCase @Inject constructor(
    private val launcherService: ShortcutLauncherService
) {

    /**
     * Ergebnis eines Shortcut-Launch-Versuchs.
     */
    sealed class Result {
        /** Shortcut wurde erfolgreich gestartet */
        object Success : Result()

        /** Shortcut-Launch ist fehlgeschlagen */
        data class Failure(val error: Error) : Result()
    }

    /**
     * Mögliche Fehler beim Shortcut-Launch.
     */
    sealed class Error {
        /** Shortcut war null oder ungültig */
        object ShortcutNull : Error()

        /** LauncherApps Service nicht verfügbar */
        object ServiceUnavailable : Error()

        /** Launch fehlgeschlagen (z.B. App nicht installiert) */
        data class LaunchFailed(val cause: Throwable) : Error()

        /** Unerwarteter Fehler */
        data class Unknown(val cause: Throwable) : Error()
    }

    /**
     * Führt den Shortcut-Launch aus.
     *
     * @param shortcut Der zu startende Shortcut (kann null sein)
     * @return Result.Success oder Result.Failure mit spezifischem Error
     */
    fun execute(shortcut: LauncherShortcut?): Result {
        // 1. Null-Check
        if (shortcut == null) {
            return Result.Failure(Error.ShortcutNull)
        }

        // 2. Service-Verfügbarkeit prüfen
        if (!launcherService.isAvailable()) {
            return Result.Failure(Error.ServiceUnavailable)
        }

        // 3. Launch versuchen
        return try {
            launcherService.startShortcut(shortcut)
            Result.Success
        } catch (e: ShortcutLaunchException) {
            Result.Failure(Error.LaunchFailed(e))
        } catch (e: Throwable) {
            Result.Failure(Error.Unknown(e))
        }
    }
}