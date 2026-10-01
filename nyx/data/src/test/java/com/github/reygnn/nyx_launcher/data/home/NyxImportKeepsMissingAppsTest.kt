package com.github.reygnn.nyx_launcher.data.home

import android.content.Context
import com.github.reygnn.launcher.common.data.saf.SafDocuments
import com.github.reygnn.launcher.common.data.wallpaper.WallpaperFileManager
import com.github.reygnn.launcher.core.ComponentKey
import com.github.reygnn.launcher.core.wallpaper.WallpaperDisplaySettings
import com.github.reygnn.launcher.core.wallpaper.WallpaperRepository
import com.github.reygnn.launcher.feature.backup.container.ContainerManifest
import com.github.reygnn.launcher.feature.backup.contract.ImportKeepsMissingAppsContract
import com.github.reygnn.launcher.feature.backup.engine.BackupEngine
import com.github.reygnn.nyx_launcher.home.model.GridSpec
import com.github.reygnn.nyx_launcher.home.model.HomeItem
import com.github.reygnn.nyx_launcher.home.model.HomeLayout
import com.github.reygnn.nyx_launcher.home.model.ImportOptions
import com.github.reygnn.nyx_launcher.home.model.ImportResult
import com.github.reygnn.nyx_launcher.home.repository.FakeDrawerFoldersRepository
import com.github.reygnn.nyx_launcher.home.repository.FakeHiddenAppsRepository
import com.github.reygnn.nyx_launcher.home.repository.FakeHomeLayoutRepository
import com.github.reygnn.nyx_launcher.home.repository.FakePreferencesRepository
import com.github.reygnn.nyx_launcher.home.usecase.ReconcileHomeLayoutUseCase
import io.mockk.mockk
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream

/**
 * Nyx's run of the shared [ImportKeepsMissingAppsContract] (2b-2c): the backup carries the
 * missing app in the home grid, the dock, the hidden apps and a drawer folder; Nyx has no
 * installed-apps check in its import at all.
 */
class NyxImportKeepsMissingAppsTest : ImportKeepsMissingAppsContract() {

    private val other = ComponentKey.of("com.also.gone", "com.also.gone.Main")

    override suspend fun importReferencing(storedClassName: String): Map<String, Set<ComponentKey>> {
        val stored = ComponentKeyDto(missing.packageName, storedClassName)
        val backup = NyxBackup(
            layout = HomeLayoutDto(
                columns = 4, rows = 6, pages = 1,
                items = listOf(PlacedItemDto(HomeItemDto.AppDto("i1", stored), page = 0, x = 0, y = 0)),
                dock = listOf(HomeItemDto.AppDto("d1", stored)),
            ),
            hiddenApps = listOf(stored),
            drawerFolders = DrawerFoldersDto(folders = listOf(DrawerFolderDto("f1", "Gone", listOf(stored, other.toDto())))),
        )
        val engine = BackupEngine(mainDispatcherRule.testDispatcher, emptySet())
        val out = ByteArrayOutputStream()
        engine.export(out, ContainerManifest.Producer(NyxBackupSchema.APP_ID, "test", 1L), NyxBackupSchema.SCHEMA_VERSION, emptyList()) {
            mapOf(NyxBackupSchema.SECTION_BACKUP to ContainerManifest.Section(NyxBackupSchema.SECTION_VERSION, NyxBackupSerializer().toJson(backup)))
        }

        val layout = FakeHomeLayoutRepository(HomeLayout(GridSpec(4, 6), pages = 1, items = emptyList(), dock = emptyList()))
        val hidden = FakeHiddenAppsRepository()
        val folders = FakeDrawerFoldersRepository()
        val manager = NyxBackupManager(
            safDocuments = SafDocuments(mockk<Context>()), // the stream-level importFrom never touches it
            homeLayoutRepository = layout,
            drawerFoldersRepository = folders,
            hiddenAppsRepository = hidden,
            preferences = FakePreferencesRepository(),
            displaySettings = mockk<WallpaperDisplaySettings>(relaxed = true),
            wallpaperRepository = mockk<WallpaperRepository>(relaxed = true),
            fabPositionStore = mockk<NyxFabPositionStore>(relaxed = true),
            fileManager = mockk<WallpaperFileManager>(relaxed = true),
            serializer = NyxBackupSerializer(),
            reconcileHomeLayout = mockk<ReconcileHomeLayoutUseCase>(relaxed = true),
            engine = engine,
            appVersionName = "test",
            ioDispatcher = mainDispatcherRule.testDispatcher,
        )

        val bytes = out.toByteArray()
        val result = manager.importFrom({ ByteArrayInputStream(bytes) }, ImportOptions())
        check(result is ImportResult.Success) { "import failed: $result" }

        return mapOf(
            "home grid" to layout.current.items.mapNotNullTo(HashSet()) { (it.item as? HomeItem.App)?.key },
            "dock" to layout.current.dock.mapNotNullTo(HashSet()) { (it as? HomeItem.App)?.key },
            "hidden apps" to hidden.current,
            "drawer folders" to folders.current.folders.flatMapTo(HashSet()) { it.members },
        )
    }
}
