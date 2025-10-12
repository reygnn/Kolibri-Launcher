package com.github.reygnn.kolibri_launcher


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
        visibilityManager: AppVisibilityManager
    ): AppVisibilityRepository

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
        appNamesManager: AppNamesManager
    ): AppNamesRepository

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


}