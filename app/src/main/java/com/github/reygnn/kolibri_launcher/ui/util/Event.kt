package com.github.reygnn.kolibri_launcher.ui.util

import java.util.concurrent.atomic.AtomicBoolean

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