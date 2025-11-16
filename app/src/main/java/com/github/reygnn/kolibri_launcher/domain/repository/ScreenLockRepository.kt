package com.github.reygnn.kolibri_launcher.domain.repository

import com.github.reygnn.kolibri_launcher.domain.repository.Purgeable
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow

interface ScreenLockRepository : Purgeable {
    // Stellt den aktuellen Zustand reaktiv bereit
    val isLockingAvailableFlow: StateFlow<Boolean>

    // Flow für Sperranfragen
    val lockRequestFlow: Flow<Unit>

    // Flow für Benachrichtigungs-Anfragen
    val openNotificationsRequestFlow: Flow<Unit>

    // Meldet den Service-Status
    fun setServiceState(isAvailable: Boolean)

    // Löst Sperre aus
    suspend fun requestLock()

    // Löst das Öffnen der Benachrichtigungen aus
    suspend fun requestOpenNotifications()
}