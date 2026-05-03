package com.github.reygnn.kolibri_launcher.domain.service

import android.content.pm.ShortcutInfo

/**
 * Implementierung von ShortcutLauncherService.
 *
 * Wrapped den Android LauncherApps System Service.
 *
 * ⚠️ NICHT DIREKT VERWENDEN!
 * Immer über [LaunchShortcutUseCase] gehen - der UseCase bietet:
 * - Null-Safety
 * - Vollständiges Error-Handling
 * - Typsichere Result-Objekte
 *
 * Direkter Zugriff wirft unchecked [ShortcutLaunchException].
 */
interface ShortcutLauncherService {

    /**
     * Startet einen App-Shortcut.
     *
     * @param shortcut Der zu startende Shortcut
     * @throws ShortcutLaunchException wenn der Launch fehlschlägt
     */
    fun startShortcut(shortcut: ShortcutInfo)

    /**
     * Prüft ob der Service verfügbar ist.
     */
    fun isAvailable(): Boolean
}

/**
 * Exception für fehlgeschlagene Shortcut-Launches.
 *
 * RuntimeException (unchecked) weil:
 * 1. Kotlin hat keine checked Exceptions
 * 2. Mockito kann sonst nicht doThrow() verwenden
 * 3. Der Caller soll nicht gezwungen werden zu catchen
 */
class ShortcutLaunchException(
    message: String,
    cause: Throwable? = null
) : RuntimeException(message, cause)