package com.github.reygnn.kolibri_launcher

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow

interface ScreenLockRepository : Purgeable {
    // Bleibt gleich: Stellt den aktuellen Zustand reaktiv bereit
    val isLockingAvailableFlow: StateFlow<Boolean>

    // NEU: Ein Flow, auf den der Service lauschen kann, um zu wissen, wann er sperren soll
    val lockRequestFlow: Flow<Unit>

    // NEU: Eine Funktion, mit der der Service seinen Zustand melden kann
    fun setServiceState(isAvailable: Boolean)

    // NEU: Die Funktion zum Auslösen des Sperrvorgangs
    suspend fun requestLock()
}