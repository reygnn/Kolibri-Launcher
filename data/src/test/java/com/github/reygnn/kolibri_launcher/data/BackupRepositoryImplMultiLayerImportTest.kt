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
                WallpaperLayerState(imageUri = "content://img/0", scale = 1.3f, label = "Has image"),
                WallpaperLayerState(imageUri = null, scale = 2.0f, label = "Metadata only"),
            )
        )

        val json = backupManager.exportToJson()
        val result = backupManager.importFromJson(json, themeOnly)

        assertThat(result).isInstanceOf(ImportResult.Success::class.java)
        val restored = fakeWallpaperRepo.currentState
        assertThat(restored.isMultiLayer).isTrue()
        assertThat(restored.layers).hasSize(1)
        assertThat(restored.layers[0].label).isEqualTo("Has image")
        assertThat(restored.layers[0].scale).isEqualTo(1.3f)
    }

    @Test
    fun `all-metadata-only layers - wallpaper is not restored`() = runTest {
        fakeWallpaperRepo.currentState = WallpaperState.multiLayer(
            listOf(
                WallpaperLayerState(imageUri = null, scale = 1.5f, label = "A"),
                WallpaperLayerState(imageUri = null, scale = 2.5f, label = "B"),
            )
        )

        val json = backupManager.exportToJson()
        val result = backupManager.importFromJson(json, themeOnly)

        // Import still succeeds overall; the wallpaper simply ends up cleared
        // (Phase 7 clears, restore finds no valid layer, leaves it cleared).
        assertThat(result).isInstanceOf(ImportResult.Success::class.java)
        val restored = fakeWallpaperRepo.currentState
        assertThat(restored.isMultiLayer).isFalse()
        assertThat(restored.hasWallpaper).isFalse()
    }

    @Test
    fun `both image-bearing layers survive - JVM sanity for the happy path`() = runTest {
        // Fast JVM mirror of the instrumented happy-path round-trip: no bytes,
        // but proves the two-layer JSON round-trip preserves count + order +
        // per-layer transforms through parse + restore.
        fakeWallpaperRepo.currentState = WallpaperState.multiLayer(
            listOf(
                WallpaperLayerState(imageUri = "content://img/0", scale = 1.1f, label = "First"),
                WallpaperLayerState(imageUri = "content://img/1", scale = 2.2f, label = "Second"),
            )
        )

        val json = backupManager.exportToJson()
        val result = backupManager.importFromJson(json, themeOnly)

        assertThat(result).isInstanceOf(ImportResult.Success::class.java)
        val restored = fakeWallpaperRepo.currentState
        assertThat(restored.layers).hasSize(2)
        assertThat(restored.layers.map { it.label }).containsExactly("First", "Second").inOrder()
        assertThat(restored.layers[0].scale).isEqualTo(1.1f)
        assertThat(restored.layers[1].scale).isEqualTo(2.2f)
    }
}
