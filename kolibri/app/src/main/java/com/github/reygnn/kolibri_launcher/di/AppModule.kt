package com.github.reygnn.kolibri_launcher.di

import android.app.WallpaperManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.SystemClock
import com.github.reygnn.kolibri_launcher.BuildConfig
import com.github.reygnn.kolibri_launcher.ui.main.AppLauncher
import com.github.reygnn.kolibri_launcher.ui.main.AppLauncherImpl
import com.github.reygnn.kolibri_launcher.ui.util.MonotonicClock
import com.github.reygnn.kolibri_launcher.ui.util.TestMode
import com.github.reygnn.kolibri_launcher.R
import com.github.reygnn.launcher.common.ui.wallpaper.WallpaperFlattenTheme
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

    // Theme for the shared WallpaperFlattener's off-screen AppCompat view.
    @Provides
    @WallpaperFlattenTheme
    fun provideWallpaperFlattenTheme(): Int = R.style.AppTheme

    /**
     * The app-launch seam. Behind an interface so tests can supply a fake that
     * returns a chosen [com.github.reygnn.kolibri_launcher.ui.main.AppLaunchResult]
     * without the real `LauncherApps` (see [AppLauncher] KDoc).
     */
    @Provides
    @Singleton
    fun provideAppLauncher(impl: AppLauncherImpl): AppLauncher = impl

    /**
     * Monotonic clock seam ([SystemClock.elapsedRealtime]) so the app-launch
     * double-tap throttle in
     * [com.github.reygnn.kolibri_launcher.ui.main.delegate.AppManagementDelegate]
     * stays JVM-testable with a fake clock.
     */
    @Provides
    @Singleton
    fun provideMonotonicClock(): MonotonicClock =
        MonotonicClock { SystemClock.elapsedRealtime() }

    // AcraToggle + ConsentSaveFailureNotifier bindings moved to
    // :feature-crashreporting (CrashReportConsentBindingModule) so both apps get
    // them and ConsentController is providable everywhere.

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