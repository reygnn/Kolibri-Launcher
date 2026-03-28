package com.github.reygnn.kolibri_launcher.di

import com.github.reygnn.kolibri_launcher.data.TestDataSource
import com.github.reygnn.kolibri_launcher.domain.repository.AppUsageRepository
import com.github.reygnn.kolibri_launcher.domain.repository.BackupRepository
import com.github.reygnn.kolibri_launcher.domain.repository.CustomNamesRepository
import com.github.reygnn.kolibri_launcher.domain.repository.FavoritesOrderRepository
import com.github.reygnn.kolibri_launcher.domain.repository.FavoritesRepository
import com.github.reygnn.kolibri_launcher.domain.repository.GetDrawerAppsUseCaseRepository
import com.github.reygnn.kolibri_launcher.domain.repository.GetFavoriteAppsUseCaseRepository
import com.github.reygnn.kolibri_launcher.domain.repository.GetOnboardingAppsUseCaseRepository
import com.github.reygnn.kolibri_launcher.domain.repository.HiddenAppsRepository
import com.github.reygnn.kolibri_launcher.domain.repository.InstalledAppsRepository
import com.github.reygnn.kolibri_launcher.domain.repository.InstalledAppsStateRepository
import com.github.reygnn.kolibri_launcher.domain.repository.ResetRepository
import com.github.reygnn.kolibri_launcher.domain.repository.ScreenLockRepository
import com.github.reygnn.kolibri_launcher.domain.repository.SettingsRepository
import com.github.reygnn.kolibri_launcher.domain.repository.ShortcutRepository
import com.github.reygnn.kolibri_launcher.domain.repository.SwipeActionsRepository
import com.github.reygnn.kolibri_launcher.domain.repository.TimeBasedEventsRepository
import com.github.reygnn.kolibri_launcher.fakes.FakeAppUpdateSignal
import com.github.reygnn.kolibri_launcher.fakes.FakeAppUsageRepository
import com.github.reygnn.kolibri_launcher.fakes.FakeBackupRepository
import com.github.reygnn.kolibri_launcher.fakes.FakeCustomNamesRepository
import com.github.reygnn.kolibri_launcher.fakes.FakeFavoritesOrderRepository
import com.github.reygnn.kolibri_launcher.fakes.FakeFavoritesRepository
import com.github.reygnn.kolibri_launcher.fakes.FakeGetDrawerAppsUseCaseRepository
import com.github.reygnn.kolibri_launcher.fakes.FakeGetFavoriteAppsUseCaseRepository
import com.github.reygnn.kolibri_launcher.fakes.FakeGetOnboardingAppsUseCaseRepository
import com.github.reygnn.kolibri_launcher.fakes.FakeHiddenAppsRepository
import com.github.reygnn.kolibri_launcher.fakes.FakeInstalledAppsRepository
import com.github.reygnn.kolibri_launcher.fakes.FakeInstalledAppsStateRepository
import com.github.reygnn.kolibri_launcher.fakes.FakeResetRepository
import com.github.reygnn.kolibri_launcher.fakes.FakeScreenLockRepository
import com.github.reygnn.kolibri_launcher.fakes.FakeSettingsRepository
import com.github.reygnn.kolibri_launcher.fakes.FakeShortcutRepository
import com.github.reygnn.kolibri_launcher.fakes.FakeSwipeActionsRepository
import com.github.reygnn.kolibri_launcher.fakes.FakeTimeBasedEventsRepository
import com.github.reygnn.kolibri_launcher.ui.util.AppUpdateSignal
import dagger.Module
import dagger.Provides
import dagger.hilt.components.SingletonComponent
import dagger.hilt.testing.TestInstallIn
import javax.inject.Singleton

/**
 * Test-Modul für Domain-Level Dependencies (Repositories, UseCases).
 * Ersetzt RepositoryModule in Tests.
 */
@Module
@TestInstallIn(
    components = [SingletonComponent::class],
    replaces = [RepositoryModule::class]
)
object TestRepositoryModule {

    @Provides
    @Singleton
    fun provideInstalledAppsRepository(): InstalledAppsRepository {
        // Erstellt die Singleton-Instanz und füllt sie mit den initialen Daten.
        return FakeInstalledAppsRepository().apply {
            appsFlow.value = TestDataSource.getProcessedList()
        }
    }

    @Provides
    @Singleton
    fun provideCustomNamesRepository(
        // Hilt injiziert hier die Singleton-Instanz von oben.
        installedAppsRepo: InstalledAppsRepository
    ): CustomNamesRepository {
        // Erstellt das AppNamesRepository und gibt ihm die Fähigkeit, das
        // InstalledAppsRepository zu aktualisieren, wenn sich ein Name ändert.
        return FakeCustomNamesRepository(
            onNameChanged = {
                val newList = TestDataSource.getProcessedList()
                (installedAppsRepo as FakeInstalledAppsRepository).appsFlow.value = newList
            }
        )
    }

    @Provides
    @Singleton
    fun provideFavoritesRepository(): FavoritesRepository = FakeFavoritesRepository()

    @Provides
    @Singleton
    fun provideAppVisibilityRepository(): HiddenAppsRepository = FakeHiddenAppsRepository()

    @Provides
    @Singleton
    fun provideSettingsRepository(): SettingsRepository = FakeSettingsRepository()

    @Provides
    @Singleton
    fun provideAppUsageRepository(): AppUsageRepository = FakeAppUsageRepository()

    @Provides
    @Singleton
    fun provideFavoritesOrderRepository(): FavoritesOrderRepository = FakeFavoritesOrderRepository()

    @Provides
    @Singleton
    fun provideInstalledAppsStateRepository(): InstalledAppsStateRepository =
        FakeInstalledAppsStateRepository()

    @Provides
    @Singleton
    fun provideGetFavoriteAppsUseCaseRepository(): GetFavoriteAppsUseCaseRepository =
        FakeGetFavoriteAppsUseCaseRepository()

    @Provides
    @Singleton
    fun provideGetDrawerAppsUseCaseRepository(): GetDrawerAppsUseCaseRepository =
        FakeGetDrawerAppsUseCaseRepository()

    @Provides
    @Singleton
    fun provideScreenLockRepository(): ScreenLockRepository = FakeScreenLockRepository()

    @Provides
    @Singleton
    fun provideShortcutRepository(): ShortcutRepository = FakeShortcutRepository()

    @Provides
    @Singleton
    fun provideGetOnboardingAppsUseCase(): GetOnboardingAppsUseCaseRepository =
        FakeGetOnboardingAppsUseCaseRepository()

    @Provides
    @Singleton
    fun provideAppUpdateSignal(): AppUpdateSignal = FakeAppUpdateSignal()

    @Provides
    @Singleton
    fun provideSwipeActionsRepository(): SwipeActionsRepository = FakeSwipeActionsRepository()


    @Provides
    @Singleton
    fun provideBackupRepository(): BackupRepository = FakeBackupRepository()

    @Provides
    @Singleton
    fun provideTimeBasedEventsRepository(): TimeBasedEventsRepository =
        FakeTimeBasedEventsRepository()

    @Provides
    @Singleton
    fun provideResetRepository(): ResetRepository = FakeResetRepository()
}