package com.github.reygnn.kolibri_launcher.data

import com.github.reygnn.kolibri_launcher.domain.model.BackupData
import com.github.reygnn.kolibri_launcher.domain.model.LauncherSettings
import com.github.reygnn.kolibri_launcher.domain.model.WallpaperLayerBackup
import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * JVM unit tests for the two PURE ZIP-restore helpers on [BackupSerializer]:
 * [BackupSerializer.resolveZipImages] and [BackupSerializer.buildPreview].
 *
 * These sit on the CURRENT backup format (ZIP), yet the RC edge-case audit
 * found the whole ZIP → extract → resolve → restore pipeline was exercised only
 * via the legacy `importFromJson` path — `resolveZipImages` / `buildPreview` /
 * `previewBackup` had zero test references. resolveZipImages and buildPreview
 * are pure data transforms (no org.json, no Android), so they pin on the plain
 * JVM without Robolectric. This locks the stale-URI fallback edge in particular:
 * a layer whose ZIP entry is missing keeps its original imageUri, which is the
 * value that then feeds the inaccessible-restore path in BackupRepositoryImpl.
 */
class BackupSerializerZipResolveTest {

    private val serializer = BackupSerializer()

    private fun backupWith(settings: LauncherSettings) =
        BackupData(version = "test", timestamp = 1L, settings = settings)

    // ----- resolveZipImages: multi-layer -----

    @Test
    fun `resolveZipImages replaces each layer imageFileName with the extracted internal URI`() {
        val backup = backupWith(
            LauncherSettings(
                wallpaperLayers = listOf(
                    WallpaperLayerBackup(id = "l0", imageFileName = "wallpapers/0.img"),
                    WallpaperLayerBackup(id = "l1", imageFileName = "wallpapers/1.img"),
                ),
            ),
        )
        val extracted = mapOf(
            "wallpapers/0.img" to "file:///internal/0.img",
            "wallpapers/1.img" to "file:///internal/1.img",
        )

        val resolved = serializer.resolveZipImages(backup, extracted)

        assertThat(resolved.settings.wallpaperLayers.map { it.imageUri })
            .containsExactly("file:///internal/0.img", "file:///internal/1.img")
            .inOrder()
    }

    @Test
    fun `resolveZipImages keeps the stale imageUri when a layer's ZIP entry is missing`() {
        // Missing/corrupt ZIP entry: the layer keeps its stale JSON imageUri
        // rather than resolving to an internal file. This is the value that then
        // feeds BackupRepositoryImpl's inaccessible-restore path.
        val backup = backupWith(
            LauncherSettings(
                wallpaperLayers = listOf(
                    WallpaperLayerBackup(
                        id = "l0",
                        imageUri = "content://stale/original",
                        imageFileName = "wallpapers/0.img",
                    ),
                ),
            ),
        )

        val resolved = serializer.resolveZipImages(backup, extractedImages = emptyMap())

        assertThat(resolved.settings.wallpaperLayers.single().imageUri)
            .isEqualTo("content://stale/original")
    }

    // ----- resolveZipImages: single-image field -----

    @Test
    fun `resolveZipImages resolves the single wallpaperImageFileName to the extracted URI`() {
        val backup = backupWith(
            LauncherSettings(
                wallpaperImageFileName = "wallpapers/single.img",
                wallpaperUri = "content://stale/single",
            ),
        )
        val extracted = mapOf("wallpapers/single.img" to "file:///internal/single.img")

        val resolved = serializer.resolveZipImages(backup, extracted)

        assertThat(resolved.settings.wallpaperUri).isEqualTo("file:///internal/single.img")
    }

    @Test
    fun `resolveZipImages falls back to the stale wallpaperUri when the single entry is missing`() {
        val backup = backupWith(
            LauncherSettings(
                wallpaperImageFileName = "wallpapers/single.img",
                wallpaperUri = "content://stale/single",
            ),
        )

        val resolved = serializer.resolveZipImages(backup, extractedImages = emptyMap())

        assertThat(resolved.settings.wallpaperUri).isEqualTo("content://stale/single")
    }

    // ----- buildPreview -----

    @Test
    fun `buildPreview counts and flags reflect the parsed backup`() {
        val backup = backupWith(
            LauncherSettings(
                favoriteComponents = setOf("com.a/A", "com.b/B"),
                favoritesOrder = listOf("com.b/B", "com.a/A"),
                hiddenComponents = setOf("com.c/C"),
                customAppNames = mapOf("com.a" to "Alpha"),
                swipeLeftApp = "com.a/A",
                wallpaperLayers = listOf(
                    WallpaperLayerBackup(id = "l0", imageFileName = "wallpapers/0.img"),
                ),
            ),
        )

        val preview = serializer.buildPreview(backup)

        assertThat(preview.favoriteCount).isEqualTo(2)
        assertThat(preview.orderCount).isEqualTo(2)
        assertThat(preview.hiddenCount).isEqualTo(1)
        assertThat(preview.customNamesCount).isEqualTo(1)
        assertThat(preview.hasSwipeLeft).isTrue()
        assertThat(preview.hasSwipeRight).isFalse()
        assertThat(preview.hasWallpaper).isTrue()
        assertThat(preview.wallpaperLayerCount).isEqualTo(1)
    }

    @Test
    fun `buildPreview reports hasWallpaper for a single-layer file-name-only backup`() {
        val backup = backupWith(
            LauncherSettings(wallpaperImageFileName = "wallpapers/single.img"),
        )

        val preview = serializer.buildPreview(backup)

        assertThat(preview.hasWallpaper).isTrue()
        assertThat(preview.wallpaperLayerCount).isEqualTo(0)
    }
}
