package com.github.reygnn.nyx_launcher.data.di

import android.content.Context
import android.content.pm.LauncherApps
import android.content.pm.PackageManager
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.flow.MutableSharedFlow
import javax.inject.Singleton

/** Android system services as injectable singletons (keeps consumers testable). */
@Module
@InstallIn(SingletonComponent::class)
object SystemServiceModule {

    // Provided app-side (decision B): the shared LauncherAppsEnumerator, bound in
    // Nyx's RepositoryModule, injects this — plus Nyx's own LauncherAppsIconSource /
    // PackageEventCoordinator. Not a duplicate of any :common-data provider.
    @Provides
    @Singleton
    fun provideLauncherApps(@ApplicationContext context: Context): LauncherApps =
        context.getSystemService(Context.LAUNCHER_APPS_SERVICE) as LauncherApps

    // Backs the reconcile deletion gate's cross-surface presence check (PackageManagerPresence)
    // and the install-session gate (PackageManagerInstallSessions) — a different subsystem
    // from LauncherApps on purpose (AUDIT-1 F7 review, fixes 2 + 3).
    @Provides
    @Singleton
    fun providePackageManager(@ApplicationContext context: Context): PackageManager =
        context.packageManager

    // Reload-trigger bus for the shared installed-apps motor (SHARED_INSTALLED_APPS_SPEC
    // §2 "Freshness"). replay=0, extraBufferCapacity=1 — robust for an "event" trigger.
    // Nyx had no such provider (it self-enumerated); the shared motor injects it.
    @Provides
    @Singleton
    fun provideAppsUpdateTrigger(): MutableSharedFlow<Unit> =
        MutableSharedFlow(replay = 0, extraBufferCapacity = 1)
}
