package com.github.reygnn.kolibri_launcher.di

import android.app.WallpaperManager
import android.content.Context
import android.content.pm.PackageManager
import com.github.reygnn.kolibri_launcher.BuildConfig
import com.github.reygnn.kolibri_launcher.ui.main.AppLauncher
import com.github.reygnn.kolibri_launcher.ui.main.AppLauncherImpl
import com.github.reygnn.kolibri_launcher.ui.util.TestMode
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Named
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object AppModule {
    @Provides
    @Singleton
    fun provideTestMode(): TestMode {
        return TestMode(isEnabled = false)
    }

    @Provides
    @Singleton
    fun providePackageManager(@ApplicationContext context: Context): PackageManager {
        return context.packageManager
    }

    @Provides
    @Singleton
    fun provideWallpaperManager(@ApplicationContext context: Context): WallpaperManager {
        return WallpaperManager.getInstance(context)
    }

    /**
     * The app-launch seam. Behind an interface so tests can supply a fake that
     * returns a chosen [com.github.reygnn.kolibri_launcher.ui.main.AppLaunchResult]
     * without the real `LauncherApps` (see [AppLauncher] KDoc).
     */
    @Provides
    @Singleton
    fun provideAppLauncher(): AppLauncher = AppLauncherImpl()

    /**
     * The app's `versionName` from :app's BuildConfig, exposed for injection
     * into :data classes (BackupDataAssembler, UsageExportRepositoryImpl)
     * that record it in backup payloads. :data has its own BuildConfig but
     * not VERSION_NAME, so we centralise the source of truth here.
     */
    @Provides
    @Singleton
    @Named("appVersionName")
    fun provideAppVersionName(): String = BuildConfig.VERSION_NAME
}