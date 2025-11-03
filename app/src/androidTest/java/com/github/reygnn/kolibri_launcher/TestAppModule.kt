package com.github.reygnn.kolibri_launcher

import android.content.Context
import android.content.pm.PackageManager
import com.github.reygnn.kolibri_launcher.di.AppModule
import dagger.Module
import dagger.Provides
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import dagger.hilt.testing.TestInstallIn
import javax.inject.Singleton

/**
 * Test-Modul für System-Level Dependencies.
 * Ersetzt AppModule in Tests.
 */
@Module
@TestInstallIn(
    components = [SingletonComponent::class],
    replaces = [AppModule::class]
)
object TestAppModule {

    @Provides
    @Singleton
    fun provideTestMode(): TestMode {
        return TestMode(isEnabled = true)
    }

    @Provides
    @Singleton
    fun providePackageManager(@ApplicationContext context: Context): PackageManager {
        return context.packageManager
    }
}
