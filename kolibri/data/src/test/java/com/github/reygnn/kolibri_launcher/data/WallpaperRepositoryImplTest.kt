package com.github.reygnn.kolibri_launcher.data
import kotlin.test.assertFailsWith
import java.io.IOException
import kotlinx.coroutines.CancellationException
import com.github.reygnn.launcher.common.data.wallpaper.WallpaperRepositoryImpl
import com.github.reygnn.launcher.common.data.wallpaper.WallpaperFileManager

import android.net.Uri
import androidx.core.net.toUri
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.mutablePreferencesOf
import androidx.datastore.preferences.core.stringPreferencesKey
import com.github.reygnn.launcher.core.wallpaper.WallpaperLayerState
import com.github.reygnn.launcher.core.wallpaper.WallpaperState
import com.github.reygnn.launcher.core.testing.MainDispatcherRule
import com.github.reygnn.kolibri_launcher.rule.TimberRule
import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
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
        // Default: every wallpaper file deleted. A relaxed Boolean is false, which the purge would
        // report as "files left" (2b-4c, F1).
        every { fileManager.clearAll() } returns true

        manager = WallpaperRepositoryImpl(dataStore, fileManager, mainDispatcherRule.testDispatcher)
    }

    // ===========================================
    // READ — EMPTY / SINGLE / MULTI
    // ===========================================

    @Test
    fun `parseWallpaperState with no keys yields NONE`() = runTest {
        val state = manager.wallpaperState.first()
        assertThat(state).isEqualTo(WallpaperState.NONE)
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

        assertThat(state).isEqualTo(WallpaperState.NONE)
    }

    @Test
    fun `parseWallpaperState with single-element JSON yields one-layer state`() = runTest {
        val json = """[{"id":"only","imageUri":"file:///data/x.jpg","scale":2.5,"translateX":-100.0,"translateY":-50.0}]"""
        dataStore.seed { it[KEY_LAYERS_JSON] = json }

        val state = manager.wallpaperState.first()

        assertThat(state.layerCount).isEqualTo(1)
        val layer = state.layers.single()
        assertThat(layer.imageUri).isEqualTo("file:///data/x.jpg")
        assertThat(layer.scale).isEqualTo(2.5f)
        assertThat(layer.translateX).isEqualTo(-100f)
        assertThat(layer.translateY).isEqualTo(-50f)
    }

    @Test
    fun `parseWallpaperState with single-layer file missing yields NONE`() = runTest {
        every { fileManager.fileExists(any<Uri>()) } returns false
        dataStore.seed {
            it[KEY_LAYERS_JSON] = """[{"id":"l","imageUri":"file:///data/deleted.jpg","scale":1.5}]"""
        }

        val state = manager.wallpaperState.first()

        assertThat(state).isEqualTo(WallpaperState.NONE)
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

        assertThat(state).isEqualTo(WallpaperState.NONE)
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
        assertThat(state.layerCount).isEqualTo(2)
        assertThat(state.getLayer(0)!!.id).isEqualTo("l_ok")
        assertThat(state.getLayer(1)!!.id).isEqualTo("l_ok2")
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

        assertThat(state.layerCount).isEqualTo(2)

        val l1 = state.getLayer(0)!!
        assertThat(l1.id).isEqualTo("layer_1")
        assertThat(l1.scale).isEqualTo(1.5f)
        assertThat(l1.translateX).isEqualTo(10f)
        assertThat(l1.translateY).isEqualTo(20f)

        val l2 = state.getLayer(1)!!
        assertThat(l2.id).isEqualTo("layer_2")
        assertThat(l2.scale).isEqualTo(2.0f)
        assertThat(l2.translateX).isEqualTo(-5f)
        assertThat(l2.translateY).isEqualTo(0f)
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

        assertThat(state.layerCount).isEqualTo(1)
        assertThat(state.getLayer(0)!!.id).isEqualTo("la")
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

        assertThat(state).isEqualTo(WallpaperState.NONE)
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

        assertThat(state).isEqualTo(WallpaperState.NONE)
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
        assertWithMessage("single image must persist as JSON").that(prefs[KEY_LAYERS_JSON]).isNotNull()

        // Round-trip: reads back as a one-layer state with the same values.
        val loaded = manager.wallpaperState.first()
        assertThat(loaded.layerCount).isEqualTo(1)
        val layer = loaded.layers.single()
        assertThat(layer.imageUri).isEqualTo("file:///data/x.jpg")
        assertThat(layer.scale).isEqualTo(1.5f)
        assertThat(layer.translateX).isEqualTo(10f)
        assertThat(layer.translateY).isEqualTo(20f)
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
        assertThat(prefs[KEY_LAYERS_JSON]).isNotNull()

        val loaded = manager.wallpaperState.first()
        assertThat(loaded.layerCount).isEqualTo(2)
        assertThat(loaded.getLayer(0)!!.imageUri).isEqualTo("file:///data/a.jpg")
    }

    @Test
    fun `saveWallpaperState with empty state removes the layers key`() = runTest {
        dataStore.seed { it[KEY_LAYERS_JSON] = "[]" }

        manager.saveWallpaperState(WallpaperState.NONE)
        advanceUntilIdle()

        val prefs = dataStore.data.first()
        assertThat(prefs[KEY_LAYERS_JSON]).isNull()
    }

    @Test
    fun `clearWallpaper removes the layers key`() = runTest {
        dataStore.seed { it[KEY_LAYERS_JSON] = """[{"id":"l","imageUri":"file:///data/a.jpg"}]""" }

        manager.clearWallpaper()
        advanceUntilIdle()

        val prefs = dataStore.data.first()
        assertThat(prefs[KEY_LAYERS_JSON]).isNull()
    }

    @Test
    fun `purgeRepository removes the layers key`() = runTest {
        dataStore.seed { it[KEY_LAYERS_JSON] = """[{"id":"l","imageUri":"file:///data/a.jpg"}]""" }

        manager.purgeRepository()
        advanceUntilIdle()

        val prefs = dataStore.data.first()
        assertThat(prefs[KEY_LAYERS_JSON]).isNull()
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

    // ---- an incomplete purge is reported, and every step still runs (2b-4c, F1) ----

    @Test
    fun `purgeRepository still deletes the files when removing the key fails, then throws`() = runTest {
        dataStore.makeEditFail()

        val error = assertFailsWith<IOException> { manager.purgeRepository() }

        assertThat(error).hasMessageThat().contains("Simulated edit failure")
        verify(exactly = 1) { fileManager.clearAll() } // the independent step ran anyway
    }

    @Test
    fun `purgeRepository throws when wallpaper files are left`() = runTest {
        every { fileManager.clearAll() } returns false

        val error = assertFailsWith<IOException> { manager.purgeRepository() }

        assertThat(error).hasMessageThat().isEqualTo("Not every wallpaper file could be deleted")
        assertThat(dataStore.data.first()[KEY_LAYERS_JSON]).isNull() // the key went regardless
    }

    @Test
    fun `purgeRepository keeps both failures, the first thrown and the second suppressed`() = runTest {
        dataStore.makeEditFail()
        every { fileManager.clearAll() } returns false

        val error = assertFailsWith<IOException> { manager.purgeRepository() }

        assertThat(error).hasMessageThat().contains("Simulated edit failure")
        assertThat(error.suppressed.map { it.message }).containsExactly("Not every wallpaper file could be deleted")
    }

    @Test
    fun `purgeRepository lets a cancellation through unchanged`() = runTest {
        dataStore.makeCancellable()

        assertFailsWith<CancellationException> { manager.purgeRepository() }
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

        assertThat(loaded.layerCount).isEqualTo(1)
        val layer = loaded.getLayer(0)!!
        assertThat(layer.imageUri).isEqualTo("file:///data/a.jpg")
        assertThat(layer.scale).isEqualTo(1.25f)
        assertThat(layer.translateX).isEqualTo(7f)
        assertThat(layer.translateY).isEqualTo(-3f)
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

        assertThat(loaded.layerCount).isEqualTo(2)
        val layer = loaded.getLayer(0)!!
        assertThat(layer.id).isEqualTo("abc_123")
        assertThat(layer.scale).isEqualTo(1.25f)
        assertThat(layer.translateX).isEqualTo(7f)
        assertThat(layer.translateY).isEqualTo(-3f)
    }

    @Test
    fun `getWallpaperStateSync returns current persisted value`() = runTest {
        dataStore.seed {
            it[KEY_LAYERS_JSON] = """[{"id":"l","imageUri":"file:///data/a.jpg","scale":2.0}]"""
        }

        val state = manager.getWallpaperStateSync()

        assertThat(state.layerCount).isEqualTo(1)
        assertThat(state.layers.single().imageUri).isEqualTo("file:///data/a.jpg")
        assertThat(state.layers.single().scale).isEqualTo(2.0f)
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
        assertThat(state).isEqualTo(WallpaperState.NONE)

        val prefs = dataStore.data.first()
        assertThat(prefs[KEY_LAYERS_JSON]).isNull()
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

        assertThat(afterPurge).isEqualTo(afterClear)
    }

    @Test
    fun `saveWallpaperState handles extreme scale values`() = runTest {
        // Guards against silent clamping (e.g. a future min/max validator).
        // 0.25f and 8.0f are both exactly representable as Float to avoid
        // precision noise in the round-trip via Double in the JSON path.
        manager.saveWallpaperState(WallpaperState.single("file:///data/x.jpg", scale = 0.25f))
        advanceUntilIdle()
        assertThat(manager.wallpaperState.first().layers.single().scale).isEqualTo(0.25f)

        manager.saveWallpaperState(WallpaperState.single("file:///data/x.jpg", scale = 8.0f))
        advanceUntilIdle()
        assertThat(manager.wallpaperState.first().layers.single().scale).isEqualTo(8.0f)
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
        assertThat(layer.translateX).isEqualTo(-999f)
        assertThat(layer.translateY).isEqualTo(-1500f)
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
        assertThat(emissions.size).isEqualTo(1)
        verify(exactly = 1) { fileManager.fileExists(any<Uri>()) }

        // Merge in an UNRELATED key (edit() keeps the wallpaper key intact).
        dataStore.edit { it[stringPreferencesKey("some_unrelated_setting")] = "x" }
        advanceUntilIdle()

        job.cancel()

        assertWithMessage("unrelated change must not re-emit wallpaper state").that(emissions.size).isEqualTo(1)
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

        assertWithMessage("a real wallpaper change must re-emit").that(emissions.size).isEqualTo(2)
        assertThat(emissions.last().layers.single().scale).isEqualTo(2.0f)
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

    private var shouldFailEdit = false
    private var shouldCancel = false

    /** Next [updateData] throws an IOException, like a failed DataStore write. */
    fun makeEditFail() {
        shouldFailEdit = true
    }

    /** Next [updateData] throws a CancellationException, like a cancelled write. */
    fun makeCancellable() {
        shouldCancel = true
    }

    override val data: Flow<Preferences> = state

    override suspend fun updateData(
        transform: suspend (t: Preferences) -> Preferences
    ): Preferences {
        when {
            shouldCancel -> throw kotlinx.coroutines.CancellationException("FakeDataStore: Simulated cancellation")
            shouldFailEdit -> throw java.io.IOException("FakeDataStore: Simulated edit failure")
        }
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
