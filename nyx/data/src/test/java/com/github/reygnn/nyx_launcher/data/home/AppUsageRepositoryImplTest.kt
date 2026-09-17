package com.github.reygnn.nyx_launcher.data.home

import androidx.datastore.preferences.core.preferencesOf
import androidx.datastore.preferences.core.stringSetPreferencesKey
import com.github.reygnn.launcher.core.AppConstants
import com.github.reygnn.nyx_launcher.data.testing.FakeDataStore
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Test

/**
 * Impl-specific behaviour the shared [com.github.reygnn.nyx_launcher.home.repository.AppUsageRepositoryContract]
 * does not pin (it never records past the cap): a package's stored timestamps are capped at
 * [AppConstants.MAX_TIMESTAMPS_PER_APP], newest kept. Pinned here so a dropped `.take(...)`
 * regression turns this red (mirrors kolibri's AppUsageRepositoryImplTest cap coverage).
 */
class AppUsageRepositoryImplTest {

    @Test
    fun recording_caps_timestamps_at_the_max_keeping_the_newest() = runTest {
        val now = System.currentTimeMillis()
        val max = AppConstants.MAX_TIMESTAMPS_PER_APP
        // Seed exactly MAX distinct, valid timestamps (1 minute apart), oldest last.
        val seeded = (1..max).map { now - it * 60_000L }
        val oldest = seeded.last()
        val store = FakeDataStore(
            preferencesOf(
                stringSetPreferencesKey(AppConstants.KEY_USAGE_PREFIX + "com.a") to seeded.map { it.toString() }.toSet(),
            ),
        )
        val repo = AppUsageRepositoryImpl(store)

        // One more launch → MAX + 1 distinct → must cap back to MAX, dropping the oldest.
        repo.recordPackageLaunch("com.a")

        val timestamps = repo.usageSnapshotFlow.first().getValue("com.a")
        assertThat(timestamps).hasSize(max)
        assertThat(timestamps).doesNotContain(oldest)
    }
}
