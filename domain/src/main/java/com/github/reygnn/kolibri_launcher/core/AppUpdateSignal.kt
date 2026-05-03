package com.github.reygnn.kolibri_launcher.core

import com.github.reygnn.kolibri_launcher.domain.repository.Purgeable
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import javax.inject.Inject
import javax.inject.Singleton

/**
 * App-wide event bus for the "package list changed" signal, managed by Hilt
 * as a singleton. Bridges the non-Hilt PackageUpdateReceiver to the
 * Hilt-managed HomeViewModel without making them know about each other.
 */
@Singleton
open class AppUpdateSignal @Inject constructor() : Purgeable {

    private val _events = MutableSharedFlow<Unit>()
    val events = _events.asSharedFlow()

    open suspend fun sendUpdateSignal() {
        _events.emit(Unit)
    }

    override suspend fun purgeRepository() { }
}
