package com.github.reygnn.nyx_launcher.data.di

import com.github.reygnn.nyx_launcher.data.home.HomeLayoutRepositoryImpl
import com.github.reygnn.nyx_launcher.data.home.HomeLayoutSerializer
import com.github.reygnn.nyx_launcher.data.home.InstalledAppsRepositoryImpl
import com.github.reygnn.nyx_launcher.data.home.PreferencesRepositoryImpl
import com.github.reygnn.nyx_launcher.data.home.UuidItemIdFactory
import com.github.reygnn.nyx_launcher.home.model.ItemIdFactory
import com.github.reygnn.nyx_launcher.home.repository.HomeLayoutRepository
import com.github.reygnn.nyx_launcher.home.repository.LayoutSerializer
import com.github.reygnn.nyx_launcher.home.repository.PreferencesRepository
import com.github.reygnn.nyx_launcher.home.repository.InstalledAppsRepository
import com.github.reygnn.launcher.common.data.timeinfo.TimeBasedEventsRepositoryImpl
import com.github.reygnn.launcher.core.timeinfo.TimeBasedEventsRepository
import com.github.reygnn.launcher.core.timeinfo.TimeInfoSettings
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

/** Binds domain repository interfaces to their `:data` implementations (rule 1). */
@Module
@InstallIn(SingletonComponent::class)
abstract class RepositoryModule {

    @Binds
    @Singleton
    abstract fun bindHomeLayoutRepository(impl: HomeLayoutRepositoryImpl): HomeLayoutRepository

    @Binds
    abstract fun bindLayoutSerializer(impl: HomeLayoutSerializer): LayoutSerializer

    @Binds
    @Singleton
    abstract fun bindPreferencesRepository(impl: PreferencesRepositoryImpl): PreferencesRepository

    @Binds
    @Singleton
    abstract fun bindInstalledAppsRepository(impl: InstalledAppsRepositoryImpl): InstalledAppsRepository

    // Home-info subsystem (HIE Phase C): the narrow settings port is Nyx's
    // PreferencesRepository; the calendar/alarm reader is the shared :common-data impl.
    @Binds
    abstract fun bindTimeInfoSettings(impl: PreferencesRepository): TimeInfoSettings

    @Binds
    @Singleton
    abstract fun bindTimeBasedEventsRepository(impl: TimeBasedEventsRepositoryImpl): TimeBasedEventsRepository

    @Binds
    abstract fun bindItemIdFactory(impl: UuidItemIdFactory): ItemIdFactory
}
