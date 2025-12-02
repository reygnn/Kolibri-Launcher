package com.github.reygnn.kolibri_launcher.ui.home

/**
 * Responsible for synchronizing the app's internal orientation state with the actual
 * system configuration.
 *
 * ### The Problem ("Pocket Rotation" Scenario)
 * When a device is rotated while the app is paused (e.g., screen locked, app in background),
 * the standard Android `onConfigurationChanged` callback may not fire or be processed.
 * Consequently, when the app resumes, it might still believe it is in the previous orientation
 * (e.g., Landscape) while the device is actually in Portrait.
 *
 * ### The Solution
 * This class performs a check (usually in `onResume`) to verify if the internal state
 * matches the current system source of truth. If a mismatch is detected, it triggers
 * the necessary update and cleanup callbacks.
 *
 * ### Architecture
 * Designed as a **Pure Kotlin** class completely decoupled from the Android Framework.
 * Instead of passing `Context` or `Resources`, we pass functional interfaces (lambdas).
 * This allows the logic to be heavily unit-tested without Android mocks or instrumentation.
 *
 * @param getSystemOrientation Provider that returns the current real-time system orientation.
 * @param onUpdateNeeded Callback invoked ONLY if a mismatch is detected.
 * @param onCleanupNeeded Callback invoked to clear caches (e.g., layout calculators) after an update.
 */

/**
 * Verantwortlich für den Abgleich zwischen internem State und System-State.
 * Entkoppelt die Logik vom Android Framework für Unit-Tests.
 */
class OrientationSynchronizer(
    private val getSystemOrientation: () -> Int,
    private val onUpdateNeeded: (Int) -> Unit,
    private val onCleanupNeeded: () -> Unit
) {

    /**
     * Prüft, ob der interne State mit dem System übereinstimmt.
     * Wenn nicht, werden Update und Cleanup getriggert.
     *
     * @param currentInternalState Der aktuelle Wert aus dem StateFlow/Variable
     * @return true, wenn ein Fix durchgeführt wurde, sonst false
     */
    fun verifyAndSync(currentInternalState: Int): Boolean {
        val realSystemOrientation = getSystemOrientation()

        if (currentInternalState != realSystemOrientation) {
            // Logik: State ist asynchron -> Wir müssen eingreifen
            onUpdateNeeded(realSystemOrientation)
            onCleanupNeeded()
            return true
        }

        return false
    }
}