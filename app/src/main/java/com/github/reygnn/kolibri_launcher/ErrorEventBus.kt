package com.github.reygnn.kolibri_launcher

import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Ein einfacher, anwendungsweiter Event-Bus, der auf einem SharedFlow basiert.
 * Er wird verwendet, um Timber.e-Fehlermeldungen von überall in der App
 * an die aktuell sichtbare Activity zu senden, um sie als Toast anzuzeigen.
 *
 * Mit dem optionalen 'tag' können Fehler als "silent" markiert werden,
 * sodass sie nur im Logcat erscheinen, aber keinen Toast auslösen.
 */

data class ErrorData(
    val message: String,
    val tag: String? = null
)

data class Event<out T>(private val content: T) {
    private val hasBeenHandled = AtomicBoolean(false)

    fun getContentIfNotHandled(): T? {
        return if (hasBeenHandled.getAndSet(true)) {
            null
        } else {
            content
        }
    }
}

object ErrorEventBus {
    private val _events = MutableSharedFlow<Event<ErrorData>>(
        replay = 5,
        extraBufferCapacity = 10
    )
    val events = _events.asSharedFlow()

    fun post(message: String, tag: String? = null) {
        _events.tryEmit(Event(ErrorData(message, tag)))
    }
}