package com.github.reygnn.kolibri_launcher

import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Ein einfacher, App-weiter Event-Bus (Signal), der von Hilt als Singleton verwaltet wird.
 * Er dient dazu, ein Signal vom Hilt-freien PackageUpdateReceiver an das
 * Hilt-verwaltete HomeViewModel zu senden, ohne dass diese sich direkt kennen müssen.
 */
@Singleton
open class AppUpdateSignal @Inject constructor() : Purgeable {

    // Ein SharedFlow ist perfekt für "Feuern und Vergessen"-Events.
    private val _events = MutableSharedFlow<Unit>()
    val events = _events.asSharedFlow()

    open suspend fun sendUpdateSignal() {
        _events.emit(Unit)
    }

    // Der Fake im Test-Code wird diese dann mit der echten Reset-Logik überschreiben.
    override fun purgeRepository() { }
}