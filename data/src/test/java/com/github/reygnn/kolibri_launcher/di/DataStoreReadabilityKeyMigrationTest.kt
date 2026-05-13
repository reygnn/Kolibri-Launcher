package com.github.reygnn.kolibri_launcher.di

import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.mutablePreferencesOf
import androidx.datastore.preferences.core.stringPreferencesKey
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.test.runTest
import org.junit.Test

/**
 * Pins the one-shot migration that removes the orphan
 * `text_readability_mode` key from DataStore. Direct contract test on
 * the [DataMigration] returned by [removeStaleReadabilityModeKey] —
 * no DataStore-on-disk needed.
 */
class DataStoreReadabilityKeyMigrationTest {

    private val staleKey = stringPreferencesKey("text_readability_mode")
    private val sentinel = booleanPreferencesKey("kolibri_test_sentinel")

    @Test
    fun `shouldMigrate is true when stale key is present`() = runTest {
        val migration = removeStaleReadabilityModeKey()
        val data = mutablePreferencesOf(staleKey to "smart_contrast")

        assertThat(migration.shouldMigrate(data)).isTrue()
    }

    @Test
    fun `shouldMigrate is false when stale key is absent`() = runTest {
        val migration = removeStaleReadabilityModeKey()
        val data = mutablePreferencesOf(sentinel to true)

        assertThat(migration.shouldMigrate(data)).isFalse()
    }

    @Test
    fun `migrate removes the stale key and leaves other keys untouched`() = runTest {
        val migration = removeStaleReadabilityModeKey()
        val data = mutablePreferencesOf(
            staleKey to "smart_contrast",
            sentinel to true,
        )

        val migrated = migration.migrate(data)

        assertThat(migrated.contains(staleKey)).isFalse()
        assertThat(migrated[sentinel]).isTrue()
    }
}
