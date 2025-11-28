package com.github.reygnn.kolibri_launcher.di

//import com.github.reygnn.kolibri_launcher.domain.usecase.GetDrawerAppsUseCase
//import com.github.reygnn.kolibri_launcher.domain.repository.GetDrawerAppsUseCaseRepository
//import com.github.reygnn.kolibri_launcher.domain.usecase.GetFavoriteAppsUseCase
//import com.github.reygnn.kolibri_launcher.domain.repository.GetFavoriteAppsUseCaseRepository
//import com.github.reygnn.kolibri_launcher.domain.usecase.GetOnboardingAppsUseCase
//import com.github.reygnn.kolibri_launcher.domain.repository.GetOnboardingAppsUseCaseRepository
import com.github.reygnn.kolibri_launcher.data.AppUsageManager
import com.github.reygnn.kolibri_launcher.data.BackupManager
import com.github.reygnn.kolibri_launcher.data.CustomNamesManager
import com.github.reygnn.kolibri_launcher.data.FavoritesManager
import com.github.reygnn.kolibri_launcher.data.FavoritesOrderManager
import com.github.reygnn.kolibri_launcher.data.HiddenAppsManager
import com.github.reygnn.kolibri_launcher.data.InstalledAppsManager
import com.github.reygnn.kolibri_launcher.data.InstalledAppsStateManager
import com.github.reygnn.kolibri_launcher.data.ResetManager
import com.github.reygnn.kolibri_launcher.data.ScreenLockManager
import com.github.reygnn.kolibri_launcher.data.SettingsManager
import com.github.reygnn.kolibri_launcher.data.ShortcutManager
import com.github.reygnn.kolibri_launcher.data.SwipeActionsManager
import com.github.reygnn.kolibri_launcher.data.TimeBasedEventsManager
import com.github.reygnn.kolibri_launcher.domain.repository.AppUsageRepository
import com.github.reygnn.kolibri_launcher.domain.repository.BackupRepository
import com.github.reygnn.kolibri_launcher.domain.repository.CustomNamesRepository
import com.github.reygnn.kolibri_launcher.domain.repository.FavoritesOrderRepository
import com.github.reygnn.kolibri_launcher.domain.repository.FavoritesRepository
import com.github.reygnn.kolibri_launcher.domain.repository.HiddenAppsRepository
import com.github.reygnn.kolibri_launcher.domain.repository.InstalledAppsRepository
import com.github.reygnn.kolibri_launcher.domain.repository.InstalledAppsStateRepository
import com.github.reygnn.kolibri_launcher.domain.repository.ResetRepository
import com.github.reygnn.kolibri_launcher.domain.repository.ScreenLockRepository
import com.github.reygnn.kolibri_launcher.domain.repository.SettingsRepository
import com.github.reygnn.kolibri_launcher.domain.repository.ShortcutRepository
import com.github.reygnn.kolibri_launcher.domain.repository.SwipeActionsRepository
import com.github.reygnn.kolibri_launcher.domain.repository.TimeBasedEventsRepository
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
abstract class RepositoryModule {

//    @Binds
//    @Singleton
//    abstract fun bindGetOnboardingAppsUseCase(
//        impl: GetOnboardingAppsUseCase
//    ): GetOnboardingAppsUseCaseRepository
//
//    @Binds
//    @Singleton
//    abstract fun bindGetDrawerAppsUseCaseRepository(
//        impl: GetDrawerAppsUseCase
//    ): GetDrawerAppsUseCaseRepository
//
//    @Binds
//    @Singleton
//    abstract fun bindGetFavoriteAppsUseCaseRepository(
//        impl: GetFavoriteAppsUseCase
//    ): GetFavoriteAppsUseCaseRepository

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
    abstract fun bindSwipeActionsRepository(
        swipeActionsManager: SwipeActionsManager
    ): SwipeActionsRepository

    @Binds
    @Singleton
    abstract fun bindBackupRepository(
        backupManager: BackupManager
    ): BackupRepository

    @Binds
    @Singleton
    abstract fun bindCalendarRepository(
        impl: TimeBasedEventsManager
    ): TimeBasedEventsRepository

    @Binds
    @Singleton
    abstract fun bindResetRepository(
        resetManager: ResetManager
    ): ResetRepository

}