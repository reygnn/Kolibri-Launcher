package com.github.reygnn.kolibri_launcher.data

import android.net.Uri
import androidx.core.net.toUri
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.mutablePreferencesOf
import androidx.datastore.preferences.core.stringPreferencesKey
import com.github.reygnn.kolibri_launcher.domain.model.WallpaperLayerState
import com.github.reygnn.kolibri_launcher.domain.model.WallpaperState
import com.github.reygnn.kolibri_launcher.rule.MainDispatcherRule
import com.github.reygnn.kolibri_launcher.rule.TimberRule
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.robolectric.RobolectricTestRunner
import org.junit.runner.RunWith

/**
 * Unit tests for [WallpaperRepositoryImpl].
 *
 * These tests run against an in-memory fake [DataStore] (no real filesystem
 * persistence), with a mocked [WallpaperFileManager] controlling whether
 * referenced files are "present" on disk.
 *
 * A wallpaper is persisted ONLY as a JSON array under `wallpaper_layers_json`
 * (a single image is a one-element array). The legacy flat single-layer keys
 * (`wallpaper_uri` etc.) were removed with the flat [WallpaperState]
 * representation: they are no longer written and no longer read (the sole
 * migration path across the break is export → reset → restore).
 *
 * Robolectric is used so that `String.toUri()` works on the JVM without
 * stubbing android.net.Uri manually.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class WallpaperRepositoryImplTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    @get:Rule
    val timberRule = TimberRule()

    // --- DataStore keys (mirror production) ---
    private val KEY_LAYERS_JSON = stringPreferencesKey("wallpaper_layers_json")

    // Legacy keys: no longer written or read by the impl. Kept here only so the
    // "legacy-only store yields NONE" regression test can seed them.
    private val KEY_WALLPAPER_URI = stringPreferencesKey("wallpaper_uri")
    private val KEY_WALLPAPER_SCALE = floatPreferencesKey("wallpaper_scale")

    private lateinit var dataStore: FakeDataStore
    private lateinit var fileManager: WallpaperFileManager
    private lateinit var manager: WallpaperRepositoryImpl

    @Before
    fun setUp() {
        dataStore = FakeDataStore()
        fileManager = mockk(relaxed = true)
        // Default: every file exists on disk. Individual tests override.
        every { fileManager.fileExists(any<Uri>()) } returns true

        manager = WallpaperRepositoryImpl(dataStore, fileManager, mainDispatcherRule.testDispatcher)
    }

    // ===========================================
    // READ — EMPTY / SINGLE / MULTI
    // ===========================================

    @Test
    fun `parseWallpaperState with no keys yields NONE`() = runTest {
        val state = manager.wallpaperState.first()
        assertEquals(WallpaperState.NONE, state)
    }

    @Test
    fun `parseWallpaperState with only legacy keys yields NONE (legacy no longer read)`() = runTest {
        // The flat single-layer keys are dead: an old install's single-image
        // wallpaper stored under them is NOT resurrected (breaking change, by
        // design — the store-cleanup later sweeps them as orphans).
        dataStore.seed {
            it[KEY_WALLPAPER_URI] = "file:///data/wp1.jpg"
            it[KEY_WALLPAPER_SCALE] = 2.5f
        }

        val state = manager.wallpaperState.first()

        assertEquals(WallpaperState.NONE, state)
    }

    @Test
    fun `parseWallpaperState with single-element JSON yields one-layer state`() = runTest {
        val json = """[{"id":"only","imageUri":"file:///data/x.jpg","scale":2.5,"translateX":-100.0,"translateY":-50.0}]"""
        dataStore.seed { it[KEY_LAYERS_JSON] = json }

        val state = manager.wallpaperState.first()

        assertEquals(1, state.layerCount)
        val layer = state.layers.single()
        assertEquals("file:///data/x.jpg", layer.imageUri)
        assertEquals(2.5f, layer.scale)
        assertEquals(-100f, layer.translateX)
        assertEquals(-50f, layer.translateY)
    }

    @Test
    fun `parseWallpaperState with single-layer file missing yields NONE`() = runTest {
        every { fileManager.fileExists(any<Uri>()) } returns false
        dataStore.seed {
            it[KEY_LAYERS_JSON] = """[{"id":"l","imageUri":"file:///data/deleted.jpg","scale":1.5}]"""
        }

        val state = manager.wallpaperState.first()

        assertEquals(WallpaperState.NONE, state)
    }

    @Test
    fun `parseWallpaperState with non-file single-layer URI yields NONE`() = runTest {
        // Regression guard against ACRA-reported
        // "Volume external_primary not found" crash: if a content:// URI ever
        // reaches persistence (old app version, bad restore, or remapped
        // external volume), we must not pass it to setImageURI. Treat as NONE.
        dataStore.seed {
            it[KEY_LAYERS_JSON] =
                """[{"id":"l","imageUri":"content://media/external_primary/images/media/42","scale":1.0}]"""
        }

        val state = manager.wallpaperState.first()

        assertEquals(WallpaperState.NONE, state)
    }

    @Test
    fun `parseWallpaperState with mixed file and non-file multi-layer URIs drops only the bad ones`() = runTest {
        val json = """
            [
              {"id":"l_ok","imageUri":"file:///data/a.jpg"},
              {"id":"l_bad","imageUri":"content://media/external_primary/images/media/42"},
              {"id":"l_ok2","imageUri":"file:///data/b.jpg"}
            ]
        """.trimIndent()
        dataStore.seed { it[KEY_LAYERS_JSON] = json }

        val state = manager.wallpaperState.first()

        // Bad URI layer dropped; good ones kept.
        assertEquals(2, state.layerCount)
        assertEquals("l_ok", state.getLayer(0)!!.id)
        assertEquals("l_ok2", state.getLayer(1)!!.id)
    }

    @Test
    fun `parseWallpaperState with valid LAYERS_JSON yields multi-layer state`() = runTest {
        val json = """
            [
              {"id":"layer_1","imageUri":"file:///data/a.jpg","scale":1.5,"translateX":10.0,"translateY":20.0},
              {"id":"layer_2","imageUri":"file:///data/b.jpg","scale":2.0,"translateX":-5.0,"translateY":0.0}
            ]
        """.trimIndent()

        dataStore.seed {
            it[KEY_LAYERS_JSON] = json
        }

        val state = manager.wallpaperState.first()

        assertEquals(2, state.layerCount)

        val l1 = state.getLayer(0)!!
        assertEquals("layer_1", l1.id)
        assertEquals(1.5f, l1.scale)
        assertEquals(10f, l1.translateX)
        assertEquals(20f, l1.translateY)

        val l2 = state.getLayer(1)!!
        assertEquals("layer_2", l2.id)
        assertEquals(2.0f, l2.scale)
        assertEquals(-5f, l2.translateX)
        assertEquals(0f, l2.translateY)
    }

    @Test
    fun `parseWallpaperState with some layer files missing drops those layers`() = runTest {
        // layer a exists, layer b is missing on disk
        val aUri = "file:///data/a.jpg".toUri()
        val bUri = "file:///data/b.jpg".toUri()
        every { fileManager.fileExists(aUri) } returns true
        every { fileManager.fileExists(bUri) } returns false

        val json = """
            [
              {"id":"la","imageUri":"file:///data/a.jpg"},
              {"id":"lb","imageUri":"file:///data/b.jpg"}
            ]
        """.trimIndent()
        dataStore.seed { it[KEY_LAYERS_JSON] = json }

        val state = manager.wallpaperState.first()

        assertEquals(1, state.layerCount)
        assertEquals("la", state.getLayer(0)!!.id)
    }

    @Test
    fun `parseWallpaperState with all layer files missing yields NONE`() = runTest {
        every { fileManager.fileExists(any<Uri>()) } returns false
        val json = """
            [
              {"id":"la","imageUri":"file:///data/a.jpg"},
              {"id":"lb","imageUri":"file:///data/b.jpg"}
            ]
        """.trimIndent()
        dataStore.seed { it[KEY_LAYERS_JSON] = json }

        val state = manager.wallpaperState.first()

        assertEquals(WallpaperState.NONE, state)
    }

    @Test
    fun `parseWallpaperState with corrupt JSON yields NONE (no legacy fallback)`() = runTest {
        // Even with legacy keys still lying around, an unparsable JSON collapses
        // to NONE — the legacy single-layer recovery path is gone.
        dataStore.seed {
            it[KEY_LAYERS_JSON] = "{this is not valid json]"
            it[KEY_WALLPAPER_URI] = "file:///data/legacy.jpg"
            it[KEY_WALLPAPER_SCALE] = 3.0f
        }

        val state = manager.wallpaperState.first()

        assertEquals(WallpaperState.NONE, state)
    }

    // ===========================================
    // WRITE — SAVE / CLEAR / ROUNDTRIP
    // ===========================================

    @Test
    fun `saveWallpaperState single image writes a one-element JSON array`() = runTest {
        val single = WallpaperState.single(
            uri = "file:///data/x.jpg",
            scale = 1.5f,
            translateX = 10f,
            translateY = 20f,
        )
        manager.saveWallpaperState(single)
        advanceUntilIdle()

        val prefs = dataStore.data.first()
        assertNotNull("single image must persist as JSON", prefs[KEY_LAYERS_JSON])

        // Round-trip: reads back as a one-layer state with the same values.
        val loaded = manager.wallpaperState.first()
        assertEquals(1, loaded.layerCount)
        val layer = loaded.layers.single()
        assertEquals("file:///data/x.jpg", layer.imageUri)
        assertEquals(1.5f, layer.scale)
        assertEquals(10f, layer.translateX)
        assertEquals(20f, layer.translateY)
    }

    @Test
    fun `saveWallpaperState multi-layer writes JSON`() = runTest {
        val state = WallpaperState.multiLayer(
            listOf(
                WallpaperLayerState(id = "l1", imageUri = "file:///data/a.jpg", scale = 2.0f),
                WallpaperLayerState(id = "l2", imageUri = "file:///data/b.jpg", scale = 3.0f)
            )
        )

        manager.saveWallpaperState(state)
        advanceUntilIdle()

        val prefs = dataStore.data.first()
        assertNotNull(prefs[KEY_LAYERS_JSON])

        val loaded = manager.wallpaperState.first()
        assertEquals(2, loaded.layerCount)
        assertEquals("file:///data/a.jpg", loaded.getLayer(0)!!.imageUri)
    }

    @Test
    fun `saveWallpaperState with empty state removes the layers key`() = runTest {
        dataStore.seed { it[KEY_LAYERS_JSON] = "[]" }

        manager.saveWallpaperState(WallpaperState.NONE)
        advanceUntilIdle()

        val prefs = dataStore.data.first()
        assertNull(prefs[KEY_LAYERS_JSON])
    }

    @Test
    fun `clearWallpaper removes the layers key`() = runTest {
        dataStore.seed { it[KEY_LAYERS_JSON] = """[{"id":"l","imageUri":"file:///data/a.jpg"}]""" }

        manager.clearWallpaper()
        advanceUntilIdle()

        val prefs = dataStore.data.first()
        assertNull(prefs[KEY_LAYERS_JSON])
    }

    @Test
    fun `purgeRepository removes the layers key`() = runTest {
        dataStore.seed { it[KEY_LAYERS_JSON] = """[{"id":"l","imageUri":"file:///data/a.jpg"}]""" }

        manager.purgeRepository()
        advanceUntilIdle()

        val prefs = dataStore.data.first()
        assertNull(prefs[KEY_LAYERS_JSON])
    }

    @Test
    fun `purgeRepository also deletes on-disk wallpaper files`() = runTest {
        // AUDIT-9 #7: a factory reset (which routes through purgeRepository)
        // must clear the wallpaper image files too, not just the DataStore
        // keys — otherwise orphaned files linger in filesDir/wallpapers/ until
        // the next cold-start gcOrphans sweep. Pins the wiring to clearAll().
        manager.purgeRepository()
        advanceUntilIdle()

        verify(exactly = 1) { fileManager.clearAll() }
    }

    @Test
    fun `single image roundtrip preserves all fields`() = runTest {
        val original = WallpaperState.single(
            uri = "file:///data/a.jpg",
            scale = 1.25f,
            translateX = 7f,
            translateY = -3f,
        )

        manager.saveWallpaperState(original)
        advanceUntilIdle()

        val loaded = manager.wallpaperState.first()

        assertEquals(1, loaded.layerCount)
        val layer = loaded.getLayer(0)!!
        assertEquals("file:///data/a.jpg", layer.imageUri)
        assertEquals(1.25f, layer.scale)
        assertEquals(7f, layer.translateX)
        assertEquals(-3f, layer.translateY)
    }

    @Test
    fun `multi-layer roundtrip preserves all fields`() = runTest {
        val original = WallpaperState.multiLayer(
            listOf(
                WallpaperLayerState(
                    id = "abc_123",
                    imageUri = "file:///data/a.jpg",
                    scale = 1.25f,
                    translateX = 7f,
                    translateY = -3f,
                ),
                WallpaperLayerState(
                    id = "def_456",
                    imageUri = "file:///data/b.jpg",
                    scale = 2.0f,
                ),
            )
        )

        manager.saveWallpaperState(original)
        advanceUntilIdle()

        val loaded = manager.wallpaperState.first()

        assertEquals(2, loaded.layerCount)
        val layer = loaded.getLayer(0)!!
        assertEquals("abc_123", layer.id)
        assertEquals(1.25f, layer.scale)
        assertEquals(7f, layer.translateX)
        assertEquals(-3f, layer.translateY)
    }

    @Test
    fun `getWallpaperStateSync returns current persisted value`() = runTest {
        dataStore.seed {
            it[KEY_LAYERS_JSON] = """[{"id":"l","imageUri":"file:///data/a.jpg","scale":2.0}]"""
        }

        val state = manager.getWallpaperStateSync()

        assertEquals(1, state.layerCount)
        assertEquals("file:///data/a.jpg", state.layers.single().imageUri)
        assertEquals(2.0f, state.layers.single().scale)
    }

    // ===========================================
    // EDGE CASES — VALUE PASSTHROUGH
    // ===========================================

    @Test
    fun `clearWallpaper on already empty state is idempotent`() = runTest {
        // No seed — DataStore starts empty.

        manager.clearWallpaper()
        advanceUntilIdle()

        // Calling it again must also be safe (regression guard against
        // accidental "first call required" assumptions in removeAllKeys).
        manager.clearWallpaper()
        advanceUntilIdle()

        val state = manager.wallpaperState.first()
        assertEquals(WallpaperState.NONE, state)

        val prefs = dataStore.data.first()
        assertNull(prefs[KEY_LAYERS_JSON])
    }

    @Test
    fun `purgeRepository has same effect as clearWallpaper`() = runTest {
        // Both methods must wipe identical key sets. They currently share
        // removeAllKeys() — this test guards against future divergence.
        val seed: (androidx.datastore.preferences.core.MutablePreferences) -> Unit = {
            it[KEY_LAYERS_JSON] = """[{"id":"l1","imageUri":"file:///data/a.jpg"}]"""
        }

        dataStore.seed(seed)
        manager.purgeRepository()
        advanceUntilIdle()
        val afterPurge = dataStore.data.first().asMap()

        dataStore.seed(seed)
        manager.clearWallpaper()
        advanceUntilIdle()
        val afterClear = dataStore.data.first().asMap()

        assertEquals(afterClear, afterPurge)
    }

    @Test
    fun `saveWallpaperState handles extreme scale values`() = runTest {
        // Guards against silent clamping (e.g. a future min/max validator).
        // 0.25f and 8.0f are both exactly representable as Float to avoid
        // precision noise in the round-trip via Double in the JSON path.
        manager.saveWallpaperState(WallpaperState.single("file:///data/x.jpg", scale = 0.25f))
        advanceUntilIdle()
        assertEquals(0.25f, manager.wallpaperState.first().layers.single().scale)

        manager.saveWallpaperState(WallpaperState.single("file:///data/x.jpg", scale = 8.0f))
        advanceUntilIdle()
        assertEquals(8.0f, manager.wallpaperState.first().layers.single().scale)
    }

    @Test
    fun `saveWallpaperState handles negative translate values`() = runTest {
        // Negative translate is normal during pan operations. Guards the
        // SAVE-side against future clamping at write time.
        val state = WallpaperState.single(
            uri = "file:///data/x.jpg",
            translateX = -999f,
            translateY = -1500f,
        )

        manager.saveWallpaperState(state)
        advanceUntilIdle()

        val layer = manager.wallpaperState.first().layers.single()
        assertEquals(-999f, layer.translateX)
        assertEquals(-1500f, layer.translateY)
    }

    // ===========================================
    // AUDIT-19 F2: projection + distinct before the parse. An unrelated
    // write to the shared store must NOT re-run parseWallpaperState (JSON
    // parse + per-layer fileExists stat); a wallpaper-key change must.
    // ===========================================

    @Test
    fun `unrelated preference change does not re-parse wallpaper state`() = runTest {
        dataStore.seed {
            it[KEY_LAYERS_JSON] = """[{"id":"l","imageUri":"file:///data/wp.jpg","scale":1.0}]"""
        }

        val emissions = mutableListOf<WallpaperState>()
        val job = launch { manager.wallpaperState.collect { emissions.add(it) } }
        advanceUntilIdle()

        // Baseline: parsed once (one fileExists stat for the single layer).
        assertEquals(1, emissions.size)
        verify(exactly = 1) { fileManager.fileExists(any<Uri>()) }

        // Merge in an UNRELATED key (edit() keeps the wallpaper key intact).
        dataStore.edit { it[stringPreferencesKey("some_unrelated_setting")] = "x" }
        advanceUntilIdle()

        job.cancel()

        assertEquals("unrelated change must not re-emit wallpaper state", 1, emissions.size)
        // No re-parse → no second disk stat.
        verify(exactly = 1) { fileManager.fileExists(any<Uri>()) }
    }

    @Test
    fun `wallpaper key change does re-parse and re-emit`() = runTest {
        dataStore.seed {
            it[KEY_LAYERS_JSON] = """[{"id":"l","imageUri":"file:///data/wp.jpg","scale":1.0}]"""
        }

        val emissions = mutableListOf<WallpaperState>()
        val job = launch { manager.wallpaperState.collect { emissions.add(it) } }
        advanceUntilIdle()

        // Change the wallpaper key — must pass the distinct gate.
        dataStore.edit {
            it[KEY_LAYERS_JSON] = """[{"id":"l","imageUri":"file:///data/wp.jpg","scale":2.0}]"""
        }
        advanceUntilIdle()

        job.cancel()

        assertEquals("a real wallpaper change must re-emit", 2, emissions.size)
        assertEquals(2.0f, emissions.last().layers.single().scale)
    }
}

// ===========================================
// FAKE IMPLEMENTATION
// ===========================================

/**
 * In-memory DataStore<Preferences> fake for unit tests. Supports edit() and
 * flow-based reads; no disk persistence.
 */
private class FakeDataStore(initial: Preferences = emptyPreferences()) : DataStore<Preferences> {
    private val state = MutableStateFlow(initial)

    override val data: Flow<Preferences> = state

    override suspend fun updateData(
        transform: suspend (t: Preferences) -> Preferences
    ): Preferences {
        val current = state.value
        val next = transform(current)
        state.value = next
        return next
    }

    /** Test helper: seed the store synchronously (bypasses updateData). */
    fun seed(build: (androidx.datastore.preferences.core.MutablePreferences) -> Unit) {
        val prefs = mutablePreferencesOf()
        build(prefs)
        state.value = prefs
    }
}
