package com.github.reygnn.kolibri_launcher.di

import android.app.WallpaperManager
import android.content.Context
import android.content.pm.LauncherApps
import android.content.pm.PackageManager
import android.os.SystemClock
import kotlinx.coroutines.flow.MutableSharedFlow
import com.github.reygnn.launcher.core.AppsUpdateTrigger
import com.github.reygnn.kolibri_launcher.BuildConfig
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

    // System LauncherApps service, for the shared LauncherAppsEnumerator
    // (SHARED_INSTALLED_APPS_SPEC §9.1). Provided app-side (like PackageManager /
    // WallpaperManager above), NOT by an auto-aggregated :common-data module — Nyx
    // keeps its own provider until Step E.
    @Provides
    @Singleton
    fun provideLauncherApps(@ApplicationContext context: Context): LauncherApps =
        context.getSystemService(Context.LAUNCHER_APPS_SERVICE) as LauncherApps

    // Reload-trigger bus feeding the shared installed-apps motor. replay=0,
    // extraBufferCapacity=1 — robust for an "event" trigger. Moved here from the
    // deleted AppUpdateModule. Qualified (@AppsUpdateTrigger) so this generic
    // MutableSharedFlow<Unit> can't collide with an unrelated future binding.
    @Provides
    @Singleton
    @AppsUpdateTrigger
    fun provideAppsUpdateTrigger(): MutableSharedFlow<Unit> =
        MutableSharedFlow(replay = 0, extraBufferCapacity = 1)

    // Theme for the shared WallpaperFlattener's off-screen AppCompat view.
    @Provides
    @WallpaperFlattenTheme
    fun provideWallpaperFlattenTheme(): Int = R.style.AppTheme

    // The app-launch seam (provideAppLauncher) moved to AppLauncherModule so an
    // instrumented test can replace just that binding without uninstalling the
    // rest of AppModule.

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