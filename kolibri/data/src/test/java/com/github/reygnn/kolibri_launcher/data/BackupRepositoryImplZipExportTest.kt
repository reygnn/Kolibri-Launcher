package com.github.reygnn.kolibri_launcher.data
import com.github.reygnn.kolibri_launcher.domain.model.LauncherSettings
import com.github.reygnn.launcher.feature.backup.container.ContainerManifestCodec
import com.github.reygnn.launcher.feature.backup.container.ContainerManifest
import com.github.reygnn.launcher.common.data.wallpaper.WallpaperFileManager

import android.content.ContentResolver
import android.content.Context
import android.net.Uri
import com.github.reygnn.launcher.core.wallpaper.WallpaperLayerState
import com.github.reygnn.launcher.core.wallpaper.WallpaperState
import com.github.reygnn.kolibri_launcher.fakes.FakeCustomNamesRepository
import com.github.reygnn.kolibri_launcher.fakes.FakeFavoritesOrderRepository
import com.github.reygnn.kolibri_launcher.fakes.FakeFavoritesRepository
import com.github.reygnn.kolibri_launcher.fakes.FakeHiddenAppsRepository
import com.github.reygnn.launcher.core.installedapps.FakeInstalledAppsRepository
import com.github.reygnn.kolibri_launcher.fakes.FakeSettingsRepository
import com.github.reygnn.kolibri_launcher.fakes.FakeSwipeActionsRepository
import com.github.reygnn.launcher.core.wallpaper.FakeWallpaperRepository
import com.github.reygnn.kolibri_launcher.rule.TimberRule
import com.google.common.truth.Truth.assertThat
import io.mockk.MockKAnnotations
import io.mockk.every
import io.mockk.impl.annotations.MockK
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.BeforeClass
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.zip.ZipInputStream

/**
 * ZIP-EXPORT embedding rules (AUDIT-8 §3 tail #2): `writeZipBackup` can only
 * embed image bytes it can resolve to a local file — i.e. `file://` URIs
 * pointing into internal storage. A `content://` layer URI (e.g. one still
 * referencing the source picker document) cannot be resolved to a local file,
 * so it is NOT embedded and carries no `imageFileName`. On a real round-trip
 * that layer's image is therefore not portable — which is exactly why the
 * import side now surfaces the drop to the user (see the AUDIT-8 #1 warning
 * on the `feature/backup-import-wallpaper-warning` branch).
 *
 * This test pins the EXPORT half of that contract cheaply on the JVM: drive
 * `saveBackupToFile` with a mocked `openOutputStream` capturing the archive bytes,
 * then inspect the E5a container (SPEC_NYX_REWRITE 2a-5): `manifest.json` first, one
 * `blobs/<sha256>` entry per embedded image, the settings in the `kolibri.backup`
 * section. Robolectric for `Uri.parse()`.
 */
@RunWith(RobolectricTestRunner::class)
class BackupRepositoryImplZipExportTest {

    companion object {
        @JvmStatic
        @BeforeClass
        fun checkEnvironment() {
            assumeTrue("Skipping Robolectric tests in GitHub CI", System.getenv("CI") == null)
        }
    }

    @get:Rule
    val timberRule = TimberRule()

    @get:Rule
    val tempFolder = TemporaryFolder()

    private lateinit var fakeWallpaperRepo: FakeWallpaperRepository

    @MockK private lateinit var context: Context
    @MockK private lateinit var contentResolver: ContentResolver
    @MockK private lateinit var wallpaperFileManager: WallpaperFileManager

    private lateinit var backupManager: BackupRepositoryImpl

    /** Captures the bytes the repository writes to the (mocked) output URI. */
    private val zipBytes = ByteArrayOutputStream()

    private val json = Json { ignoreUnknownKeys = true }

    @Before
    fun setUp() {
        MockKAnnotations.init(this)
        fakeWallpaperRepo = FakeWallpaperRepository()

        every { context.contentResolver } returns contentResolver
        every { contentResolver.openOutputStream(any()) } returns zipBytes
        // Note: the export reads image bytes via File.inputStream() and never calls
        // copyToInternal — that is an import-side concern — so wallpaperFileManager
        // needs no stubbing here.

        backupManager = BackupRepositoryImplTestFactory.create(
            favoritesRepository = FakeFavoritesRepository(),
            favoritesOrderRepository = FakeFavoritesOrderRepository(),
            hiddenAppsRepository = FakeHiddenAppsRepository(),
            customNamesRepository = FakeCustomNamesRepository(),
            installedAppsRepository = FakeInstalledAppsRepository(),
            swipeActionsRepository = FakeSwipeActionsRepository(),
            settingsRepository = FakeSettingsRepository(),
            wallpaperRepository = fakeWallpaperRepo,
            wallpaperFileManager = wallpaperFileManager,
            context = context,
        )
    }

    @Test
    fun `content-uri layer is not embedded while file-uri layer is`() = runTest {
        // A real on-disk file for the file:// layer — the only kind the export
        // can embed (it reads bytes via File.inputStream()).
        val realImage = tempFolder.newFile("layer.img").apply {
            writeBytes(ByteArray(512) { (it and 0xFF).toByte() })
        }
        fakeWallpaperRepo.currentState = WallpaperState.multiLayer(
            listOf(
                WallpaperLayerState(imageUri = "content://media/external/images/1"),
                WallpaperLayerState(imageUri = Uri.fromFile(realImage).toString()),
            )
        )

        val saved = backupManager.saveBackupToFile("content://out/backup.zip")
        assertThat(saved).isTrue()

        val bytes = zipBytes.toByteArray()
        val entries = readZipEntryNames(bytes)
        assertThat(entries.first()).isEqualTo("manifest.json")
        val blobEntries = entries.filter { it.startsWith("blobs/") }
        // Exactly one embedded image — the file:// layer. The content:// layer
        // is not embeddable, so no blob is created for it.
        assertThat(blobEntries).hasSize(1)
        val hash = blobEntries.single().removePrefix("blobs/")
        val manifest = readManifest(bytes)
        assertThat(manifest.blobs.map { it.sha256 }).containsExactly(hash)

        val layers = settingsOf(manifest).wallpaperLayers
        assertThat(layers).hasSize(2)
        // content:// layer: no blob reference (nothing was written).
        assertThat(layers[0].imageUri).isEqualTo("content://media/external/images/1")
        assertThat(layers[0].imageFileName).isNull()
        // file:// layer: references its blob by hash; the path is not exported —
        // the blob is the source of truth.
        assertThat(layers[1].imageFileName).isEqualTo(hash)
        assertThat(layers[1].imageUri).isNull()
    }

    @Test
    fun `single-layer content-uri wallpaper is not embedded`() = runTest {
        fakeWallpaperRepo.currentState =
            WallpaperState.single("content://media/external/images/9", scale = 1.5f)

        val saved = backupManager.saveBackupToFile("content://out/backup.zip")
        assertThat(saved).isTrue()

        val bytes = zipBytes.toByteArray()
        assertThat(readZipEntryNames(bytes).none { it.startsWith("blobs/") }).isTrue()

        val settings = settingsOf(readManifest(bytes))
        // The URI is preserved in the section, but no blob reference is stamped.
        assertThat(settings.wallpaperUri).isEqualTo("content://media/external/images/9")
        assertThat(settings.wallpaperImageFileName).isNull()
    }

    private fun readZipEntryNames(bytes: ByteArray): List<String> {
        val names = mutableListOf<String>()
        ZipInputStream(ByteArrayInputStream(bytes)).use { zip ->
            var entry = zip.nextEntry
            while (entry != null) {
                names.add(entry.name)
                zip.closeEntry()
                entry = zip.nextEntry
            }
        }
        return names
    }

    private fun readManifest(bytes: ByteArray): ContainerManifest {
        ZipInputStream(ByteArrayInputStream(bytes)).use { zip ->
            val first = zip.nextEntry ?: error("empty archive")
            check(first.name == "manifest.json") { "manifest.json must be the first entry" }
            return checkNotNull(ContainerManifestCodec.decode(zip.readBytes())) { "manifest unreadable" }
        }
    }

    private fun settingsOf(manifest: ContainerManifest): LauncherSettings =
        json.decodeFromJsonElement(LauncherSettings.serializer(), manifest.sections.getValue("kolibri.backup").data)
}
