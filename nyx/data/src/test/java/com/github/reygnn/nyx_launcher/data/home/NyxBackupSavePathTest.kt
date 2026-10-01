package com.github.reygnn.nyx_launcher.data.home

import android.content.ContentResolver
import android.content.Context
import android.net.Uri
import com.github.reygnn.launcher.common.data.wallpaper.WallpaperFileManager
import com.github.reygnn.launcher.core.testing.MainDispatcherRule
import com.github.reygnn.launcher.core.wallpaper.FabPosition
import com.github.reygnn.launcher.core.wallpaper.WallpaperBackdrop
import com.github.reygnn.launcher.core.wallpaper.WallpaperDisplaySettings
import com.github.reygnn.launcher.core.wallpaper.WallpaperRepository
import com.github.reygnn.launcher.core.wallpaper.WallpaperState
import com.github.reygnn.launcher.core.wallpaper.WallpaperSurfaceMode
import com.github.reygnn.launcher.feature.backup.container.ContainerManifest
import com.github.reygnn.launcher.feature.backup.container.ContainerManifestCodec
import com.github.reygnn.launcher.feature.backup.engine.BackupEngine
import com.github.reygnn.nyx_launcher.home.model.GridSpec
import com.github.reygnn.nyx_launcher.home.model.HomeLayout
import com.github.reygnn.nyx_launcher.home.model.ImportOptions
import com.github.reygnn.nyx_launcher.home.model.ImportResult
import com.github.reygnn.nyx_launcher.home.model.PreviewResult
import com.github.reygnn.nyx_launcher.home.repository.FakeDrawerFoldersRepository
import com.github.reygnn.nyx_launcher.home.repository.FakeHiddenAppsRepository
import com.github.reygnn.nyx_launcher.home.repository.FakeHomeLayoutRepository
import com.github.reygnn.nyx_launcher.home.repository.FakePreferencesRepository
import com.github.reygnn.nyx_launcher.home.repository.HomeLayoutRepository
import com.github.reygnn.nyx_launcher.home.usecase.ReconcileHomeLayoutUseCase
import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * Nyx's SAF path of [NyxBackupManager] (2b-3a): the [com.github.reygnn.nyx_launcher.home.repository.BackupRepository]
 * operations on a real `file://` document. The first case is the fifth case of the old Nyx
 * `WriteOrDiscardTest` ("a reported failure discards the target"): a failed export now
 * throws out of the write — never a `false` — so the shared writeOrDiscard removes the
 * document. Robolectric for `Uri`.
 */
@RunWith(RobolectricTestRunner::class)
class NyxBackupSavePathTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    @get:Rule
    val tmp = TemporaryFolder()

    private val resolver = mockk<ContentResolver> {
        every { openOutputStream(any()) } answers { File(firstArg<Uri>().path!!).outputStream() }
        every { openInputStream(any()) } answers { File(firstArg<Uri>().path!!).inputStream() }
        every { openFileDescriptor(any(), any()) } returns null // size unknown: the engine's own cap applies
    }
    private val context = mockk<Context> { every { contentResolver } returns resolver }

    private val layout = HomeLayout(grid = GridSpec(columns = 4, rows = 6), pages = 1, items = emptyList(), dock = emptyList())

    private fun manager(homeLayoutRepository: HomeLayoutRepository = FakeHomeLayoutRepository(layout)) = NyxBackupManager(
        context = context,
        homeLayoutRepository = homeLayoutRepository,
        drawerFoldersRepository = FakeDrawerFoldersRepository(),
        hiddenAppsRepository = FakeHiddenAppsRepository(),
        preferences = FakePreferencesRepository(),
        displaySettings = mockk<WallpaperDisplaySettings>(relaxed = true) {
            // A relaxed mock's Flow never emits; first() would throw.
            every { wallpaperScrimAlphaStateFlow } returns flowOf(0.1f)
            every { wallpaperBackdropFlow } returns flowOf(WallpaperBackdrop.BLACK)
            every { wallpaperSurfaceModeFlow } returns flowOf(WallpaperSurfaceMode.AUTO)
        },
        wallpaperRepository = mockk<WallpaperRepository>(relaxed = true) {
            coEvery { getWallpaperStateSync() } returns WallpaperState.NONE
        },
        fabPositionStore = mockk<NyxFabPositionStore>(relaxed = true) {
            every { fabPositionFlow } returns flowOf(FabPosition.DEFAULT)
        },
        fileManager = mockk<WallpaperFileManager>(relaxed = true),
        serializer = NyxBackupSerializer(),
        reconcileHomeLayout = mockk<ReconcileHomeLayoutUseCase>(relaxed = true),
        engine = BackupEngine(mainDispatcherRule.testDispatcher, emptySet()),
        appVersionName = "0.2.0",
        ioDispatcher = mainDispatcherRule.testDispatcher,
    )

    /** The document CreateDocument has just made for the export. */
    private fun createdDocument(): Uri = Uri.fromFile(tmp.newFile("nyx-backup.zip"))

    @Test
    fun a_failed_export_leaves_no_document() = runTest(mainDispatcherRule.testDispatcher) {
        val failing = mockk<HomeLayoutRepository> { every { layout() } returns flow { throw IOException("store gone") } }
        val target = createdDocument()

        val ok = manager(failing).saveBackupToFile(target.toString())

        assertThat(ok).isFalse()
        verify { resolver.openOutputStream(target) } // the export did start writing
        assertThat(File(target.path!!).exists()).isFalse() // …and the document was discarded
    }

    @Test
    fun a_successful_export_keeps_a_document_that_previews_and_imports() = runTest(mainDispatcherRule.testDispatcher) {
        val target = createdDocument()
        val manager = manager()

        assertThat(manager.saveBackupToFile(target.toString())).isTrue()

        assertThat(File(target.path!!).length()).isGreaterThan(0L)
        val result = manager.previewBackup(target.toString())
        assertThat(result).isInstanceOf(PreviewResult.Readable::class.java)
        val preview = (result as PreviewResult.Readable).preview
        assertThat(preview.appVersion).isEqualTo("0.2.0")
        assertThat(preview.homeItemCount).isEqualTo(0)
        assertThat(preview.hasSettings).isTrue()
        assertThat(manager.loadBackupFromFile(target.toString(), ImportOptions())).isEqualTo(ImportResult.Success())
    }

    @Test
    fun an_import_with_nothing_selected_reads_nothing() = runTest(mainDispatcherRule.testDispatcher) {
        val nothing = ImportOptions(importLayout = false, importHiddenApps = false, importSettings = false, importWallpaper = false)

        val result = manager().loadBackupFromFile(createdDocument().toString(), nothing)

        assertThat(result).isEqualTo(ImportResult.Error("No import options selected"))
        verify(exactly = 0) { resolver.openInputStream(any()) }
    }

    @Test
    fun a_location_that_is_no_document_is_refused() = runTest(mainDispatcherRule.testDispatcher) {
        val manager = manager()

        assertThat(manager.saveBackupToFile("https://example.org/backup.zip")).isFalse()
        assertThat(manager.loadBackupFromFile("https://example.org/backup.zip", ImportOptions())).isInstanceOf(ImportResult.Error::class.java)
        assertThat(manager.previewBackup("https://example.org/backup.zip")).isInstanceOf(PreviewResult.Refused::class.java)
        verify(exactly = 0) { resolver.openOutputStream(any()) }
    }

    @Test
    fun preview_and_import_refuse_every_engine_outcome_alike() = runTest(mainDispatcherRule.testDispatcher) {
        // One mapping (refusalOf) serves both paths (2b-3b), so the settings can show the import's
        // own message at once. Each case: the same document, both paths, nothing written.
        val engine = BackupEngine(mainDispatcherRule.testDispatcher, emptySet())
        val foreign = ByteArrayOutputStream().also { out ->
            engine.export(out, ContainerManifest.Producer("kolibri", "test", 1L), 1, emptyList()) {
                mapOf("kolibri.backup" to ContainerManifest.Section(1, JsonPrimitive("x")))
            }
        }.toByteArray()
        val newerFormat = zipOf(
            "manifest.json" to ContainerManifestCodec.encode(
                ContainerManifest(formatVersion = "9.0", producer = ContainerManifest.Producer(NyxBackupSchema.APP_ID, "test", 1L), schemaVersion = 1),
            ),
        )
        val undecodable = zipOf(
            "manifest.json" to ContainerManifestCodec.encode(
                ContainerManifest(
                    producer = ContainerManifest.Producer(NyxBackupSchema.APP_ID, "test", 1L),
                    schemaVersion = NyxBackupSchema.SCHEMA_VERSION,
                    sections = mapOf(NyxBackupSchema.SECTION_BACKUP to ContainerManifest.Section(NyxBackupSchema.SECTION_VERSION, JsonPrimitive("not a backup"))),
                ),
            ),
        )
        val cases = listOf(
            Triple("another app", foreign, ImportResult.ForeignBackup("kolibri")),
            Triple("pre-container archive", zipOf("backup.json" to "{}".toByteArray()), ImportResult.OutdatedBackup),
            Triple("newer format", newerFormat, ImportResult.UnsupportedVersion("9.0")),
            Triple("not a ZIP", "not a zip".toByteArray(), ImportResult.InvalidFormat),
            Triple("undecodable section", undecodable, ImportResult.InvalidFormat),
        )
        val layoutStore = FakeHomeLayoutRepository(layout)
        val manager = manager(layoutStore)

        for ((case, bytes, expected) in cases) {
            val document = Uri.fromFile(tmp.newFile().apply { writeBytes(bytes) }).toString()
            assertWithMessage("import of: $case").that(manager.loadBackupFromFile(document, ImportOptions())).isEqualTo(expected)
            assertWithMessage("preview of: $case").that(manager.previewBackup(document)).isEqualTo(PreviewResult.Refused(expected))
        }
        assertThat(layoutStore.current).isEqualTo(layout) // nothing written
    }

    private fun zipOf(vararg entries: Pair<String, ByteArray>): ByteArray {
        val bos = ByteArrayOutputStream()
        ZipOutputStream(bos).use { zip ->
            for ((name, bytes) in entries) {
                zip.putNextEntry(ZipEntry(name))
                zip.write(bytes)
                zip.closeEntry()
            }
        }
        return bos.toByteArray()
    }
}
