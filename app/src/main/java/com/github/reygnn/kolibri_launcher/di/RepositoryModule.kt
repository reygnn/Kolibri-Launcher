package com.github.reygnn.kolibri_launcher.di

import com.github.reygnn.kolibri_launcher.FavoritesManager
import com.github.reygnn.kolibri_launcher.FavoritesOrderManager
import com.github.reygnn.kolibri_launcher.GetDrawerAppsUseCase
import com.github.reygnn.kolibri_launcher.GetDrawerAppsUseCaseRepository
import com.github.reygnn.kolibri_launcher.GetFavoriteAppsUseCase
import com.github.reygnn.kolibri_launcher.GetFavoriteAppsUseCaseRepository
import com.github.reygnn.kolibri_launcher.GetOnboardingAppsUseCase
import com.github.reygnn.kolibri_launcher.GetOnboardingAppsUseCaseRepository
import com.github.reygnn.kolibri_launcher.data.SettingsManager
import com.github.reygnn.kolibri_launcher.data.SettingsRepository
import com.github.reygnn.kolibri_launcher.data.ShortcutManager
import com.github.reygnn.kolibri_launcher.data.ShortcutRepository
import com.github.reygnn.kolibri_launcher.data.SwipeActionsManager
import com.github.reygnn.kolibri_launcher.data.SwipeActionsRepository
import com.github.reygnn.kolibri_launcher.data.CustomNamesManager
import com.github.reygnn.kolibri_launcher.data.CustomNamesRepository
import com.github.reygnn.kolibri_launcher.data.AppUsageManager
import com.github.reygnn.kolibri_launcher.data.AppUsageRepository
import com.github.reygnn.kolibri_launcher.data.HiddenAppsManager
import com.github.reygnn.kolibri_launcher.data.HiddenAppsRepository
import com.github.reygnn.kolibri_launcher.data.BackupManager
import com.github.reygnn.kolibri_launcher.data.BackupRepository
import com.github.reygnn.kolibri_launcher.data.FavoritesOrderRepository
import com.github.reygnn.kolibri_launcher.data.FavoritesRepository
import com.github.reygnn.kolibri_launcher.data.InstalledAppsManager
import com.github.reygnn.kolibri_launcher.data.InstalledAppsRepository
import com.github.reygnn.kolibri_launcher.data.InstalledAppsStateManager
import com.github.reygnn.kolibri_launcher.data.InstalledAppsStateRepository
import com.github.reygnn.kolibri_launcher.data.ScreenLockManager
import com.github.reygnn.kolibri_launcher.data.ScreenLockRepository
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
    abstract fun bindSettingsRepository(
        settingsManager: SettingsManager
    ): SettingsRepository

    @Binds
    @Singleton
    abstract fun bindFavoritesRepository(
        favoritesManager: FavoritesManager
    ): FavoritesRepository

    @Binds
    @Singleton
    abstract fun bindAppUsageRepository(
        appUsageManager: AppUsageManager
    ): AppUsageRepository

    @Binds
    @Singleton
    abstract fun bindVisibilityRepository(
        visibilityManager: HiddenAppsManager
    ): HiddenAppsRepository

    @Binds
    @Singleton
    abstract fun bindFavoritesOrderRepository(
        favoritesOrderManager: FavoritesOrderManager
    ): FavoritesOrderRepository

    @Binds
    @Singleton
    abstract fun bindAppRepository(
        installedAppsManager: InstalledAppsManager
    ): InstalledAppsRepository

     @Binds
    @Singleton
    abstract fun bindAppNamesRepository(
        appNamesManager: CustomNamesManager
    ): CustomNamesRepository

    @Binds
    @Singleton
    abstract fun bindInstalledAppsStateRepository(
        impl: InstalledAppsStateManager
    ): InstalledAppsStateRepository

    @Binds
    @Singleton
    abstract fun bindGetFavoriteAppsUseCaseRepository(
        impl: GetFavoriteAppsUseCase
    ): GetFavoriteAppsUseCaseRepository

    @Binds
    @Singleton
    abstract fun bindGetDrawerAppsUseCaseRepository(
        impl: GetDrawerAppsUseCase
    ): GetDrawerAppsUseCaseRepository

    @Binds
    @Singleton
    abstract fun bindScreenLockRepository(
        screenLockManager: ScreenLockManager
    ): ScreenLockRepository

    @Binds
    @Singleton
    abstract fun bindShortcutRepository(
        shortcutManager: ShortcutManager
    ): ShortcutRepository

    @Binds
    @Singleton
    abstract fun bindGetOnboardingAppsUseCase(
        impl: GetOnboardingAppsUseCase
    ): GetOnboardingAppsUseCaseRepository

    @Binds
    @Singleton
    abstract fun bindSwipeActionsRepository(
        swipeActionsManager: SwipeActionsManager
    ): SwipeActionsRepository

    @Binds
    @Singleton
    abstract fun bindBackupRepository(
        backupManager: BackupManager
    ): BackupRepository

}