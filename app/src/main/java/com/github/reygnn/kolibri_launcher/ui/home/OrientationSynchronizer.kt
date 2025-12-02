package com.github.reygnn.kolibri_launcher.ui.home

class OrientationSynchronizer(
    private val getSystemOrientation: () -> Int
) {

    fun check(currentInternalState: Int): SyncResult {
        val realSystemOrientation = getSystemOrientation()

        return if (currentInternalState != realSystemOrientation) {
            // Mismatch gefunden! Wir geben zurück, was zu tun ist.
            SyncResult.CorrectionNeeded(
                oldOrientation = currentInternalState,
                newOrientation = realSystemOrientation
            )
        } else {
            // Alles synchron.
            SyncResult.UpToDate
        }
    }
}