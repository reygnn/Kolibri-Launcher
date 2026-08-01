package com.github.reygnn.kolibri_launcher.core

import com.github.reygnn.kolibri_launcher.domain.repository.Purgeable
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import javax.inject.Inject
import javax.inject.Singleton

/**
 * App-wide event bus for package-lifecycle events, managed by Hilt as a
 * singleton. Bridges the non-Hilt PackageUpdateReceiver to the Hilt-managed
 * consumers without making them know about each other.
 *
 * The stream carries a typed [PackageEvent] (which package was added/removed),
 * so consumers can act on a specific target rather than re-scanning the whole
 * app list — see `RECONCILE_SPEC.md`.
 */
@Singleton
open class AppUpdateSignal @Inject constructor() : Purgeable {

    private val _events = MutableSharedFlow<PackageEvent>()
    val events = _events.asSharedFlow()

    open suspend fun send(event: PackageEvent) {
        _events.emit(event)
    }

    override suspend fun purgeRepository() { }
}
