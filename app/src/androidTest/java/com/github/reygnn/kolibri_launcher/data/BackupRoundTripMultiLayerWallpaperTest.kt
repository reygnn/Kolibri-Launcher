package com.github.reygnn.kolibri_launcher.data

import android.content.Context
import androidx.core.net.toUri
import com.github.reygnn.kolibri_launcher.domain.model.ImportOptions
import com.github.reygnn.kolibri_launcher.domain.model.ImportResult
import com.github.reygnn.kolibri_launcher.domain.model.WallpaperLayerState
import com.github.reygnn.kolibri_launcher.domain.model.WallpaperState
import com.github.reygnn.kolibri_launcher.domain.repository.BackupRepository
import com.github.reygnn.kolibri_launcher.domain.repository.WallpaperRepository
import com.google.common.truth.Truth.assertThat
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.android.testing.HiltAndroidRule
import dagger.hilt.android.testing.HiltAndroidTest
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import java.io.File
import javax.inject.Inject

/**
 * Why instrumented, and why this test exists at all: the single-layer
 * companion [BackupRoundTripWallpaperTest] only exercises the
 * `WallpaperState(imageUri = …)` branch. The MULTI-layer branch —
 * `WallpaperState.multiLayer(listOf(layer0, layer1, …))` — was, before
 * this test, uncovered by any test source set (JVM or instrumented). That
 * gap is exactly where two prior audit findings (AUDIT-3 #8 / #10, both
 * since fixed) lived, and it is the path a real user with a layered
 * wallpaper hits on every export→import. See AUDIT-8.md.
 *
 * The multi-layer path has extra machinery the single-layer path does not:
 *  - export: [BackupRepositoryImpl.writeZipBackup] walks
 *    `settings.wallpaperLayers`, mints a per-layer `wallpapers/layer_N.img`
 *    entry, and deduplicates layers that share the same underlying file via
 *    `entryByPath` (the AUDIT-3 #8 orphan-reference fix).
 *  - import: [BackupSerializer.resolveZipImages] maps each layer's
 *    `imageFileName` back to a freshly-extracted internal URI, and
 *    [BackupRepositoryImpl.importMultiLayerWallpaper] copies each layer to
 *    internal storage and rebuilds a multi-layer [WallpaperState], per-layer
 *    (a missing entry drops just that one layer, not the whole restore).
 *
 * The Robolectric companion ([BackupRepositoryImplWallpaperTest]) stubs
 * `WallpaperFileManager.copyToInternal` to echo its argument, so the actual
 * ZIP write, the byte copy into `filesDir/wallpapers/`, and the
 * imageFileName→URI resolution never run. On a real device every layer's
 * bytes have to physically survive the ZIP round-trip and each layer's
 * transform metadata (scale/translate/alpha/blend/visibility/label) has to
 * come back intact.
 */
@HiltAndroidTest
class BackupRoundTripMultiLayerWallpaperTest {

    @get:Rule(order = 0) val hiltRule = HiltAndroidRule(this)

    @Inject lateinit var backup: BackupRepository
    @Inject lateinit var wallpaper: WallpaperRepository
    @Inject lateinit var fileManager: WallpaperFileManager
    @Inject @ApplicationContext lateinit var context: Context

    private lateinit var backupFile: File

    /**
     * Distinct, stable, unmistakeably non-zero payloads per layer. Real PNG
     * validity is irrelevant — WallpaperFileManager copies bytes opaquely;
     * distinct bytes let us prove each restored layer points at ITS OWN
     * image and layers were not cross-wired during the index-based
     * `layer_N.img` round-trip.
     */
    private val layer0Bytes: ByteArray = ByteArray(2048) { i -> ((i * 31 + 7) and 0xFF).toByte() }
    private val layer1Bytes: ByteArray = ByteArray(2048) { i -> ((i * 17 + 101) and 0xFF).toByte() }

    @Before fun setUp() {
        hiltRule.inject()
        backupFile = File(context.cacheDir, "test-multilayer-backup-${System.nanoTime()}.zip")
            .also { it.parentFile?.mkdirs(); it.delete() }
    }

    /**
     * Copies [bytes] into a fresh cache file and then into the launcher's
     * internal wallpapers/ dir (mirroring production's wallpaper-pick flow),
     * returning the internal `file://` URI string. The export-side
     * `resolveToLocalFile` only embeds `file://` URIs into the ZIP, so the
     * layer state MUST reference an internal file, not a content:// URI.
     */
    private suspend fun seedInternalLayerImage(bytes: ByteArray, tag: String): String {
        val seed = File(context.cacheDir, "seed-$tag-${System.nanoTime()}.png")
            .also { it.parentFile?.mkdirs(); it.delete(); it.writeBytes(bytes) }
        val internalUri = fileManager.copyToInternal(seed.toUri())
            ?: error("copyToInternal returned null — internal storage must be writable for this test")
        return internalUri.toString()
    }

    @Test
    fun saveAndLoad_multiLayerWallpaper_roundTripsEveryLayer() = runBlocking {
        // ── ARRANGE: two layers, distinct images, distinct non-default
        // transform + blend/visibility/label metadata so a dropped OR
        // cross-wired layer is unmissable. ─────────────────────────────────
        val layer0 = WallpaperLayerState(
            imageUri = seedInternalLayerImage(layer0Bytes, "l0"),
            scale = 1.4f,
            translateX = 12.5f,
            translateY = -34.0f,
            alpha = 0.75f,
            blendModeName = "MULTIPLY",
            isVisible = true,
            label = "Base",
        )
        val layer1 = WallpaperLayerState(
            imageUri = seedInternalLayerImage(layer1Bytes, "l1"),
            scale = 2.1f,
            translateX = -8.0f,
            translateY = 44.5f,
            alpha = 0.5f,
            blendModeName = "SCREEN",
            isVisible = false,
            label = "Overlay",
        )
        wallpaper.saveWallpaperState(WallpaperState.multiLayer(listOf(layer0, layer1)))

        // ── SANITY: the multi-layer state must survive the WallpaperRepository
        // read path before backup runs. If it collapsed to single-layer or
        // NONE, the export would embed the wrong thing and the final asserts
        // would fail with a misleading message. Pin the failure here instead.
        val sanity = wallpaper.wallpaperState.first()
        assertThat(sanity.isMultiLayer).isTrue()
        assertThat(sanity.layers).hasSize(2)

        // ── ACT 1: save backup ─────────────────────────────────────────────
        val saved = withTimeout(15_000) { backup.saveBackupToFile(backupFile.toUri().toString()) }
        assertThat(saved).isTrue()

        // ── ARRANGE 2: wipe to prove restore actually brings layers back ────
        wallpaper.clearWallpaper()
        assertThat(wallpaper.wallpaperState.first().hasWallpaper).isFalse()

        // ── ACT 2: load backup (wallpaper rides under importThemeSettings) ──
        val result = withTimeout(15_000) {
            backup.loadBackupFromFile(
                backupFile.toUri().toString(),
                ImportOptions(importThemeSettings = true),
            )
        }
        assertThat(result).isInstanceOf(ImportResult.Success::class.java)

        // ── ASSERT: both layers restored, in order, none silently dropped ──
        val restored = wallpaper.wallpaperState.first()
        assertThat(restored.isMultiLayer).isTrue()
        assertThat(restored.layers).hasSize(2)

        val r0 = restored.layers[0]
        val r1 = restored.layers[1]

        // ── ASSERT: per-layer transform + blend metadata round-tripped ─────
        assertThat(r0.scale).isEqualTo(1.4f)
        assertThat(r0.translateX).isEqualTo(12.5f)
        assertThat(r0.translateY).isEqualTo(-34.0f)
        assertThat(r0.alpha).isEqualTo(0.75f)
        assertThat(r0.blendModeName).isEqualTo("MULTIPLY")
        assertThat(r0.isVisible).isTrue()
        assertThat(r0.label).isEqualTo("Base")

        assertThat(r1.scale).isEqualTo(2.1f)
        assertThat(r1.translateX).isEqualTo(-8.0f)
        assertThat(r1.translateY).isEqualTo(44.5f)
        assertThat(r1.alpha).isEqualTo(0.5f)
        assertThat(r1.blendModeName).isEqualTo("SCREEN")
        assertThat(r1.isVisible).isFalse()
        assertThat(r1.label).isEqualTo("Overlay")

        // ── ASSERT: each layer's bytes survived AND were not cross-wired ───
        // (layer 0 must carry layer0Bytes, layer 1 must carry layer1Bytes —
        // the index-based layer_N.img naming makes a swap plausible if the
        // resolve step ever mismatched entries).
        assertThat(readLayerBytes(r0)).isEqualTo(layer0Bytes)
        assertThat(readLayerBytes(r1)).isEqualTo(layer1Bytes)
    }

    /**
     * The AUDIT-3 #8 regression case: two layers referencing the SAME
     * underlying file. The export dedup ([writeZipBackup]'s `entryByPath`)
     * must write ONE image entry and stamp both layers with it — never mint
     * a second `layer_N.img` that is never written (the orphan reference the
     * importer couldn't resolve, which used to silently drop the second
     * layer). Both layers must come back with the shared bytes and their own
     * distinct transforms.
     */
    @Test
    fun saveAndLoad_layersSharingOneImage_bothSurvive() = runBlocking {
        val sharedUri = seedInternalLayerImage(layer0Bytes, "shared")
        val layer0 = WallpaperLayerState(imageUri = sharedUri, scale = 1.2f, label = "First")
        val layer1 = WallpaperLayerState(imageUri = sharedUri, scale = 3.0f, label = "Second")
        wallpaper.saveWallpaperState(WallpaperState.multiLayer(listOf(layer0, layer1)))

        val saved = withTimeout(15_000) { backup.saveBackupToFile(backupFile.toUri().toString()) }
        assertThat(saved).isTrue()

        wallpaper.clearWallpaper()
        assertThat(wallpaper.wallpaperState.first().hasWallpaper).isFalse()

        val result = withTimeout(15_000) {
            backup.loadBackupFromFile(
                backupFile.toUri().toString(),
                ImportOptions(importThemeSettings = true),
            )
        }
        assertThat(result).isInstanceOf(ImportResult.Success::class.java)

        val restored = wallpaper.wallpaperState.first()
        assertThat(restored.isMultiLayer).isTrue()
        // The core AUDIT-3 #8 assertion: the second (shared-file) layer is
        // NOT dropped.
        assertThat(restored.layers).hasSize(2)
        assertThat(restored.layers[0].label).isEqualTo("First")
        assertThat(restored.layers[1].label).isEqualTo("Second")
        assertThat(restored.layers[0].scale).isEqualTo(1.2f)
        assertThat(restored.layers[1].scale).isEqualTo(3.0f)
        // Both restored layers carry the shared image's bytes.
        assertThat(readLayerBytes(restored.layers[0])).isEqualTo(layer0Bytes)
        assertThat(readLayerBytes(restored.layers[1])).isEqualTo(layer0Bytes)
    }

    /**
     * Reads the on-disk bytes a restored layer points at. The import path
     * writes a fresh internal `file://` file per layer via
     * `WallpaperFileManager.copyFromInputStream` / `copyToInternal`, so the
     * restored URI is a new local file that must exist and contain the
     * original bytes.
     */
    private fun readLayerBytes(layer: WallpaperLayerState): ByteArray {
        val uriString = layer.imageUri
        assertThat(uriString).isNotNull()
        val uri = uriString!!.toUri()
        assertThat(uri.scheme).isEqualTo("file")
        val file = File(uri.path!!)
        assertThat(file.exists()).isTrue()
        return file.readBytes()
    }
}
