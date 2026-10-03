package com.github.reygnn.launcher.feature.wallpaper

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.emptyPreferences
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flow

/**
 * The module's in-memory preferences store for unit tests (3a-4/3a-5): the apps' `FakeDataStore`s
 * live in their own test fixtures, which `:feature-wallpaper` cannot use. [readFailure] /
 * [writeFailure] make reads or writes fail.
 */
internal class InMemoryPreferencesStore(initial: Preferences = emptyPreferences()) : DataStore<Preferences> {
    val state = MutableStateFlow(initial)
    var readFailure: Throwable? = null
    var writeFailure: Throwable? = null

    override val data: Flow<Preferences> = flow {
        readFailure?.let { throw it }
        state.collect { emit(it) }
    }

    override suspend fun updateData(transform: suspend (t: Preferences) -> Preferences): Preferences {
        writeFailure?.let { throw it }
        return transform(state.value).also { state.value = it }
    }
}
