package com.github.reygnn.nyx_launcher.home.wallpaper

import com.github.reygnn.launcher.core.TimberWrapper
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/**
 * Launches [block] with a safety net: cancellation propagates, any other throwable
 * is reported via [TimberWrapper.silentError] instead of escaping to the default
 * uncaught handler (which would crash the launcher). Use for fire-and-forget
 * DataStore writes from UI callbacks that have no CoroutineExceptionHandler of
 * their own (Nyx has no BaseViewModel launchSafe).
 */
inline fun CoroutineScope.launchSafe(
    errorMessage: String,
    crossinline block: suspend () -> Unit,
): Job = launch {
    try {
        block()
    } catch (e: CancellationException) {
        throw e
    } catch (e: Throwable) {
        TimberWrapper.silentError(e, errorMessage)
    }
}
