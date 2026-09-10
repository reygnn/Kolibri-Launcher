package com.github.reygnn.kolibri_launcher.ui.util

import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow

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