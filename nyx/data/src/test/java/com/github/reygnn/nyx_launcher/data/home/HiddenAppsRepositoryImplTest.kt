package com.github.reygnn.nyx_launcher.data.home

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.preferencesOf
import androidx.datastore.preferences.core.stringPreferencesKey
import com.github.reygnn.nyx_launcher.data.testing.FakeDataStore
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.test.runTest
import org.junit.Test
import java.io.IOException

/**
 * Impl-specific behaviour not covered by the shared contract (which always seeds a valid
 * blob): the read path never crashes on absent OR corrupt state — a missing/undecodable
 * blob reads as the empty set. The two fallbacks are independent (a missing key and a
 * present-but-undecodable blob), so both are pinned — mirrors DrawerFoldersRepositoryImplTest.
 */
class HiddenAppsRepositoryImplTest {

    @Test
    fun missing_blob_reads_as_empty() = runTest {
        val repo = HiddenAppsRepositoryImpl(FakeDataStore(), HiddenAppsSerializer())
        assertThat(repo.hidden().first()).isEmpty()
    }

    @Test
    fun undecodable_blob_reads_as_empty() = runTest {
        // Seed a corrupt value under the impl's own key — mirrors HiddenAppsRepositoryImpl.KEY,
        // keep in sync if that key ever changes. Exercises the `deserialize(raw) ?: emptySet()`
        // branch, distinct from the missing-key branch above, so a future `deserialize(raw)!!`
        // regression (which would crash the whole drawer read Flow on a corrupt blob) turns
        // this red instead of shipping.
        val store = FakeDataStore(
            preferencesOf(stringPreferencesKey("hidden_apps_v1") to "{ not valid json"),
        )
        val repo = HiddenAppsRepositoryImpl(store, HiddenAppsSerializer())
        assertThat(repo.hidden().first()).isEmpty()
    }

    @Test
    fun update_propagates_read_failure_and_does_not_write() = runTest {
        // The RMW read is fail-CLOSED: an IOException propagates and the write never runs, so a
        // transient store failure can't clobber the real set. (A fail-open revert would read
        // emptySet, not throw, and proceed to write.)
        var wrote = false
        val throwing = object : DataStore<Preferences> {
            override val data: Flow<Preferences> = flow { throw IOException("boom") }
            override suspend fun updateData(transform: suspend (Preferences) -> Preferences): Preferences {
                wrote = true
                return transform(emptyPreferences())
            }
        }
        val repo = HiddenAppsRepositoryImpl(throwing, HiddenAppsSerializer())

        var thrown = false
        try {
            repo.update { it }
        } catch (e: IOException) {
            thrown = true
        }

        assertThat(thrown).isTrue()
        assertThat(wrote).isFalse()
    }
}
