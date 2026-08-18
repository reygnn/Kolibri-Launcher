package com.github.reygnn.kolibri_launcher.data

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.mutablePreferencesOf
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import com.github.reygnn.kolibri_launcher.core.AppConstants
import com.github.reygnn.kolibri_launcher.rule.MainDispatcherRule
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test

/**
 * Impl test for [DataStoreMaintenanceRepositoryImpl] against a [FakeSettingsDataStore]: the retired-key
 * filter + the DataStore read/edit mechanics (the part a pure-map fake couldn't exercise, see the
 * ADR in `DataStoreMaintenanceRepositoryContract`). The critical property: live keys are NEVER
 * removed.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class DataStoreMaintenanceRepositoryImplTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    private lateinit var dataStore: FakeSettingsDataStore
    private lateinit var repo: DataStoreMaintenanceRepositoryImpl

    // Live keys — must NEVER be removed.
    private val liveUri = stringPreferencesKey("wallpaper_uri")
    private val liveLayers = stringPreferencesKey("wallpaper_layers_json")
    private val liveSetting = stringPreferencesKey("some_live_setting")

    // Retired keys — must be removed.
    private val retiredExact = stringPreferencesKey("wallpaper_flattened_path")
    private val retiredUsage1 = stringSetPreferencesKey(AppConstants.KEY_USAGE_PREFIX + "com.foo")
    private val retiredUsage2 = stringSetPreferencesKey(AppConstants.KEY_USAGE_PREFIX + "com.bar")

    @Before
    fun setUp() {
        dataStore = FakeSettingsDataStore()
        repo = DataStoreMaintenanceRepositoryImpl(dataStore, mainDispatcherRule.testDispatcher)
    }

    @Test
    fun `scanOrphanKeys returns only retired keys, sorted, never live ones`() = runTest {
        dataStore.seed {
            it[liveUri] = "file:///a.jpg"
            it[liveLayers] = "[]"
            it[liveSetting] = "x"
            it[retiredExact] = "file:///c.webp"
            it[retiredUsage1] = setOf("1")
            it[retiredUsage2] = setOf("2")
        }

        assertEquals(
            listOf("usage_com.bar", "usage_com.foo", "wallpaper_flattened_path"),
            repo.scanOrphanKeys(),
        )
    }

    @Test
    fun `removeOrphanKeys deletes retired keys and leaves live keys intact`() = runTest {
        dataStore.seed {
            it[liveUri] = "file:///a.jpg"
            it[liveLayers] = "[]"
            it[liveSetting] = "x"
            it[retiredExact] = "file:///c.webp"
            it[retiredUsage1] = setOf("1")
        }

        assertEquals(2, repo.removeOrphanKeys())

        val prefs = dataStore.data.first()
        // Live keys untouched.
        assertEquals("file:///a.jpg", prefs[liveUri])
        assertEquals("[]", prefs[liveLayers])
        assertEquals("x", prefs[liveSetting])
        // Retired keys gone.
        assertNull(prefs[retiredExact])
        assertNull(prefs[retiredUsage1])
    }

    @Test
    fun `scan and remove are no-ops on a store with no orphans`() = runTest {
        dataStore.seed { it[liveUri] = "file:///a.jpg" }

        assertTrue(repo.scanOrphanKeys().isEmpty())
        assertEquals(0, repo.removeOrphanKeys())
        assertEquals("file:///a.jpg", dataStore.data.first()[liveUri])
    }
}

/** Minimal in-memory DataStore for the JVM test (mirrors the one in WallpaperRepositoryImplTest). */
private class FakeSettingsDataStore(initial: Preferences = emptyPreferences()) : DataStore<Preferences> {
    private val state = MutableStateFlow(initial)

    override val data: Flow<Preferences> = state

    override suspend fun updateData(transform: suspend (t: Preferences) -> Preferences): Preferences {
        val next = transform(state.value)
        state.value = next
        return next
    }

    /** Test helper: seed the store synchronously. */
    fun seed(build: (androidx.datastore.preferences.core.MutablePreferences) -> Unit) {
        val prefs = mutablePreferencesOf()
        build(prefs)
        state.value = prefs
    }
}
