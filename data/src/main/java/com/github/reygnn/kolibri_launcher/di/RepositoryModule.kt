package com.github.reygnn.kolibri_launcher.di

import com.github.reygnn.kolibri_launcher.data.AppUsageRepositoryImpl
import com.github.reygnn.kolibri_launcher.data.BackupRepositoryImpl
import com.github.reygnn.kolibri_launcher.data.CrashReportConsentRepositoryImpl
import com.github.reygnn.kolibri_launcher.data.CustomNamesRepositoryImpl
import com.github.reygnn.kolibri_launcher.data.FabPositionRepositoryImpl
import com.github.reygnn.kolibri_launcher.data.FavoritesOrderRepositoryImpl
import com.github.reygnn.kolibri_launcher.data.FavoritesRepositoryImpl
import com.github.reygnn.kolibri_launcher.data.HiddenAppsRepositoryImpl
import com.github.reygnn.kolibri_launcher.data.InstalledAppsRepositoryImpl
import com.github.reygnn.kolibri_launcher.data.InstalledAppsStateRepositoryImpl
import com.github.reygnn.kolibri_launcher.data.ResetRepositoryImpl
import com.github.reygnn.kolibri_launcher.data.SettingsRepositoryImpl
import com.github.reygnn.kolibri_launcher.data.ShortcutRepositoryImpl
import com.github.reygnn.kolibri_launcher.data.SwipeActionsRepositoryImpl
import com.github.reygnn.kolibri_launcher.data.TimeBasedEventsRepositoryImpl
import com.github.reygnn.kolibri_launcher.data.UsageExportRepositoryImpl
import com.github.reygnn.kolibri_launcher.data.WallpaperRepositoryImpl
import com.github.reygnn.kolibri_launcher.data.service.ShortcutLauncherServiceImpl
import com.github.reygnn.kolibri_launcher.data.wallpaper.WallpaperBitmapLuminanceImpl
import com.github.reygnn.kolibri_launcher.domain.repository.AppUsageRepository
import com.github.reygnn.kolibri_launcher.domain.repository.BackupRepository
import com.github.reygnn.kolibri_launcher.domain.repository.CrashReportConsentRepository
import com.github.reygnn.kolibri_launcher.domain.repository.CustomNamesRepository
import com.github.reygnn.kolibri_launcher.domain.repository.FabPositionRepository
import com.github.reygnn.kolibri_launcher.domain.repository.FavoritesOrderRepository
import com.github.reygnn.kolibri_launcher.domain.repository.FavoritesRepository
import com.github.reygnn.kolibri_launcher.domain.repository.HiddenAppsRepository
import com.github.reygnn.kolibri_launcher.domain.repository.InstalledAppsRepository
import com.github.reygnn.kolibri_launcher.domain.repository.InstalledAppsStateRepository
import com.github.reygnn.kolibri_launcher.domain.repository.ResetRepository
import com.github.reygnn.kolibri_launcher.domain.repository.SettingsRepository
import com.github.reygnn.kolibri_launcher.domain.repository.ShortcutRepository
import com.github.reygnn.kolibri_launcher.domain.repository.SwipeActionsRepository
import com.github.reygnn.kolibri_launcher.domain.repository.TimeBasedEventsRepository
import com.github.reygnn.kolibri_launcher.domain.repository.UsageExportRepository
import com.github.reygnn.kolibri_launcher.domain.repository.WallpaperBitmapLuminance
import com.github.reygnn.kolibri_launcher.domain.repository.WallpaperRepository
import com.github.reygnn.kolibri_launcher.domain.service.ShortcutLauncherService
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
abstract class RepositoryModule {

    @Binds
    @Singleton
    abstract fun bindSettingsRepository(impl: SettingsRepositoryImpl): SettingsRepository

    @Binds
    @Singleton
    abstract fun bindCrashReportConsentRepository(
        impl: CrashReportConsentRepositoryImpl,
    ): CrashReportConsentRepository

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
    abstract fun bindInstalledAppsRepository(impl: InstalledAppsRepositoryImpl): InstalledAppsRepository

    @Binds
    @Singleton
    abstract fun bindCustomNamesRepository(impl: CustomNamesRepositoryImpl): CustomNamesRepository

    @Binds
    @Singleton
    abstract fun bindInstalledAppsStateRepository(impl: InstalledAppsStateRepositoryImpl): InstalledAppsStateRepository

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
    abstract fun bindWallpaperRepository(impl: WallpaperRepositoryImpl): WallpaperRepository

    @Binds
    @Singleton
    abstract fun bindWallpaperBitmapLuminance(impl: WallpaperBitmapLuminanceImpl): WallpaperBitmapLuminance

    @Binds
    @Singleton
    abstract fun bindFabPositionRepository(impl: FabPositionRepositoryImpl): FabPositionRepository
}
