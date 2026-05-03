package com.github.reygnn.kolibri_launcher.data

import android.content.Context
import com.github.reygnn.kolibri_launcher.domain.repository.CustomNamesRepository
import com.github.reygnn.kolibri_launcher.domain.repository.FavoritesOrderRepository
import com.github.reygnn.kolibri_launcher.domain.repository.FavoritesRepository
import com.github.reygnn.kolibri_launcher.domain.repository.HiddenAppsRepository
import com.github.reygnn.kolibri_launcher.domain.repository.InstalledAppsRepository
import com.github.reygnn.kolibri_launcher.domain.repository.SettingsRepository
import com.github.reygnn.kolibri_launcher.domain.repository.SwipeActionsRepository
import com.github.reygnn.kolibri_launcher.domain.repository.WallpaperRepository

/**
 * Test-only factory: builds a fully-wired [BackupRepositoryImpl] from
 * the same constructor signature the class had before its split into
 * [BackupSerializer] + [BackupDataAssembler] + [BackupRepositoryImpl].
 *
 * The 9 BackupRepositoryImpl spec test files (Strict, Doomsday,
 * Wallpaper, Logic, Io, Security, Malformed, Isolation, NamingConvention)
 * test the public API; they don't care about the internal layering and
 * shouldn't need to know about it. This factory keeps the
 * `BackupRepositoryImpl(...)` → `BackupRepositoryImplTestFactory.create(...)`
 * change to one line per file.
 *
 * Eventually some of these tests will migrate to
 * `BackupSerializerTest` (the parser/validator/strict-fallback edge
 * cases — most of Strict, Malformed, Doomsday) and to
 * `BackupDataAssemblerTest` (read-all-then-import round-trip with fakes).
 * Until that migration happens, this factory keeps the existing
 * coverage intact.
 */
internal object BackupRepositoryImplTestFactory {
    fun create(
        favoritesRepository: FavoritesRepository,
        favoritesOrderRepository: FavoritesOrderRepository,
        hiddenAppsRepository: HiddenAppsRepository,
        customNamesRepository: CustomNamesRepository,
        installedAppsRepository: InstalledAppsRepository,
        swipeActionsRepository: SwipeActionsRepository,
        settingsRepository: SettingsRepository,
        wallpaperRepository: WallpaperRepository,
        wallpaperFileManager: WallpaperFileManager,
        context: Context,
    ): BackupRepositoryImpl {
        val assembler = BackupDataAssembler(
            favoritesRepository = favoritesRepository,
            favoritesOrderRepository = favoritesOrderRepository,
            hiddenAppsRepository = hiddenAppsRepository,
            customNamesRepository = customNamesRepository,
            installedAppsRepository = installedAppsRepository,
            swipeActionsRepository = swipeActionsRepository,
            settingsRepository = settingsRepository,
            wallpaperRepository = wallpaperRepository,
        )
        val serializer = BackupSerializer()
        return BackupRepositoryImpl(
            assembler = assembler,
            serializer = serializer,
            wallpaperFileManager = wallpaperFileManager,
            context = context,
        )
    }
}
