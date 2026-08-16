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
import org.junit.Assert.assertFalse
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
 * Robolectric is used so that `Uri.parse` / `String.toUri()` work on the JVM
 * without needing to stub android.net.Uri manually.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class WallpaperRepositoryImplTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    @get:Rule
    val timberRule = TimberRule()

    // --- DataStore keys (mirror production) ---
    private val KEY_WALLPAPER_URI = stringPreferencesKey("wallpaper_uri")
    private val KEY_WALLPAPER_SCALE = floatPreferencesKey("wallpaper_scale")
    private val KEY_WALLPAPER_TRANSLATE_X = floatPreferencesKey("wallpaper_translate_x")
    private val KEY_WALLPAPER_TRANSLATE_Y = floatPreferencesKey("wallpaper_translate_y")
    private val KEY_LAYERS_JSON = stringPreferencesKey("wallpaper_layers_json")

    private lateinit var dataStore: FakeDataStore
    private lateinit var fileManager: WallpaperFileManager
    private lateinit var manager: WallpaperRepositoryImpl

    @Before
    fun setUp() {
        dataStore = FakeDataStore()
        fileManager = mockk(relaxed = true)
        // Default: every file exists on disk. Individual tests override.
        every { fileManager.fileExists(any<Uri>()) } returns true

        manager = WallpaperRepositoryImpl(dataStore, fileManager, mockk(relaxed = true), mainDispatcherRule.testDispatcher)
    }

    // ===========================================
    // READ — EMPTY / LEGACY / MULTI
    // ===========================================

    @Test
    fun `parseWallpaperState with no keys yields NONE`() = runTest {
        val state = manager.wallpaperState.first()
        assertEquals(WallpaperState.NONE, state)
    }

    @Test
    fun `parseWallpaperState with only legacy keys yields single-layer state`() = runTest {
        dataStore.seed {
            it[KEY_WALLPAPER_URI] = "file:///data/wp1.jpg"
            it[KEY_WALLPAPER_SCALE] = 2.5f
            it[KEY_WALLPAPER_TRANSLATE_X] = -100f
            it[KEY_WALLPAPER_TRANSLATE_Y] = -50f
        }

        val state = manager.wallpaperState.first()

        assertFalse("should NOT be multi-layer", state.isMultiLayer)
        assertNotNull(state.imageUri)
        assertEquals(2.5f, state.scale)
        assertEquals(-100f, state.translateX)
        assertEquals(-50f, state.translateY)
    }

    @Test
    fun `parseWallpaperState with legacy keys but file missing yields NONE`() = runTest {
        every { fileManager.fileExists(any<Uri>()) } returns false
        dataStore.seed {
            it[KEY_WALLPAPER_URI] = "file:///data/deleted.jpg"
            it[KEY_WALLPAPER_SCALE] = 1.5f
        }

        val state = manager.wallpaperState.first()

        assertEquals(WallpaperState.NONE, state)
    }

    @Test
    fun `parseWallpaperState with non-file single-layer URI yields NONE`() = runTest {
        // Regression guard against ACRA-reported
        // "Volume external_primary not found" crash: if a content:// URI
        // ever reaches persistence (old app version, bad restore, or
        // remapped external volume), we must not pass it to setImageURI —
        // it can throw IllegalArgumentException on resolve. Treat as
        // NONE so the user sees an empty wallpaper and can re-pick.
        dataStore.seed {
            it[KEY_WALLPAPER_URI] = "content://media/external_primary/images/media/42"
            it[KEY_WALLPAPER_SCALE] = 1.0f
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
        assertTrue(state.isMultiLayer)
        assertEquals(2, state.layerCount)
        assertEquals("l_ok", state.getLayer(0)!!.id)
        assertEquals("l_ok2", state.getLayer(1)!!.id)
    }

    @Test
    fun `parseWallpaperState with valid LAYERS_JSON yields multi-layer state`() = runTest {
        val json = """
            [
              {"id":"layer_1","imageUri":"file:///data/a.jpg","scale":1.5,"translateX":10.0,"translateY":20.0,"alpha":0.8,"blendModeName":"MULTIPLY","isVisible":true,"label":"Oben"},
              {"id":"layer_2","imageUri":"file:///data/b.jpg","scale":2.0,"translateX":-5.0,"translateY":0.0,"alpha":1.0,"blendModeName":"","isVisible":false,"label":"Unten"}
            ]
        """.trimIndent()

        dataStore.seed {
            it[KEY_LAYERS_JSON] = json
        }

        val state = manager.wallpaperState.first()

        assertTrue("should be multi-layer", state.isMultiLayer)
        assertEquals(2, state.layerCount)

        val l1 = state.getLayer(0)!!
        assertEquals("layer_1", l1.id)
        assertEquals(1.5f, l1.scale)
        assertEquals(10f, l1.translateX)
        assertEquals(20f, l1.translateY)
        assertEquals(0.8f, l1.alpha)
        assertEquals("MULTIPLY", l1.blendModeName)
        assertTrue(l1.isVisible)
        assertEquals("Oben", l1.label)

        val l2 = state.getLayer(1)!!
        assertEquals("layer_2", l2.id)
        assertFalse(l2.isVisible)
        assertNull("empty blendModeName deserializes to null", l2.blendModeName)
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

        assertTrue(state.isMultiLayer)
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
    fun `parseWallpaperState with corrupt JSON falls back to legacy keys`() = runTest {
        dataStore.seed {
            it[KEY_LAYERS_JSON] = "{this is not valid json]"
            it[KEY_WALLPAPER_URI] = "file:///data/legacy.jpg"
            it[KEY_WALLPAPER_SCALE] = 3.0f
        }

        val state = manager.wallpaperState.first()

        assertFalse("fallback must be single-layer, not multi", state.isMultiLayer)
        assertNotNull(state.imageUri)
        assertEquals(3.0f, state.scale)
    }

    @Test
    fun `parseWallpaperState with mixed legacy and multi keys prefers multi`() = runTest {
        // Both keys present: the manager ALWAYS writes legacy keys alongside multi
        // for forward-compat with older code paths. The reader must pick multi.
        val json = """[{"id":"l1","imageUri":"file:///data/a.jpg","scale":2.0}]"""
        dataStore.seed {
            it[KEY_LAYERS_JSON] = json
            it[KEY_WALLPAPER_URI] = "file:///data/a.jpg"
            it[KEY_WALLPAPER_SCALE] = 2.0f
        }

        val state = manager.wallpaperState.first()

        assertTrue(state.isMultiLayer)
        assertEquals(1, state.layerCount)
    }

    // ===========================================
    // WRITE — SAVE / CLEAR / ROUNDTRIP
    // ===========================================

    @Test
    fun `saveWallpaperState single-layer writes legacy keys and removes layers key`() = runTest {
        // Pre-populate layers key to ensure it gets cleared on single-layer save
        dataStore.seed { it[KEY_LAYERS_JSON] = "[]" }

        val single = WallpaperState(
            imageUri = "file:///data/x.jpg",
            scale = 1.5f,
            translateX = 10f,
            translateY = 20f
        )
        manager.saveWallpaperState(single)
        advanceUntilIdle()

        val prefs = dataStore.data.first()
        assertEquals("file:///data/x.jpg", prefs[KEY_WALLPAPER_URI])
        assertEquals(1.5f, prefs[KEY_WALLPAPER_SCALE])
        assertEquals(10f, prefs[KEY_WALLPAPER_TRANSLATE_X])
        assertEquals(20f, prefs[KEY_WALLPAPER_TRANSLATE_Y])
        assertNull("LAYERS_JSON must be cleared in single-layer mode", prefs[KEY_LAYERS_JSON])
    }

    @Test
    fun `saveWallpaperState multi-layer writes JSON and legacy keys from first layer`() = runTest {
        val state = WallpaperState.multiLayer(
            listOf(
                WallpaperLayerState(
                    id = "l1",
                    imageUri = "file:///data/a.jpg",
                    scale = 2.0f
                ),
                WallpaperLayerState(
                    id = "l2",
                    imageUri = "file:///data/b.jpg",
                    scale = 3.0f
                )
            )
        )

        manager.saveWallpaperState(state)
        advanceUntilIdle()

        val prefs = dataStore.data.first()
        assertNotNull(prefs[KEY_LAYERS_JSON])
        // Legacy fallback: first layer with image
        assertEquals("file:///data/a.jpg", prefs[KEY_WALLPAPER_URI])
        assertEquals(2.0f, prefs[KEY_WALLPAPER_SCALE])
    }

    @Test
    fun `saveWallpaperState with empty state removes all keys`() = runTest {
        dataStore.seed {
            it[KEY_WALLPAPER_URI] = "file:///data/a.jpg"
            it[KEY_WALLPAPER_SCALE] = 2.0f
            it[KEY_LAYERS_JSON] = "[]"
        }

        manager.saveWallpaperState(WallpaperState.NONE)
        advanceUntilIdle()

        val prefs = dataStore.data.first()
        assertNull(prefs[KEY_WALLPAPER_URI])
        assertNull(prefs[KEY_WALLPAPER_SCALE])
        assertNull(prefs[KEY_LAYERS_JSON])
    }

    @Test
    fun `clearWallpaper removes all keys`() = runTest {
        dataStore.seed {
            it[KEY_WALLPAPER_URI] = "file:///data/a.jpg"
            it[KEY_LAYERS_JSON] = "[]"
        }

        manager.clearWallpaper()
        advanceUntilIdle()

        val prefs = dataStore.data.first()
        assertNull(prefs[KEY_WALLPAPER_URI])
        assertNull(prefs[KEY_LAYERS_JSON])
    }

    @Test
    fun `purgeRepository removes all keys`() = runTest {
        dataStore.seed {
            it[KEY_WALLPAPER_URI] = "file:///data/a.jpg"
            it[KEY_LAYERS_JSON] = "[]"
        }

        manager.purgeRepository()
        advanceUntilIdle()

        val prefs = dataStore.data.first()
        assertNull(prefs[KEY_WALLPAPER_URI])
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
    fun `multi-layer roundtrip preserves all fields`() = runTest {
        val original = WallpaperState.multiLayer(
            listOf(
                WallpaperLayerState(
                    id = "abc_123",
                    imageUri = "file:///data/a.jpg",
                    scale = 1.25f,
                    translateX = 7f,
                    translateY = -3f,
                    alpha = 0.5f,
                    blendModeName = "SCREEN",
                    isVisible = false,
                    label = "Oben"
                )
            )
        )

        manager.saveWallpaperState(original)
        advanceUntilIdle()

        val loaded = manager.wallpaperState.first()

        assertTrue(loaded.isMultiLayer)
        assertEquals(1, loaded.layerCount)
        val layer = loaded.getLayer(0)!!
        assertEquals("abc_123", layer.id)
        assertEquals(1.25f, layer.scale)
        assertEquals(7f, layer.translateX)
        assertEquals(-3f, layer.translateY)
        assertEquals(0.5f, layer.alpha)
        assertEquals("SCREEN", layer.blendModeName)
        assertFalse(layer.isVisible)
        assertEquals("Oben", layer.label)
    }

    @Test
    fun `getWallpaperStateSync returns current persisted value`() = runTest {
        dataStore.seed {
            it[KEY_WALLPAPER_URI] = "file:///data/a.jpg"
            it[KEY_WALLPAPER_SCALE] = 2.0f
        }

        val state = manager.getWallpaperStateSync()

        assertEquals("file:///data/a.jpg", state.imageUri)
        assertEquals(2.0f, state.scale)
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
        assertNull(prefs[KEY_WALLPAPER_URI])
        assertNull(prefs[KEY_LAYERS_JSON])
    }

    @Test
    fun `purgeRepository has same effect as clearWallpaper`() = runTest {
        // Both methods must wipe identical key sets. They currently share
        // removeAllKeys() — this test guards against future divergence
        // (e.g. someone adds purge-only behavior without updating clear).
        val seed: (androidx.datastore.preferences.core.MutablePreferences) -> Unit = {
            it[KEY_WALLPAPER_URI] = "file:///data/a.jpg"
            it[KEY_WALLPAPER_SCALE] = 1.5f
            it[KEY_WALLPAPER_TRANSLATE_X] = 10f
            it[KEY_WALLPAPER_TRANSLATE_Y] = 20f
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
        val tinyState = WallpaperState(imageUri = "file:///data/x.jpg", scale = 0.25f)
        manager.saveWallpaperState(tinyState)
        advanceUntilIdle()
        assertEquals(0.25f, manager.wallpaperState.first().scale)

        val hugeState = WallpaperState(imageUri = "file:///data/x.jpg", scale = 8.0f)
        manager.saveWallpaperState(hugeState)
        advanceUntilIdle()
        assertEquals(8.0f, manager.wallpaperState.first().scale)
    }

    @Test
    fun `saveWallpaperState handles negative translate values`() = runTest {
        // Negative translate is normal during pan operations. READ-side is
        // covered by `with only legacy keys` (-100/-50); this guards the
        // SAVE-side specifically against future clamping at write time.
        val state = WallpaperState(
            imageUri = "file:///data/x.jpg",
            translateX = -999f,
            translateY = -1500f
        )

        manager.saveWallpaperState(state)
        advanceUntilIdle()

        val prefs = dataStore.data.first()
        assertEquals(-999f, prefs[KEY_WALLPAPER_TRANSLATE_X])
        assertEquals(-1500f, prefs[KEY_WALLPAPER_TRANSLATE_Y])
    }

    // ===========================================
    // AUDIT-19 F2: projection + distinct before the parse. An unrelated
    // write to the shared store must NOT re-run parseWallpaperState (JSON
    // parse + per-layer fileExists stat); a wallpaper-key change must.
    // ===========================================

    @Test
    fun `unrelated preference change does not re-parse wallpaper state`() = runTest {
        dataStore.seed {
            it[KEY_WALLPAPER_URI] = "file:///data/wp.jpg"
            it[KEY_WALLPAPER_SCALE] = 1.0f
        }

        val emissions = mutableListOf<WallpaperState>()
        val job = launch { manager.wallpaperState.collect { emissions.add(it) } }
        advanceUntilIdle()

        // Baseline: parsed once (one fileExists stat for the single layer).
        assertEquals(1, emissions.size)
        verify(exactly = 1) { fileManager.fileExists(any<Uri>()) }

        // Merge in an UNRELATED key (edit() keeps the wallpaper keys intact).
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
            it[KEY_WALLPAPER_URI] = "file:///data/wp.jpg"
            it[KEY_WALLPAPER_SCALE] = 1.0f
        }

        val emissions = mutableListOf<WallpaperState>()
        val job = launch { manager.wallpaperState.collect { emissions.add(it) } }
        advanceUntilIdle()

        // Change a WALLPAPER key — must pass the distinct gate.
        dataStore.edit { it[KEY_WALLPAPER_SCALE] = 2.0f }
        advanceUntilIdle()

        job.cancel()

        assertEquals("a real wallpaper change must re-emit", 2, emissions.size)
        assertEquals(2.0f, emissions.last().scale)
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