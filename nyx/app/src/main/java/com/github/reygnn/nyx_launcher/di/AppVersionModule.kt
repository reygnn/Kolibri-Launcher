package com.github.reygnn.nyx_launcher.di

import com.github.reygnn.nyx_launcher.BuildConfig
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Named

/**
 * The app's version name for `:data`, which has no BuildConfig of the app (2b-3a): the
 * backup manifest records which version wrote a backup. Same qualifier as Kolibri's.
 */
@Module
@InstallIn(SingletonComponent::class)
object AppVersionModule {
    @Provides
    @Named("appVersionName")
    fun provideAppVersionName(): String = BuildConfig.VERSION_NAME
}
