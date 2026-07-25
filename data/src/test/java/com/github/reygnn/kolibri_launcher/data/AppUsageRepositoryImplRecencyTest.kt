package com.github.reygnn.kolibri_launcher.data

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringSetPreferencesKey
import com.github.reygnn.kolibri_launcher.core.AppConstants
import com.github.reygnn.kolibri_launcher.fakes.FakeDataStore
import com.github.reygnn.kolibri_launcher.rule.MainDispatcherRule
import com.github.reygnn.kolibri_launcher.rule.TimberRule
import io.mockk.mockk
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Rule
import org.junit.Test

/**
 * Impl-only test for `getRecentlyLaunchedPackages`' ORDERING, which the
 * contract deliberately leaves out: `recordPackageLaunch` stamps
 * `System.currentTimeMillis()`, so rapid records tie and the order is
 * non-deterministic through the record path. Here we bypass record and seed
 * known, still-valid timestamps straight into the DataStore, pinning the
 * `last-launch = max(timestamps)` + newest-first sort deterministically — so
 * a flipped comparator (`sortedBy` / `minOrNull`) would fail loudly.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class AppUsageRepositoryImplRecencyTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    @get:Rule
    val timberRule = TimberRule()

    private lateinit var fakeDataStore: FakeDataStore
    private lateinit var repo: AppUsageRepositoryImpl

    @Before
    fun setup() {
        fakeDataStore = FakeDataStore()
        repo = AppUsageRepositoryImpl(fakeDataStore, mockk<Context>(relaxed = true))
    }

    private suspend fun seed(pkg: String, vararg timestamps: Long) {
        fakeDataStore.edit { prefs ->
            prefs[stringSetPreferencesKey(AppConstants.KEY_USAGE_PREFIX + pkg)] =
                timestamps.map { it.toString() }.toSet()
        }
    }

    @Test
    fun `orders packages by most recent launch, newest first`() = runTest(mainDispatcherRule.testDispatcher) {
        val now = System.currentTimeMillis()
        seed("pkg.a", now - 3_000)
        seed("pkg.b", now - 1_000) // most recent
        seed("pkg.c", now - 2_000)

        assertEquals(listOf("pkg.b", "pkg.c", "pkg.a"), repo.getRecentlyLaunchedPackages(10))
    }

    @Test
    fun `ranks a package by its newest timestamp, not its oldest`() = runTest(mainDispatcherRule.testDispatcher) {
        val now = System.currentTimeMillis()
        seed("pkg.a", now - 500, now - 9_000)  // max = now-500 → newest overall
        seed("pkg.b", now - 1_000)

        assertEquals(listOf("pkg.a", "pkg.b"), repo.getRecentlyLaunchedPackages(10))
    }
}
