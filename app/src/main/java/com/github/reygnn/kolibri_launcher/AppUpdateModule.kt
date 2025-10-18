package com.github.reygnn.kolibri_launcher

import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.flow.MutableSharedFlow
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object AppUpdateModule {

    @Singleton
    @Provides
    fun provideAppsUpdateTrigger(): MutableSharedFlow<Unit> {
        // replay=0, extraBufferCapacity=1 ist eine robuste Konfiguration für "Event"-Trigger.
        return MutableSharedFlow(replay = 0, extraBufferCapacity = 1)
    }
}