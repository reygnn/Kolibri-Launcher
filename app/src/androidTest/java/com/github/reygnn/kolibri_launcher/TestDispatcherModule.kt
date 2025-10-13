package com.github.reygnn.kolibri_launcher

import dagger.Module
import dagger.Provides
import dagger.hilt.testing.TestInstallIn
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import javax.inject.Singleton
import kotlinx.coroutines.SupervisorJob
import dagger.hilt.components.SingletonComponent

@Module
@TestInstallIn(
    components = [SingletonComponent::class],
    replaces = [DispatcherModule::class]
)
object TestDispatcherModule {

    @Provides
    @DefaultDispatcher
    fun provideDefaultDispatcher(): CoroutineDispatcher =
        kotlinx.coroutines.test.UnconfinedTestDispatcher()

    @Provides
    @IoDispatcher
    fun provideIoDispatcher(): CoroutineDispatcher =
        kotlinx.coroutines.test.UnconfinedTestDispatcher()

    @Provides
    @MainDispatcher
    fun provideMainDispatcher(): CoroutineDispatcher =
        kotlinx.coroutines.test.UnconfinedTestDispatcher()

    @Provides
    @Singleton
    @ApplicationScope
    fun provideApplicationScope(
        @DefaultDispatcher defaultDispatcher: CoroutineDispatcher
    ): CoroutineScope {
        return CoroutineScope(SupervisorJob() + defaultDispatcher)
    }
}