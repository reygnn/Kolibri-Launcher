package com.github.reygnn.kolibri_launcher.core

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first

/**
 * Reads the first value of this flow, returning [fallback] if the read fails.
 *
 * `CancellationException` is rethrown so coroutine cancellation keeps
 * propagating; any other failure (e.g. a DataStore `IOException` from
 * [first]) collapses to [fallback]. This is the shared form of the
 * try/first/catch idiom that the one-shot `Get*SettingUseCase` reads
 * repeated verbatim.
 */
suspend fun <T> Flow<T>.firstOrDefault(fallback: T): T {
    return try {
        first()
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        fallback
    }
}
