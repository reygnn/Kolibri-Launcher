package com.github.reygnn.kolibri_launcher.data

import com.github.reygnn.kolibri_launcher.domain.model.WallpaperState
import com.github.reygnn.kolibri_launcher.domain.repository.WallpaperRepository
import com.github.reygnn.kolibri_launcher.rule.MainDispatcherRule
import com.github.reygnn.kolibri_launcher.rule.TimberRule
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

/**
 * ============================================================================
 * WALLPAPER REPOSITORY — CONTRACT TEST
 * ============================================================================
 *
 * See [FavoritesRepositoryContract] for background and conventions.
 *
 * The [WallpaperRepository] interface is small but has one important
 * consistency property between its two read paths:
 *
 *   `getWallpaperStateSync()` and `wallpaperState.first()` must return the
 *   same value. The impl implements this by having `getWallpaperStateSync`
 *   call `wallpaperState.first()` internally. The fake implements it by
 *   pointing both at the same `MutableStateFlow.value`. If someone adds a
 *   cache layer that only one path uses, the matching contract test catches
 *   it.
 *
 * A wallpaper is always represented as a layer list now (a single image is a
 * one-element list). The contract exercises a single-image round-trip
 * (`WallpaperState.single(...)`) and the two-read-path consistency.
 *
 * KNOWN DIVERGENCES — likely red on the fake, deliberately NOT pinned:
 *
 *   1. **Non-file URI scheme** (e.g. `content://...`)
 *      - Impl: drops it on read with `scheme != "file"` → returns
 *        `WallpaperState.NONE`. Guards against SD-card remap and backup
 *        restore artifacts.
 *      - Fake: stores every URI unchanged.
 *
 *   2. **File not (any longer) on disk** (a `file://` URI without a backing
 *      file)
 *      - Impl: calls `WallpaperFileManager.fileExists(uri)` and returns
 *        `WallpaperState.NONE` on `false`.
 *      - Fake: has no concept of "file exists".
 *
 *   So the contract uses `file://` URIs and the impl test stubs
 *   `fileExists(any()) returns true`. The two drifts above are documented but
 *   NOT pinned as contract tests — declaring the fakes "broken" and forcing
 *   Robolectric would be overkill for behaviour almost nobody relies on.
 *
 * NOT IN CONTRACT (impl-specific):
 *   - Layer JSON serialization (an impl detail of the store).
 *   - DataStore IOException recovery.
 *   - URI-scheme and file-existence validation (see above).
 *
 * WHY ROBOLECTRIC:
 *   The contract builds real `android.net.Uri` instances via `String.toUri()`
 *   inside the impl. On plain JVM that throws, so every subclass must be
 *   annotated `@RunWith(RobolectricTestRunner::class)`. This is the only
 *   repository contract in the project with this setup; rationale is in each
 *   subclass KDoc.
 *
 * @see FakeWallpaperRepositoryContractTest
 * @see WallpaperRepositoryImplContractTest
 * ============================================================================
 */
@OptIn(ExperimentalCoroutinesApi::class)
abstract class WallpaperRepositoryContract {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    @get:Rule
    val timberRule = TimberRule()

    protected abstract fun createRepository(): WallpaperRepository

    // file:// URIs chosen because the impl resets content:// URIs to NONE
    // (see class KDoc, drift #1). This exercises the common base.
    private val testUri = "file:///data/wallpaper.jpg"
    private val testUri2 = "file:///data/other.jpg"

    /** The lone image URI of a single-image state, for round-trip assertions. */
    private val WallpaperState.singleImageUri: String?
        get() = layers.firstOrNull()?.imageUri

    // ---------- Fresh state ----------

    @Test
    fun `fresh repository emits WallpaperState NONE on flow`() = runTest {
        val repo = createRepository()
        assertEquals(WallpaperState.NONE, repo.wallpaperState.first())
    }

    @Test
    fun `fresh repository returns WallpaperState NONE on sync getter`() = runTest {
        val repo = createRepository()
        assertEquals(WallpaperState.NONE, repo.getWallpaperStateSync())
    }

    // ---------- saveWallpaperState ----------

    @Test
    fun `saveWallpaperState with imageUri persists imageUri`() = runTest {
        val repo = createRepository()
        repo.saveWallpaperState(WallpaperState.single(testUri))
        assertEquals(testUri, repo.wallpaperState.first().singleImageUri)
    }

    @Test
    fun `saveWallpaperState with imageUri and scale persists both`() = runTest {
        val repo = createRepository()
        repo.saveWallpaperState(WallpaperState.single(testUri, scale = 2.5f))
        val layer = repo.wallpaperState.first().layers.single()
        assertEquals(testUri, layer.imageUri)
        assertEquals(2.5f, layer.scale)
    }

    @Test
    fun `saveWallpaperState with imageUri and translation persists both`() = runTest {
        val repo = createRepository()
        repo.saveWallpaperState(
            WallpaperState.single(testUri, translateX = 100f, translateY = -50f)
        )
        val layer = repo.wallpaperState.first().layers.single()
        assertEquals(100f, layer.translateX)
        assertEquals(-50f, layer.translateY)
    }

    @Test
    fun `saveWallpaperState overwrites previous wallpaper`() = runTest {
        val repo = createRepository()
        repo.saveWallpaperState(WallpaperState.single(testUri, scale = 2.0f))
        repo.saveWallpaperState(WallpaperState.single(testUri2, scale = 1.5f))
        val layer = repo.wallpaperState.first().layers.single()
        assertEquals(testUri2, layer.imageUri)
        assertEquals(1.5f, layer.scale)
    }

    // ---------- Consistency: sync == flow.first() ----------

    /**
     * The most important contract property of this repository: both read paths
     * return the same value. If someone adds a cache layer that only one path
     * uses, this test catches it immediately.
     */
    @Test
    fun `getWallpaperStateSync returns same value as wallpaperState first`() = runTest {
        val repo = createRepository()
        repo.saveWallpaperState(WallpaperState.single(testUri, scale = 1.75f))

        val viaFlow = repo.wallpaperState.first()
        val viaSync = repo.getWallpaperStateSync()

        assertEquals(viaFlow, viaSync)
    }

    @Test
    fun `getWallpaperStateSync stays in sync after multiple saves`() = runTest {
        val repo = createRepository()
        repo.saveWallpaperState(WallpaperState.single(testUri, scale = 1.0f))
        repo.saveWallpaperState(WallpaperState.single(testUri2, scale = 2.0f))

        assertEquals(repo.wallpaperState.first(), repo.getWallpaperStateSync())
    }

    @Test
    fun `getWallpaperStateSync stays in sync after clearWallpaper`() = runTest {
        val repo = createRepository()
        repo.saveWallpaperState(WallpaperState.single(testUri))
        repo.clearWallpaper()

        assertEquals(repo.wallpaperState.first(), repo.getWallpaperStateSync())
    }

    // ---------- clearWallpaper ----------

    @Test
    fun `clearWallpaper resets state to NONE`() = runTest {
        val repo = createRepository()
        repo.saveWallpaperState(WallpaperState.single(testUri, scale = 2.0f))
        repo.clearWallpaper()
        assertEquals(WallpaperState.NONE, repo.wallpaperState.first())
    }

    @Test
    fun `clearWallpaper on fresh repository is safe`() = runTest {
        val repo = createRepository()
        repo.clearWallpaper()
        assertEquals(WallpaperState.NONE, repo.wallpaperState.first())
    }

    @Test
    fun `clearWallpaper can be called multiple times`() = runTest {
        val repo = createRepository()
        repo.saveWallpaperState(WallpaperState.single(testUri))
        repo.clearWallpaper()
        repo.clearWallpaper()
        assertEquals(WallpaperState.NONE, repo.wallpaperState.first())
    }

    // ---------- purgeRepository ----------

    @Test
    fun `purgeRepository resets state to NONE`() = runTest {
        val repo = createRepository()
        repo.saveWallpaperState(WallpaperState.single(testUri, scale = 2.0f))
        repo.purgeRepository()
        assertEquals(WallpaperState.NONE, repo.wallpaperState.first())
    }

    @Test
    fun `purgeRepository on fresh repository is safe`() = runTest {
        val repo = createRepository()
        repo.purgeRepository()
        assertEquals(WallpaperState.NONE, repo.wallpaperState.first())
    }
}
