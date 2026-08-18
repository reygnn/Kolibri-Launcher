package com.github.reygnn.kolibri_launcher.data

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.mutablePreferencesOf
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import com.github.reygnn.kolibri_launcher.core.AppConstants
import com.github.reygnn.kolibri_launcher.domain.repository.DataStoreMaintenanceRepository
import com.github.reygnn.kolibri_launcher.rule.MainDispatcherRule
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import java.io.IOException
import kotlin.test.assertFailsWith

/**
 * Impl test for [DataStoreMaintenanceRepositoryImpl] against a [FakeSettingsDataStore]: the retired-key
 * filter + the DataStore edit mechanics (the part a pure-map fake couldn't exercise, see the ADR in
 * `DataStoreMaintenanceRepositoryContract`), plus the fail-soft contract. The critical properties:
 * live keys are NEVER removed, an I/O failure surfaces as [DataStoreMaintenanceRepository.Result.Failed]
 * (not a silent `Removed(0)`), and `CancellationException` always propagates.
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
    fun `removeOrphanKeys deletes retired keys and leaves live keys intact`() = runTest {
        dataStore.seed {
            it[liveUri] = "file:///a.jpg"
            it[liveLayers] = "[]"
            it[liveSetting] = "x"
            it[retiredExact] = "file:///c.webp"
            it[retiredUsage1] = setOf("1")
            it[retiredUsage2] = setOf("2")
        }

        assertEquals(DataStoreMaintenanceRepository.Result.Removed(3), repo.removeOrphanKeys())

        val prefs = dataStore.data.first()
        // Live keys untouched.
        assertEquals("file:///a.jpg", prefs[liveUri])
        assertEquals("[]", prefs[liveLayers])
        assertEquals("x", prefs[liveSetting])
        // Retired keys gone.
        assertNull(prefs[retiredExact])
        assertNull(prefs[retiredUsage1])
        assertNull(prefs[retiredUsage2])
    }

    @Test
    fun `removeOrphanKeys is a no-op reporting Removed(0) on a store with no orphans`() = runTest {
        dataStore.seed { it[liveUri] = "file:///a.jpg" }

        assertEquals(DataStoreMaintenanceRepository.Result.Removed(0), repo.removeOrphanKeys())
        assertEquals("file:///a.jpg", dataStore.data.first()[liveUri])
    }

    @Test
    fun `removeOrphanKeys reports Failed when the store edit throws - never masquerades as clean`() =
        runTest {
            val failing = FakeSettingsDataStore(updateError = IOException("disk full"))
            val failingRepo = DataStoreMaintenanceRepositoryImpl(failing, mainDispatcherRule.testDispatcher)

            assertEquals(DataStoreMaintenanceRepository.Result.Failed, failingRepo.removeOrphanKeys())
        }

    @Test
    fun `removeOrphanKeys propagates CancellationException - never swallows cancellation`() = runTest {
        val cancelling = FakeSettingsDataStore(updateError = CancellationException("cancelled"))
        val cancellingRepo = DataStoreMaintenanceRepositoryImpl(cancelling, mainDispatcherRule.testDispatcher)

        assertFailsWith<CancellationException> { cancellingRepo.removeOrphanKeys() }
    }
}

/**
 * Minimal in-memory DataStore for the JVM test (mirrors the one in WallpaperRepositoryImplTest).
 * When [updateError] is set, [updateData] (and hence `edit {}`) throws it — used to drive the
 * fail-soft / cancellation branches.
 */
private class FakeSettingsDataStore(
    initial: Preferences = emptyPreferences(),
    private val updateError: Throwable? = null,
) : DataStore<Preferences> {
    private val state = MutableStateFlow(initial)

    override val data: Flow<Preferences> = state

    override suspend fun updateData(transform: suspend (t: Preferences) -> Preferences): Preferences {
        updateError?.let { throw it }
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
