package com.github.reygnn.kolibri_launcher

import dagger.Module
import dagger.Provides
import dagger.hilt.components.SingletonComponent
import dagger.hilt.testing.TestInstallIn
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.test.TestDispatcher
import javax.inject.Singleton

@OptIn(ExperimentalCoroutinesApi::class)
@Module
@TestInstallIn(
    components = [SingletonComponent::class],
    replaces = [DispatcherModule::class]
)
object TestDispatcherModule {

    private var testDispatcher: TestDispatcher? = null

    /**
     * Wird von BaseAndroidTest aufgerufen, um den TestDispatcher zu setzen
     */
    fun setTestDispatcher(dispatcher: TestDispatcher) {
        testDispatcher = dispatcher
    }

    @Provides
    @DefaultDispatcher
    fun provideDefaultDispatcher(): CoroutineDispatcher {
        return testDispatcher ?: error("TestDispatcher not initialized! Call TestDispatcherModule.setTestDispatcher() in test setup")
    }

    @Provides
    @IoDispatcher
    fun provideIoDispatcher(): CoroutineDispatcher {
        return testDispatcher ?: error("TestDispatcher not initialized! Call TestDispatcherModule.setTestDispatcher() in test setup")
    }

    @Provides
    @MainDispatcher
    fun provideMainDispatcher(): CoroutineDispatcher {
        return testDispatcher ?: error("TestDispatcher not initialized! Call TestDispatcherModule.setTestDispatcher() in test setup")
    }

    @Provides
    @Singleton
    @ApplicationScope
    fun provideApplicationScope(
        @DefaultDispatcher defaultDispatcher: CoroutineDispatcher
    ): CoroutineScope {
        return CoroutineScope(SupervisorJob() + defaultDispatcher)
    }
}