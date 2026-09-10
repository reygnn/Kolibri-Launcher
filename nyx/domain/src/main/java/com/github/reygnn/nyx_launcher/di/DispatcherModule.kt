package com.github.reygnn.nyx_launcher.di

import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers

/**
 * Declared in `:domain` (pure JVM) — only the `hilt-android` PLUGIN is absent
 * here; `@Module`/`@Provides` codegen comes from `ksp(hilt-compiler)`, the
 * aggregation runs in `:app` (see domain/build.gradle.kts). `Dispatchers.Main`
 * is deliberately NOT provided here — it needs `kotlinx-coroutines-android`
 * (an `:app`/`:data` runtime artifact); tests use `MainDispatcherRule`.
 */
@Module
@InstallIn(SingletonComponent::class)
object DispatcherModule {

    @Provides
    @DefaultDispatcher
    fun provideDefaultDispatcher(): CoroutineDispatcher = Dispatchers.Default

    @Provides
    @IoDispatcher
    fun provideIoDispatcher(): CoroutineDispatcher = Dispatchers.IO
}
