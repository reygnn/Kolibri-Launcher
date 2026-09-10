package com.github.reygnn.kolibri_launcher.data

import android.content.ContentResolver
import android.content.Context
import android.net.Uri
import com.github.reygnn.kolibri_launcher.domain.model.BackupData
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
 * `saveBackupToFile` with a mocked `openOutputStream` capturing the ZIP bytes,
 * then inspect the archive entries + `backup.json`. Robolectric for
 * `Uri.parse()`.
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
        // Note: the export path (writeZipBackup) reads bytes via
        // File.inputStream() and never calls copyToInternal — that is an
        // import-side concern — so wallpaperFileManager needs no stubbing here.

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
        // A real on-disk file for the file:// layer — the only kind
        // writeZipBackup can embed (it reads bytes via File.inputStream()).
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
        val wallpaperEntries = entries.filter { it.startsWith("wallpapers/") }
        // Exactly one embedded image — the file:// layer. The content:// layer
        // is not embeddable, so no orphan wallpapers/ entry is created for it.
        assertThat(wallpaperEntries).hasSize(1)

        val backup = json.decodeFromString<BackupData>(readBackupJson(bytes))
        val layers = backup.settings.wallpaperLayers
        assertThat(layers).hasSize(2)
        // content:// layer: no imageFileName stamped (nothing was written).
        assertThat(layers[0].imageUri).isEqualTo("content://media/external/images/1")
        assertThat(layers[0].imageFileName).isNull()
        // file:// layer: stamped with the single embedded entry.
        assertThat(layers[1].imageFileName).isEqualTo(wallpaperEntries.single())
    }

    @Test
    fun `single-layer content-uri wallpaper is not embedded`() = runTest {
        fakeWallpaperRepo.currentState =
            WallpaperState.single("content://media/external/images/9", scale = 1.5f)

        val saved = backupManager.saveBackupToFile("content://out/backup.zip")
        assertThat(saved).isTrue()

        val bytes = zipBytes.toByteArray()
        assertThat(readZipEntryNames(bytes).none { it.startsWith("wallpapers/") }).isTrue()

        val backup = json.decodeFromString<BackupData>(readBackupJson(bytes))
        // The URI is preserved in the JSON, but no file reference is stamped.
        assertThat(backup.settings.wallpaperUri).isEqualTo("content://media/external/images/9")
        assertThat(backup.settings.wallpaperImageFileName).isNull()
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

    private fun readBackupJson(bytes: ByteArray): String {
        ZipInputStream(ByteArrayInputStream(bytes)).use { zip ->
            var entry = zip.nextEntry
            while (entry != null) {
                if (entry.name == "backup.json") return zip.readBytes().toString(Charsets.UTF_8)
                zip.closeEntry()
                entry = zip.nextEntry
            }
        }
        error("backup.json missing from exported ZIP")
    }
}
