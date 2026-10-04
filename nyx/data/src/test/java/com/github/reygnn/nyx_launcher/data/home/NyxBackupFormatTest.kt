package com.github.reygnn.nyx_launcher.data.home

import com.github.reygnn.launcher.core.wallpaper.FabPositionRepository
import android.content.ContentResolver
import android.content.Context
import android.net.Uri
import android.os.ParcelFileDescriptor
import com.github.reygnn.launcher.common.data.saf.SafDocuments
import com.github.reygnn.launcher.common.data.wallpaper.WallpaperFileManager
import com.github.reygnn.launcher.core.ComponentKey
import com.github.reygnn.launcher.core.wallpaper.FabPosition
import com.github.reygnn.launcher.core.wallpaper.WallpaperBackdrop
import com.github.reygnn.launcher.core.wallpaper.WallpaperDisplaySettings
import com.github.reygnn.launcher.core.wallpaper.WallpaperRepository
import com.github.reygnn.launcher.core.wallpaper.WallpaperState
import com.github.reygnn.launcher.core.wallpaper.WallpaperSurfaceMode
import com.github.reygnn.launcher.feature.backup.contract.BackupFormatContract
import com.github.reygnn.launcher.feature.backup.engine.BackupEngine
import com.github.reygnn.nyx_launcher.home.model.GridSpec
import com.github.reygnn.nyx_launcher.home.model.HomeLayout
import com.github.reygnn.nyx_launcher.home.model.IconStyle
import com.github.reygnn.nyx_launcher.home.model.ImportOptions
import com.github.reygnn.nyx_launcher.home.model.ImportResult
import com.github.reygnn.nyx_launcher.home.model.PreviewResult
import com.github.reygnn.nyx_launcher.home.repository.FakeDrawerFoldersRepository
import com.github.reygnn.nyx_launcher.home.repository.FakeHiddenAppsRepository
import com.github.reygnn.nyx_launcher.home.repository.FakeHomeLayoutRepository
import com.github.reygnn.nyx_launcher.home.repository.FakePreferencesRepository
import com.github.reygnn.nyx_launcher.home.usecase.ReconcileHomeLayoutUseCase
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import org.junit.Rule
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File
import java.io.FileDescriptor
import java.io.InputStream

/**
 * Nyx's run of the shared [BackupFormatContract] (2b-3c) through the SAF path of
 * [BackupRepositoryImpl] (`loadBackupFromFile`, `previewBackup`) on real `file://` documents.
 * Nyx binds no legacy reader (E5a). Robolectric for `Uri`.
 */
@RunWith(RobolectricTestRunner::class)
class NyxBackupFormatTest : BackupFormatContract() {

    @get:Rule
    val tmp = TemporaryFolder()

    private lateinit var images: File
    private lateinit var layoutStore: FakeHomeLayoutRepository
    private lateinit var hiddenStore: FakeHiddenAppsRepository
    private lateinit var foldersStore: FakeDrawerFoldersRepository
    private lateinit var prefs: FakePreferencesRepository
    private var reportedSize: Long? = null

    override val appId = NyxBackupSchema.APP_ID
    override val schemaVersion = NyxBackupSchema.SCHEMA_VERSION
    override val sectionId = NyxBackupSchema.SECTION_BACKUP
    override val sectionVersion = NyxBackupSchema.SECTION_VERSION

    override suspend fun seedCurrentState() {
        images = tmp.newFolder("images")
        layoutStore = FakeHomeLayoutRepository(HomeLayout(GridSpec(columns = 5, rows = 7), pages = 2, items = emptyList(), dock = emptyList()))
        hiddenStore = FakeHiddenAppsRepository(setOf(ComponentKey.of("com.cur", "com.cur.Main")))
        foldersStore = FakeDrawerFoldersRepository()
        prefs = FakePreferencesRepository(iconStyle = IconStyle.GRAYSCALE)
    }

    override suspend fun snapshot(): Any? = listOf(
        layoutStore.current,
        hiddenStore.current,
        foldersStore.current,
        prefs.iconStyle().first(),
    )

    override fun storedFiles(): Set<String> = images.list().orEmpty().toSet()

    override suspend fun import(document: ByteArray, declaredSize: Long?): Outcome {
        reportedSize = declaredSize
        return outcomeOf(manager().loadBackupFromFile(write(document), ImportOptions()))
    }

    override suspend fun preview(document: ByteArray, declaredSize: Long?): Outcome {
        reportedSize = declaredSize
        return when (val result = manager().previewBackup(write(document))) {
            is PreviewResult.Readable -> Outcome.Accepted
            is PreviewResult.Refused -> outcomeOf(result.result)
        }
    }

    private fun outcomeOf(result: ImportResult): Outcome = when (result) {
        is ImportResult.Success -> Outcome.Accepted
        is ImportResult.ForeignBackup -> Outcome.ForeignApp(result.appId)
        ImportResult.OutdatedBackup -> Outcome.OutdatedFormat
        is ImportResult.UnsupportedVersion -> Outcome.UnsupportedVersion(result.version)
        ImportResult.InvalidFormat -> Outcome.Invalid
        is ImportResult.Error -> Outcome.Error
    }

    /** The document as a real file the resolver serves. */
    private fun write(document: ByteArray): String = Uri.fromFile(tmp.newFile().apply { writeBytes(document) }).toString()

    private fun manager(): BackupRepositoryImpl {
        val resolver = mockk<ContentResolver> {
            every { openInputStream(any()) } answers { File(firstArg<Uri>().path!!).inputStream() }
            every { openFileDescriptor(any(), any()) } answers {
                reportedSize?.let { size ->
                    mockk<ParcelFileDescriptor>(relaxed = true) {
                        every { statSize } returns size
                        every { fileDescriptor } returns FileDescriptor()
                    }
                }
            }
        }
        return BackupRepositoryImpl(
            safDocuments = SafDocuments(mockk<Context> { every { contentResolver } returns resolver }),
            homeLayoutRepository = layoutStore,
            drawerFoldersRepository = foldersStore,
            hiddenAppsRepository = hiddenStore,
            preferences = prefs,
            displaySettings = mockk<WallpaperDisplaySettings>(relaxed = true) {
                every { wallpaperScrimAlphaStateFlow } returns flowOf(0.1f)
                every { wallpaperBackdropFlow } returns flowOf(WallpaperBackdrop.BLACK)
                every { wallpaperSurfaceModeFlow } returns flowOf(WallpaperSurfaceMode.AUTO)
            },
            wallpaperRepository = mockk<WallpaperRepository>(relaxed = true) {
                coEvery { getWallpaperStateSync() } returns WallpaperState.NONE
            },
            fabPositionStore = mockk<FabPositionRepository>(relaxed = true) {
                every { fabPositionFlow } returns flowOf(FabPosition.DEFAULT)
            },
            // Imported images would land here, like the production file manager's directory.
            fileManager = mockk<WallpaperFileManager>(relaxed = true) {
                every { copyFromInputStream(any()) } answers {
                    val file = File(images, "image-${System.nanoTime()}")
                    file.writeBytes(firstArg<InputStream>().readBytes())
                    Uri.fromFile(file)
                }
            },
            serializer = NyxBackupSerializer(),
            reconcileHomeLayout = mockk<ReconcileHomeLayoutUseCase>(relaxed = true),
            engine = BackupEngine(mainDispatcherRule.testDispatcher, emptySet()),
            appVersionName = "contract",
            ioDispatcher = mainDispatcherRule.testDispatcher,
        )
    }
}
