package com.github.reygnn.kolibri_launcher

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.emptyPreferences
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.IOException

class FakeDataStore : DataStore<Preferences> {

    private val flow = MutableStateFlow(emptyPreferences())
    private val mutex = Mutex()

    private var shouldFailEdit = false
    private var shouldFailRead = false
    private var shouldCancel = false

    var updateDataCallCount = 0
        private set

    fun setInitialData(preferences: Preferences) {
        flow.value = preferences
    }

    fun makeEditFail() {
        shouldFailEdit = true
    }

    fun makeReadFail() {
        shouldFailRead = true
    }

    fun makeCancellable() {
        shouldCancel = true
    }

    fun resetErrorFlags() {
        shouldFailEdit = false
        shouldFailRead = false
        shouldCancel = false
    }

    override val data: Flow<Preferences>
        get() = if (shouldFailRead) {
            flow {
                throw IOException("FakeDataStore: Simulated read failure")
            }
        } else {
            flow
        }

    override suspend fun updateData(transform: suspend (t: Preferences) -> Preferences): Preferences {
        updateDataCallCount++

        return mutex.withLock {
            // Check AFTER entering the lock to ensure thread-safe flag checking
            when {
                shouldCancel -> throw kotlinx.coroutines.CancellationException("FakeDataStore: Simulated cancellation")
                shouldFailEdit -> throw IOException("FakeDataStore: Simulated edit failure")
                else -> {
                    val currentPreferences = flow.value
                    val newPreferences = transform(currentPreferences)
                    flow.value = newPreferences
                    newPreferences
                }
            }
        }
    }

    fun reset() {
        flow.value = emptyPreferences()
        resetErrorFlags()
        updateDataCallCount = 0
    }
}