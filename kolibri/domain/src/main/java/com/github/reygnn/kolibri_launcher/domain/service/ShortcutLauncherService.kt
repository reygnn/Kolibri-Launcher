package com.github.reygnn.kolibri_launcher.domain.service

import com.github.reygnn.kolibri_launcher.domain.model.LauncherShortcut

/**
 * Contract for the shortcut-launcher service.
 *
 * Wraps the Android LauncherApps system service.
 *
 * ⚠️ DO NOT USE DIRECTLY!
 * Always go through
 * [com.github.reygnn.kolibri_launcher.domain.usecase.LaunchShortcutUseCase]
 * — the UseCase provides:
 * - null safety
 * - complete error handling
 * - type-safe result objects
 *
 * Direct access throws an unchecked [ShortcutLaunchException].
 */
interface ShortcutLauncherService {

    /**
     * Launches an app shortcut.
     *
     * @param shortcut The shortcut to launch.
     * @throws ShortcutLaunchException if the launch fails.
     */
    fun startShortcut(shortcut: LauncherShortcut)

    /**
     * Returns whether the service is available.
     */
    fun isAvailable(): Boolean
}

/**
 * Exception raised when a shortcut launch fails.
 *
 * RuntimeException (unchecked) because:
 * 1. Kotlin has no checked exceptions.
 * 2. Test stubs work cleanest with unchecked exceptions.
 * 3. Callers should not be forced to catch.
 */
class ShortcutLaunchException(
    message: String,
    cause: Throwable? = null
) : RuntimeException(message, cause)
