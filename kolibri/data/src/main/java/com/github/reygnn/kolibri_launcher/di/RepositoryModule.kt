package com.github.reygnn.kolibri_launcher.di

import com.github.reygnn.launcher.feature.wallpaper.WallpaperDisplaySettingsStore
import com.github.reygnn.kolibri_launcher.data.AppUsageRepositoryImpl
import com.github.reygnn.kolibri_launcher.data.BackupRepositoryImpl
import com.github.reygnn.kolibri_launcher.data.CustomNamesRepositoryImpl
import com.github.reygnn.kolibri_launcher.data.DefaultAppsRepositoryImpl
import com.github.reygnn.launcher.feature.wallpaper.FabPositionStore
import com.github.reygnn.kolibri_launcher.data.FavoritesOrderRepositoryImpl
import com.github.reygnn.kolibri_launcher.data.FavoritesRepositoryImpl
import com.github.reygnn.kolibri_launcher.data.HiddenAppsRepositoryImpl
import com.github.reygnn.kolibri_launcher.data.ResetRepositoryImpl
import com.github.reygnn.kolibri_launcher.data.DataStoreMaintenanceRepositoryImpl
import com.github.reygnn.kolibri_launcher.data.SettingsRepositoryImpl
import com.github.reygnn.kolibri_launcher.data.ShortcutRepositoryImpl
import com.github.reygnn.kolibri_launcher.data.SwipeActionsRepositoryImpl
import com.github.reygnn.launcher.common.data.timeinfo.TimeBasedEventsRepositoryImpl
import com.github.reygnn.kolibri_launcher.data.UsageExportRepositoryImpl
import com.github.reygnn.launcher.common.data.wallpaper.WallpaperRepositoryImpl
import com.github.reygnn.kolibri_launcher.data.service.ComponentLabelResolverImpl
import com.github.reygnn.kolibri_launcher.data.service.ShortcutLauncherServiceImpl
import com.github.reygnn.launcher.common.data.wallpaper.WallpaperBitmapLuminanceImpl
// Shared installed-apps subsystem (SHARED_INSTALLED_APPS_SPEC §3), bound app-side
// exactly like the Wallpaper/TimeInfo :common-data impls (no auto-aggregated
// module). Since C3 these ARE the installed-apps types Kolibri consumes; the old
// PackageManager-backed kolibri.data impls + kolibri.domain interfaces are retired.
import com.github.reygnn.launcher.core.AppEnumerator
import com.github.reygnn.launcher.core.InstalledAppsRepository
import com.github.reygnn.launcher.core.InstalledAppsStateRepository
import com.github.reygnn.launcher.common.data.installedapps.LauncherAppsEnumerator
import com.github.reygnn.launcher.common.data.installedapps.InstalledAppsRepositoryImpl
import com.github.reygnn.launcher.common.data.installedapps.InstalledAppsStateRepositoryImpl
import com.github.reygnn.kolibri_launcher.domain.repository.AppUsageRepository
import com.github.reygnn.kolibri_launcher.domain.repository.BackupRepository
import com.github.reygnn.kolibri_launcher.domain.repository.CustomNamesRepository
import com.github.reygnn.kolibri_launcher.domain.repository.DefaultAppsRepository
import com.github.reygnn.launcher.core.wallpaper.FabPositionRepository
import com.github.reygnn.kolibri_launcher.domain.repository.FavoritesOrderRepository
import com.github.reygnn.kolibri_launcher.domain.repository.FavoritesRepository
import com.github.reygnn.kolibri_launcher.domain.repository.HiddenAppsRepository
import com.github.reygnn.kolibri_launcher.domain.repository.ResetRepository
import com.github.reygnn.kolibri_launcher.domain.repository.DataStoreMaintenanceRepository
import com.github.reygnn.kolibri_launcher.domain.repository.SettingsRepository
import com.github.reygnn.launcher.core.timeinfo.TimeInfoSettings
import com.github.reygnn.launcher.core.wallpaper.WallpaperDisplaySettings
import com.github.reygnn.kolibri_launcher.domain.repository.ShortcutRepository
import com.github.reygnn.kolibri_launcher.domain.repository.SwipeActionsRepository
import com.github.reygnn.launcher.core.timeinfo.TimeBasedEventsRepository
import com.github.reygnn.kolibri_launcher.domain.repository.UsageExportRepository
import com.github.reygnn.launcher.core.wallpaper.WallpaperBitmapLuminance
import com.github.reygnn.launcher.core.wallpaper.WallpaperRepository
import com.github.reygnn.launcher.core.OwnsSettingsStoreKeys
import com.github.reygnn.kolibri_launcher.domain.service.ComponentLabelResolver
import com.github.reygnn.kolibri_launcher.domain.service.ShortcutLauncherService
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import dagger.multibindings.IntoSet
import dagger.multibindings.Multibinds
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
abstract class RepositoryModule {

    @Binds
    @Singleton
    abstract fun bindSettingsRepository(impl: SettingsRepositoryImpl): SettingsRepository

    // The home-info subsystem depends only on the narrow TimeInfoSettings port
    // (HIE-INV-2); Kolibri's SettingsRepository implements it (in :core.timeinfo).
    @Binds
    abstract fun bindTimeInfoSettings(repo: SettingsRepository): TimeInfoSettings

    // The shared wallpaper render layer depends only on the narrow
    // WallpaperDisplaySettings port (WSS-INV-4). Since 3a-4 the shared store implements it; it
    // is a singleton, and SettingsRepository delegates its members to the same instance.
    @Binds
    abstract fun bindWallpaperDisplaySettings(store: WallpaperDisplaySettingsStore): WallpaperDisplaySettings

    @Binds
    @Singleton
    abstract fun bindDataStoreMaintenanceRepository(
        impl: DataStoreMaintenanceRepositoryImpl,
    ): DataStoreMaintenanceRepository

    @Binds
    @Singleton
    abstract fun bindFavoritesRepository(impl: FavoritesRepositoryImpl): FavoritesRepository

    @Binds
    @Singleton
    abstract fun bindAppUsageRepository(impl: AppUsageRepositoryImpl): AppUsageRepository

    @Binds
    @Singleton
    abstract fun bindHiddenAppsRepository(impl: HiddenAppsRepositoryImpl): HiddenAppsRepository

    @Binds
    @Singleton
    abstract fun bindFavoritesOrderRepository(impl: FavoritesOrderRepositoryImpl): FavoritesOrderRepository

    @Binds
    @Singleton
    abstract fun bindCustomNamesRepository(impl: CustomNamesRepositoryImpl): CustomNamesRepository

    @Binds
    @Singleton
    abstract fun bindDefaultAppsRepository(impl: DefaultAppsRepositoryImpl): DefaultAppsRepository

    // ── Installed-apps subsystem: shared :core port → :common-data impls (C3) ──
    // Kolibri now consumes the shared LauncherApps-backed motor + holder; the old
    // PackageManager-backed kolibri.data impls are retired (deleted in C3b).
    @Binds
    @Singleton
    abstract fun bindAppEnumerator(impl: LauncherAppsEnumerator): AppEnumerator

    @Binds
    @Singleton
    abstract fun bindInstalledAppsRepository(impl: InstalledAppsRepositoryImpl): InstalledAppsRepository

    @Binds
    @Singleton
    abstract fun bindInstalledAppsStateRepository(
        impl: InstalledAppsStateRepositoryImpl,
    ): InstalledAppsStateRepository

    @Binds
    @Singleton
    abstract fun bindShortcutRepository(impl: ShortcutRepositoryImpl): ShortcutRepository

    @Binds
    @Singleton
    abstract fun bindSwipeActionsRepository(impl: SwipeActionsRepositoryImpl): SwipeActionsRepository

    @Binds
    @Singleton
    abstract fun bindBackupRepository(impl: BackupRepositoryImpl): BackupRepository

    @Binds
    @Singleton
    abstract fun bindUsageExportRepository(impl: UsageExportRepositoryImpl): UsageExportRepository

    @Binds
    @Singleton
    abstract fun bindTimeBasedEventsRepository(impl: TimeBasedEventsRepositoryImpl): TimeBasedEventsRepository

    @Binds
    @Singleton
    abstract fun bindResetRepository(impl: ResetRepositoryImpl): ResetRepository

    @Binds
    @Singleton
    abstract fun bindShortcutLauncherService(impl: ShortcutLauncherServiceImpl): ShortcutLauncherService

    @Binds
    @Singleton
    abstract fun bindComponentLabelResolver(impl: ComponentLabelResolverImpl): ComponentLabelResolver

    @Binds
    @Singleton
    abstract fun bindWallpaperRepository(impl: WallpaperRepositoryImpl): WallpaperRepository

    @Binds
    @Singleton
    abstract fun bindWallpaperBitmapLuminance(impl: WallpaperBitmapLuminanceImpl): WallpaperBitmapLuminance

    @Binds
    @Singleton
    abstract fun bindFabPositionRepository(impl: FabPositionStore): FabPositionRepository

    // ---- Storage-cleanup keep-list (OwnsSettingsStoreKeys) ----
    // Every settings-store key owner is multibound into this set; the cleanup
    // (DataStoreMaintenanceRepositoryImpl) deletes any settings-store key that NO
    // owner claims. NOTE: the @IntoSet binding below is a MANUAL per-owner line —
    // implementing OwnsSettingsStoreKeys does NOT auto-contribute to the set, so a
    // forgotten binding would silently drop that owner and let the cleanup delete
    // its live keys. That is caught by the checkConventions binding-parity gate
    // (tools/check-conventions.sh), which fails the build unless every structural
    // owner has exactly one @IntoSet binding. (AnrReporter used to contribute its
    // watermark key from :app; since SPEC_NYX_REWRITE 1c-1 that key lives in
    // :feature-crashreporting's own store and is no longer a settings-store key.)
    @Multibinds
    abstract fun settingsStoreKeyOwners(): Set<OwnsSettingsStoreKeys>

    @Binds
    @IntoSet
    abstract fun bindSettingsKeysOwner(impl: SettingsRepositoryImpl): OwnsSettingsStoreKeys

    @Binds
    @IntoSet
    abstract fun bindFavoritesKeysOwner(impl: FavoritesRepositoryImpl): OwnsSettingsStoreKeys

    @Binds
    @IntoSet
    abstract fun bindFavoritesOrderKeysOwner(
        impl: FavoritesOrderRepositoryImpl,
    ): OwnsSettingsStoreKeys

    @Binds
    @IntoSet
    abstract fun bindHiddenAppsKeysOwner(impl: HiddenAppsRepositoryImpl): OwnsSettingsStoreKeys

    @Binds
    @IntoSet
    abstract fun bindCustomNamesKeysOwner(impl: CustomNamesRepositoryImpl): OwnsSettingsStoreKeys

    @Binds
    @IntoSet
    abstract fun bindSwipeActionsKeysOwner(impl: SwipeActionsRepositoryImpl): OwnsSettingsStoreKeys

    @Binds
    @IntoSet
    abstract fun bindWallpaperKeysOwner(impl: WallpaperRepositoryImpl): OwnsSettingsStoreKeys

    // Scrim, backdrop and surface mode (3a-4): owned by the shared store since they left
    // SettingsRepositoryImpl. Without this line the storage cleanup would delete them.
    @Binds
    @IntoSet
    abstract fun bindWallpaperDisplayKeysOwner(impl: WallpaperDisplaySettingsStore): OwnsSettingsStoreKeys

    @Binds
    @IntoSet
    abstract fun bindFabPositionKeysOwner(impl: FabPositionStore): OwnsSettingsStoreKeys
}
