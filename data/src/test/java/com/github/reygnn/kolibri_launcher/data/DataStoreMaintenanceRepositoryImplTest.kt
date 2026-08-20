package com.github.reygnn.kolibri_launcher.data

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.mutablePreferencesOf
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import com.github.reygnn.kolibri_launcher.core.AppConstants
import com.github.reygnn.kolibri_launcher.core.OwnsSettingsStoreKeys
import com.github.reygnn.kolibri_launcher.domain.repository.DataStoreMaintenanceRepository
import com.github.reygnn.kolibri_launcher.rule.MainDispatcherRule
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import java.io.IOException
import kotlin.test.assertFailsWith

/**
 * Impl test for [DataStoreMaintenanceRepositoryImpl] against a [FakeSettingsDataStore]: the
 * keep-list (blacklist) filter + the DataStore edit mechanics (the part a pure-map fake couldn't
 * exercise, see the ADR in `DataStoreMaintenanceRepositoryContract`), plus the fail-soft contract.
 *
 * The critical properties, in inverted-failure-mode terms:
 * - every key a live owner claims (exact OR by prefix) is NEVER removed;
 * - every key NO owner claims IS removed;
 * - an empty keep-list (a DI wiring failure) is refused, NOT read as "wipe everything";
 * - an I/O failure surfaces as [DataStoreMaintenanceRepository.Result.Failed] (never a silent
 *   `Removed(0)`), and `CancellationException` always propagates.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class DataStoreMaintenanceRepositoryImplTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    // A representative live keep-list: a couple of exact settings keys plus the custom-name prefix.
    private val owners: Set<OwnsSettingsStoreKeys> = setOf(
        fakeOwner(
            exact = setOf("wallpaper_uri", "wallpaper_layers_json", "some_live_setting"),
            prefixes = setOf(AppConstants.KEY_NAME_PREFIX),
        ),
    )

    // Live keys (claimed by an owner) — must NEVER be removed.
    private val liveUri = stringPreferencesKey("wallpaper_uri")
    private val liveLayers = stringPreferencesKey("wallpaper_layers_json")
    private val liveSetting = stringPreferencesKey("some_live_setting")
    private val liveName = stringPreferencesKey(AppConstants.KEY_NAME_PREFIX + "com.example") // prefix-kept

    // Orphan keys (claimed by no owner) — must be removed.
    private val orphanFlattened = stringPreferencesKey("wallpaper_flattened_path")
    private val orphanUsage = stringSetPreferencesKey(AppConstants.KEY_USAGE_PREFIX + "com.foo")
    private val orphanObsolete = stringPreferencesKey("obsolete_widget_key")

    private fun repo(
        store: FakeSettingsDataStore,
        keyOwners: Set<OwnsSettingsStoreKeys> = owners,
    ) = DataStoreMaintenanceRepositoryImpl(store, keyOwners, mainDispatcherRule.testDispatcher)

    @Test
    fun `removeOrphanKeys deletes un-owned keys and leaves every claimed key intact`() = runTest {
        val dataStore = FakeSettingsDataStore()
        dataStore.seed {
            it[liveUri] = "file:///a.jpg"
            it[liveLayers] = "[]"
            it[liveSetting] = "x"
            it[liveName] = "My App"
            it[orphanFlattened] = "file:///c.webp"
            it[orphanUsage] = setOf("1")
            it[orphanObsolete] = "stale"
        }

        assertEquals(DataStoreMaintenanceRepository.Result.Removed(3), repo(dataStore).removeOrphanKeys())

        val prefs = dataStore.data.first()
        // Claimed keys untouched (exact + prefix).
        assertEquals("file:///a.jpg", prefs[liveUri])
        assertEquals("[]", prefs[liveLayers])
        assertEquals("x", prefs[liveSetting])
        assertEquals("My App", prefs[liveName])
        // Un-owned keys gone.
        assertNull(prefs[orphanFlattened])
        assertNull(prefs[orphanUsage])
        assertNull(prefs[orphanObsolete])
    }

    @Test
    fun `removeOrphanKeys is a no-op reporting Removed(0) on a store with no orphans`() = runTest {
        val dataStore = FakeSettingsDataStore()
        dataStore.seed { it[liveUri] = "file:///a.jpg" }

        assertEquals(DataStoreMaintenanceRepository.Result.Removed(0), repo(dataStore).removeOrphanKeys())
        assertEquals("file:///a.jpg", dataStore.data.first()[liveUri])
    }

    @Test
    fun `removeOrphanKeys refuses an empty keep-list and never wipes the store`() = runTest {
        // An empty owner set can only mean a DI/multibinding failure. Deleting "everything not kept"
        // would wipe the whole settings store, so the impl must refuse and leave the store intact.
        val dataStore = FakeSettingsDataStore()
        dataStore.seed {
            it[liveUri] = "file:///a.jpg"
            it[orphanObsolete] = "stale"
        }

        assertEquals(
            DataStoreMaintenanceRepository.Result.Failed,
            repo(dataStore, keyOwners = emptySet()).removeOrphanKeys(),
        )

        // Nothing deleted — not even the genuine orphan — because the guard aborts before editing.
        val prefs = dataStore.data.first()
        assertEquals("file:///a.jpg", prefs[liveUri])
        assertEquals("stale", prefs[orphanObsolete])
    }

    @Test
    fun `removeOrphanKeys reports Failed when the store edit throws - never masquerades as clean`() =
        runTest {
            val failing = FakeSettingsDataStore(updateError = IOException("disk full"))
            assertEquals(DataStoreMaintenanceRepository.Result.Failed, repo(failing).removeOrphanKeys())
        }

    @Test
    fun `removeOrphanKeys propagates CancellationException - never swallows cancellation`() = runTest {
        val cancelling = FakeSettingsDataStore(updateError = CancellationException("cancelled"))
        assertFailsWith<CancellationException> { repo(cancelling).removeOrphanKeys() }
    }

    // ---- previewOrphanKeys (dry run) ----

    @Test
    fun `previewOrphanKeys lists the un-owned keys sorted and deletes nothing`() = runTest {
        val dataStore = FakeSettingsDataStore()
        dataStore.seed {
            it[liveUri] = "file:///a.jpg"
            it[liveName] = "My App"
            it[orphanFlattened] = "file:///c.webp"
            it[orphanUsage] = setOf("1")
            it[orphanObsolete] = "stale"
        }

        val result = repo(dataStore).previewOrphanKeys()

        assertEquals(
            DataStoreMaintenanceRepository.PreviewResult.Loaded(
                listOf("obsolete_widget_key", "usage_com.foo", "wallpaper_flattened_path"),
            ),
            result,
        )
        // Dry run: the store is unchanged — every key (live AND orphan) still present.
        val prefs = dataStore.data.first()
        assertEquals("file:///a.jpg", prefs[liveUri])
        assertEquals("My App", prefs[liveName])
        assertEquals("file:///c.webp", prefs[orphanFlattened])
        assertEquals(setOf("1"), prefs[orphanUsage])
        assertEquals("stale", prefs[orphanObsolete])
    }

    @Test
    fun `previewOrphanKeys reports Failed on an empty keep-list`() = runTest {
        val dataStore = FakeSettingsDataStore()
        dataStore.seed { it[orphanObsolete] = "stale" }

        assertEquals(
            DataStoreMaintenanceRepository.PreviewResult.Failed,
            repo(dataStore, keyOwners = emptySet()).previewOrphanKeys(),
        )
    }

    @Test
    fun `previewOrphanKeys reports Failed when the read throws - never an empty list`() = runTest {
        val failing = FakeSettingsDataStore(readError = IOException("cannot read"))
        assertEquals(
            DataStoreMaintenanceRepository.PreviewResult.Failed,
            repo(failing).previewOrphanKeys(),
        )
    }

    @Test
    fun `previewOrphanKeys propagates CancellationException - never swallows cancellation`() = runTest {
        val cancelling = FakeSettingsDataStore(readError = CancellationException("cancelled"))
        assertFailsWith<CancellationException> { repo(cancelling).previewOrphanKeys() }
    }
}

private fun fakeOwner(
    exact: Set<String> = emptySet(),
    prefixes: Set<String> = emptySet(),
): OwnsSettingsStoreKeys = object : OwnsSettingsStoreKeys {
    override fun ownedExactKeys(): Set<String> = exact
    override fun ownedKeyPrefixes(): Set<String> = prefixes
}

/**
 * Minimal in-memory DataStore for the JVM test (mirrors the one in WallpaperRepositoryImplTest).
 * When [updateError] is set, [updateData] (and hence `edit {}`) throws it — used to drive the
 * fail-soft / cancellation branches.
 */
private class FakeSettingsDataStore(
    initial: Preferences = emptyPreferences(),
    private val updateError: Throwable? = null,
    private val readError: Throwable? = null,
) : DataStore<Preferences> {
    private val state = MutableStateFlow(initial)

    // When readError is set, collecting `data` (and hence `.first()`) throws it — used to drive the
    // preview fail-soft / cancellation branches.
    override val data: Flow<Preferences> =
        if (readError != null) flow { throw readError } else state

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
