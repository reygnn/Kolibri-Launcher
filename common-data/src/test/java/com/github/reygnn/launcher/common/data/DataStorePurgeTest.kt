package com.github.reygnn.launcher.common.data

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.preferencesOf
import com.github.reygnn.launcher.core.testing.MainDispatcherRule
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import org.junit.Rule
import org.junit.Test
import java.io.IOException
import kotlin.test.assertFailsWith

/**
 * [safePurge] (2b-4c, F1): a purge failure is logged AND rethrown, so the factory reset can report
 * a partial failure; a cancellation passes unchanged; a working store is purged as before.
 */
class DataStorePurgeTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    @get:Rule
    val timberRule = TimberRule()

    private val key = booleanPreferencesKey("flag")

    /** A store whose edits throw [failure], or work when it is null. */
    private class Store(initial: Preferences, private val failure: Throwable? = null) : DataStore<Preferences> {
        private val state = MutableStateFlow(initial)
        override val data: Flow<Preferences> = state
        override suspend fun updateData(transform: suspend (t: Preferences) -> Preferences): Preferences {
            failure?.let { throw it }
            return transform(state.value).also { state.value = it }
        }
        val current get() = state.value
    }

    @Test
    fun a_working_store_is_purged() = runTest(mainDispatcherRule.testDispatcher) {
        val store = Store(preferencesOf(key to true))

        store.safePurge("test") { it.remove(key) }

        assertThat(store.current).isEqualTo(emptyPreferences())
    }

    @Test
    fun a_failing_store_is_rethrown_after_logging() = runTest(mainDispatcherRule.testDispatcher) {
        val store = Store(preferencesOf(key to true), IOException("disk full"))

        val error = assertFailsWith<IOException> { store.safePurge("test") { it.remove(key) } }

        assertThat(error).hasMessageThat().isEqualTo("disk full")
    }

    @Test
    fun a_cancellation_passes_unchanged() = runTest(mainDispatcherRule.testDispatcher) {
        val store = Store(emptyPreferences(), CancellationException("left"))

        assertFailsWith<CancellationException> { store.safePurge("test") { it.remove(key) } }
    }
}
