/*
 * Copyright (C) 2025 reygnn (Ulrich Kaufmann)
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */

package com.github.reygnn.kolibri_launcher.ui.main.delegate

import com.github.reygnn.kolibri_launcher.core.TimberWrapper
import com.github.reygnn.kolibri_launcher.ui.base.UiEvent
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/**
 * Shared infrastructure passed to every delegate.
 *
 * Provides coroutine scope (viewModelScope), event sending,
 * and a safe-launch helper mirroring BaseViewModel.launchSafe().
 */
class DelegateScope(
    val coroutineScope: CoroutineScope,
    val mainDispatcher: CoroutineDispatcher,
    private val eventSender: suspend (UiEvent) -> Unit
) {
    /**
     * Sends a UiEvent to the shared channel (same as BaseViewModel.sendEvent).
     */
    suspend fun sendEvent(event: UiEvent) {
        eventSender(event)
    }

    /**
     * Safe coroutine launch mirroring BaseViewModel.launchSafe().
     * Catches all Throwable except CancellationException.
     */
    fun launchSafe(
        errorMessage: String = "Unexpected error in delegate",
        block: suspend CoroutineScope.() -> Unit
    ) {
        coroutineScope.launch(mainDispatcher) {
            try {
                block()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                TimberWrapper.silentError(e, errorMessage)
            }
        }
    }
}