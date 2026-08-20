package com.github.reygnn.kolibri_launcher.data

import android.content.ContentResolver
import android.content.Context
import android.net.Uri
import com.github.reygnn.kolibri_launcher.domain.model.AppInfo
import com.github.reygnn.kolibri_launcher.domain.model.ImportOptions
import com.github.reygnn.kolibri_launcher.domain.model.ImportResult
import com.github.reygnn.kolibri_launcher.domain.model.WallpaperLayerState
import com.github.reygnn.kolibri_launcher.domain.model.WallpaperState
import com.github.reygnn.kolibri_launcher.fakes.FakeCustomNamesRepository
import com.github.reygnn.kolibri_launcher.fakes.FakeFavoritesOrderRepository
import com.github.reygnn.kolibri_launcher.fakes.FakeFavoritesRepository
import com.github.reygnn.kolibri_launcher.fakes.FakeHiddenAppsRepository
import com.github.reygnn.kolibri_launcher.fakes.FakeInstalledAppsRepository
import com.github.reygnn.kolibri_launcher.fakes.FakeSettingsRepository
import com.github.reygnn.kolibri_launcher.fakes.FakeSwipeActionsRepository
import com.github.reygnn.kolibri_launcher.fakes.FakeWallpaperRepository
import com.github.reygnn.kolibri_launcher.rule.TimberRule
import com.google.common.truth.Truth.assertThat
import io.mockk.MockKAnnotations
import io.mockk.coEvery
import io.mockk.every
import io.mockk.impl.annotations.MockK
import kotlinx.coroutines.test.runTest
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.BeforeClass
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.ByteArrayInputStream

/**
 * Multi-layer wallpaper IMPORT edge cases (AUDIT-8 §3 tail #5): the
 * degradation behaviour of [BackupRepositoryImpl.importMultiLayerWallpaper]
 * when some layers carry no image.
 *
 * The instrumented `BackupRoundTripMultiLayerWallpaperTest` proves the happy
 * path (all layers have real files); this covers what happens when a layer's
 * `imageUri` is null/blank — a hand-edited backup, or a transform-only layer.
 * The importer skips such a layer (`uriString.isNullOrBlank()` → continue),
 * and if EVERY layer is skipped no wallpaper is restored at all.
 *
 * Robolectric for `Uri.parse()`; `copyToInternal` echoes its argument and
 * every URI reads as accessible, so surviving layers keep their URI.
 */
@RunWith(RobolectricTestRunner::class)
class BackupRepositoryImplMultiLayerImportTest {

    companion object {
        @JvmStatic
        @BeforeClass
        fun checkEnvironment() {
            assumeTrue("Skipping Robolectric tests in GitHub CI", System.getenv("CI") == null)
        }
    }

    @get:Rule
    val timberRule = TimberRule()

    private lateinit var fakeFavoritesRepo: FakeFavoritesRepository
    private lateinit var fakeFavoritesOrderRepo: FakeFavoritesOrderRepository
    private lateinit var fakeHiddenAppsRepo: FakeHiddenAppsRepository
    private lateinit var fakeCustomNamesRepo: FakeCustomNamesRepository
    private lateinit var fakeInstalledAppsRepo: FakeInstalledAppsRepository
    private lateinit var fakeSwipeActionsRepo: FakeSwipeActionsRepository
    private lateinit var fakeSettingsRepo: FakeSettingsRepository
    private lateinit var fakeWallpaperRepo: FakeWallpaperRepository

    @MockK private lateinit var context: Context
    @MockK private lateinit var contentResolver: ContentResolver
    @MockK private lateinit var wallpaperFileManager: WallpaperFileManager

    private lateinit var backupManager: BackupRepositoryImpl

    private val themeOnly = ImportOptions(importThemeSettings = true)

    @Before
    fun setUp() {
        MockKAnnotations.init(this)

        fakeFavoritesRepo = FakeFavoritesRepository()
        fakeFavoritesOrderRepo = FakeFavoritesOrderRepository()
        fakeHiddenAppsRepo = FakeHiddenAppsRepository()
        fakeCustomNamesRepo = FakeCustomNamesRepository()
        fakeInstalledAppsRepo = FakeInstalledAppsRepository()
        fakeSwipeActionsRepo = FakeSwipeActionsRepository()
        fakeSettingsRepo = FakeSettingsRepository()
        fakeWallpaperRepo = FakeWallpaperRepository()

        // Cold-path gate: performImport waits for a non-empty InstalledApps
        // emission (see BackupRepositoryImplWallpaperTest).
        fakeInstalledAppsRepo.installedApps = listOf(
            AppInfo(
                originalName = "Sentinel",
                displayName = "Sentinel",
                packageName = "kolibri.test.sentinel",
                className = "kolibri.test.sentinel.Main",
            )
        )

        every { context.contentResolver } returns contentResolver
        every { contentResolver.openInputStream(any()) } returns ByteArrayInputStream(ByteArray(1))
        coEvery { wallpaperFileManager.copyToInternal(any()) } answers { firstArg<Uri>() }

        backupManager = BackupRepositoryImplTestFactory.create(
            favoritesRepository = fakeFavoritesRepo,
            favoritesOrderRepository = fakeFavoritesOrderRepo,
            hiddenAppsRepository = fakeHiddenAppsRepo,
            customNamesRepository = fakeCustomNamesRepo,
            installedAppsRepository = fakeInstalledAppsRepo,
            swipeActionsRepository = fakeSwipeActionsRepo,
            settingsRepository = fakeSettingsRepo,
            wallpaperRepository = fakeWallpaperRepo,
            wallpaperFileManager = wallpaperFileManager,
            context = context,
        )
    }

    @Test
    fun `metadata-only layer is skipped, image-bearing layer survives`() = runTest {
        // Layer 0 has an image; layer 1 is transform-only (no URI). Export via
        // plain JSON, then re-import (Phase 7 clears wallpaper, then restores).
        fakeWallpaperRepo.currentState = WallpaperState.multiLayer(
            listOf(
                WallpaperLayerState(imageUri = "content://img/0", scale = 1.3f),
                WallpaperLayerState(imageUri = null, scale = 2.0f),
            )
        )

        val json = backupManager.exportToJson()
        val result = backupManager.importFromJson(json, themeOnly)

        assertThat(result).isInstanceOf(ImportResult.Success::class.java)
        // The skipped layer had NO image (metadata-only), so it is not counted
        // as a lost image — dropped stays 0 and no "image no longer available"
        // warning fires for it.
        assertThat((result as ImportResult.Success).droppedWallpaperLayers).isEqualTo(0)
        val restored = fakeWallpaperRepo.currentState
        assertThat(restored.layers).hasSize(1)
        assertThat(restored.layers[0].imageUri).isEqualTo("content://img/0")
        assertThat(restored.layers[0].scale).isEqualTo(1.3f)
    }

    @Test
    fun `all-image-less-layer backup leaves the current wallpaper untouched`() = runTest {
        // Build an all-image-less backup by exporting a metadata-only state…
        fakeWallpaperRepo.currentState = WallpaperState.multiLayer(
            listOf(
                WallpaperLayerState(imageUri = null, scale = 1.5f),
                WallpaperLayerState(imageUri = null, scale = 2.5f),
            )
        )
        val json = backupManager.exportToJson()

        // …then give the user a REAL wallpaper that the import must NOT wipe.
        // The backup carries no actual image, so — like every other theme
        // field's skip-on-null — the wallpaper is left alone (Review 0.99.114
        // #1). Previously the unconditional clear silently wiped it.
        val existing = WallpaperState.single("content://existing/wp", scale = 1.3f)
        fakeWallpaperRepo.currentState = existing

        val result = backupManager.importFromJson(json, themeOnly)

        assertThat(result).isInstanceOf(ImportResult.Success::class.java)
        // Nothing was a lost image → dropped 0, and the wallpaper is preserved.
        assertThat((result as ImportResult.Success).droppedWallpaperLayers).isEqualTo(0)
        assertThat(fakeWallpaperRepo.currentState).isEqualTo(existing)
    }

    @Test
    fun `both image-bearing layers survive - JVM sanity for the happy path`() = runTest {
        // Fast JVM mirror of the instrumented happy-path round-trip: no bytes,
        // but proves the two-layer JSON round-trip preserves count + order +
        // per-layer transforms through parse + restore.
        fakeWallpaperRepo.currentState = WallpaperState.multiLayer(
            listOf(
                WallpaperLayerState(imageUri = "content://img/0", scale = 1.1f),
                WallpaperLayerState(imageUri = "content://img/1", scale = 2.2f),
            )
        )

        val json = backupManager.exportToJson()
        val result = backupManager.importFromJson(json, themeOnly)

        assertThat(result).isInstanceOf(ImportResult.Success::class.java)
        // Clean restore → nothing dropped.
        assertThat((result as ImportResult.Success).droppedWallpaperLayers).isEqualTo(0)
        val restored = fakeWallpaperRepo.currentState
        assertThat(restored.layers).hasSize(2)
        assertThat(restored.layers.map { it.imageUri })
            .containsExactly("content://img/0", "content://img/1").inOrder()
        assertThat(restored.layers[0].scale).isEqualTo(1.1f)
        assertThat(restored.layers[1].scale).isEqualTo(2.2f)
    }

    @Test
    fun `image-bearing layer that is inaccessible counts as dropped`() = runTest {
        // The real #1 UX case: a layer that HAD an image whose source is no
        // longer reachable. Layer 0 restores; layer 1's URI reads as
        // inaccessible → it is dropped AND reported (unlike a metadata-only
        // layer, which is not).
        every {
            contentResolver.openInputStream(match { it.toString().contains("gone") })
        } returns null

        fakeWallpaperRepo.currentState = WallpaperState.multiLayer(
            listOf(
                WallpaperLayerState(imageUri = "content://img/ok"),
                WallpaperLayerState(imageUri = "content://img/gone"),
            )
        )

        val json = backupManager.exportToJson()
        val result = backupManager.importFromJson(json, themeOnly)

        assertThat(result).isInstanceOf(ImportResult.Success::class.java)
        assertThat((result as ImportResult.Success).droppedWallpaperLayers).isEqualTo(1)
        val restored = fakeWallpaperRepo.currentState
        assertThat(restored.layers.map { it.imageUri }).containsExactly("content://img/ok")
    }
}
