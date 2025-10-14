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

/**
 * @file TestDispatcherModule.kt
 * @description A Hilt module designed exclusively for instrumented and unit tests. Its purpose is to
 * replace the production `DispatcherModule`, enabling complete control over coroutine execution during tests.
 *
 * ROLE IN THE TESTING ARCHITECTURE:
 * This module acts as a "dependency seam." It leverages Hilt's `@TestInstallIn` annotation to intercept
 * any request for a `CoroutineDispatcher` and provide a single, controllable `TestDispatcher` instance instead.
 *
 * HOW IT WORKS:
 * 1.  **Replacement**: `@TestInstallIn(replaces = [DispatcherModule::class])` tells Hilt: "When running a
 *     test, ignore the real `DispatcherModule` and use this one instead."
 *
 * 2.  **Centralized Control**: It provides the *exact same* `TestDispatcher` instance for all three dispatcher
 *     qualifiers (`@DefaultDispatcher`, `@IoDispatcher`, `@MainDispatcher`). This is a powerful testing
 *     technique that funnels all asynchronous work in the application onto a single, manageable timeline.
 *     It eliminates complexity from multi-threaded operations during a test.
 *
 * 3.  **Static Injection Point**: The `setTestDispatcher` function serves as a static entry point.
 *     It allows the test setup class (`BaseAndroidTest`) to inject the `TestDispatcher` instance *before*
 *     Hilt begins its dependency resolution. Without this, the module would have no way of knowing which
 *     `TestDispatcher` instance to provide.
 *
 * LIFECYCLE:
 * - Before a test runs, `BaseAndroidTest.baseSetup()` calls `TestDispatcherModule.setTestDispatcher()`.
 * - Hilt then begins to construct the dependency graph for the test.
 * - When a class like `FavoritesSortFragment` requests a dispatcher via `@Inject`, Hilt consults this module.
 * - This module's `@Provides` functions return the `TestDispatcher` that was set just moments before.
 * - If `setTestDispatcher` was not called, the `error()` call ensures the test fails immediately with a
 *   clear message, preventing obscure bugs.
 */
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