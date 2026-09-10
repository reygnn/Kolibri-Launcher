package com.github.reygnn.nyx_launcher.data.testing

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.emptyPreferences
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow

/**
 * In-memory [DataStore] of [Preferences] for tests — dispatcher-agnostic, so it
 * works under `runTest` and plain `runBlocking`. Backs `.data` with a StateFlow
 * and applies `updateData` transforms synchronously. `edit { }` routes through
 * `updateData`, so preference edits work unchanged.
 */
class FakeDataStore(
    initial: Preferences = emptyPreferences(),
) : DataStore<Preferences> {

    private val state = MutableStateFlow(initial)

    override val data: Flow<Preferences> = state

    override suspend fun updateData(
        transform: suspend (t: Preferences) -> Preferences,
    ): Preferences {
        val updated = transform(state.value)
        state.value = updated
        return updated
    }
}
