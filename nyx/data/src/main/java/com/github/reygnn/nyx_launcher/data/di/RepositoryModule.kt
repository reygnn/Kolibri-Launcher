package com.github.reygnn.nyx_launcher.data.di

import com.github.reygnn.nyx_launcher.data.home.AppUsageRepositoryImpl
import com.github.reygnn.nyx_launcher.data.home.DrawerFoldersRepositoryImpl
import com.github.reygnn.nyx_launcher.data.home.HiddenAppsRepositoryImpl
import com.github.reygnn.nyx_launcher.data.home.UuidDrawerFolderIdFactory
import com.github.reygnn.nyx_launcher.data.home.HomeLayoutRepositoryImpl
import com.github.reygnn.nyx_launcher.data.home.HomeLayoutSerializer
// Shared installed-apps subsystem (SHARED_INSTALLED_APPS_SPEC §3), bound app-side
// in Nyx exactly like Kolibri (decision B: no auto-aggregated :common-data module).
import com.github.reygnn.launcher.core.AppEnumerator
import com.github.reygnn.launcher.core.InstalledAppsRepository
import com.github.reygnn.launcher.common.data.installedapps.LauncherAppsEnumerator
import com.github.reygnn.launcher.common.data.installedapps.InstalledAppsRepositoryImpl
import com.github.reygnn.nyx_launcher.data.installedapps.LauncherAppsPresence
import com.github.reygnn.nyx_launcher.home.service.AppPresence
import com.github.reygnn.nyx_launcher.data.home.NyxWallpaperDisplaySettings
import com.github.reygnn.nyx_launcher.data.home.PreferencesRepositoryImpl
import com.github.reygnn.nyx_launcher.data.home.UuidItemIdFactory
import com.github.reygnn.launcher.common.data.wallpaper.WallpaperRepositoryImpl
import com.github.reygnn.launcher.core.wallpaper.WallpaperDisplaySettings
import com.github.reygnn.launcher.core.wallpaper.WallpaperRepository
import com.github.reygnn.nyx_launcher.home.model.ItemIdFactory
import com.github.reygnn.nyx_launcher.home.model.DrawerFolderIdFactory
import com.github.reygnn.nyx_launcher.home.repository.AppUsageRepository
import com.github.reygnn.nyx_launcher.home.repository.DrawerFoldersRepository
import com.github.reygnn.nyx_launcher.home.repository.HiddenAppsRepository
import com.github.reygnn.nyx_launcher.home.repository.HomeLayoutRepository
import com.github.reygnn.nyx_launcher.home.repository.LayoutSerializer
import com.github.reygnn.nyx_launcher.home.repository.PreferencesRepository
import com.github.reygnn.launcher.common.data.timeinfo.TimeBasedEventsRepositoryImpl
import com.github.reygnn.launcher.core.timeinfo.TimeBasedEventsRepository
import com.github.reygnn.launcher.core.timeinfo.TimeInfoSettings
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

/** Binds domain repository interfaces to their `:data` implementations (rule 1). */
@Module
@InstallIn(SingletonComponent::class)
abstract class RepositoryModule {

    @Binds
    @Singleton
    abstract fun bindHomeLayoutRepository(impl: HomeLayoutRepositoryImpl): HomeLayoutRepository

    // Drawer folders (DRAWER_FOLDERS_SPEC §4 / D-5): own repository over the shared
    // DataStore under its own key, independent of the home layout.
    @Binds
    @Singleton
    abstract fun bindDrawerFoldersRepository(impl: DrawerFoldersRepositoryImpl): DrawerFoldersRepository

    @Binds
    abstract fun bindDrawerFolderIdFactory(impl: UuidDrawerFolderIdFactory): DrawerFolderIdFactory

    // Hidden apps: own repository over the shared DataStore under its own key, filtered out
    // of the drawer projection (display-only, independent of layout and folders).
    @Binds
    @Singleton
    abstract fun bindHiddenAppsRepository(impl: HiddenAppsRepositoryImpl): HiddenAppsRepository

    // App usage: own repository over a SEPARATE DataStore (highest-frequency write), drives
    // the drawer's time-weighted usage sort.
    @Binds
    @Singleton
    abstract fun bindAppUsageRepository(impl: AppUsageRepositoryImpl): AppUsageRepository

    @Binds
    abstract fun bindLayoutSerializer(impl: HomeLayoutSerializer): LayoutSerializer

    @Binds
    @Singleton
    abstract fun bindPreferencesRepository(impl: PreferencesRepositoryImpl): PreferencesRepository

    // ── Shared installed-apps subsystem (:core port → :common-data impls, decision B) ──
    // The LauncherApps-backed motor (drawer loader) and the enumerator (read directly
    // by the fail-closed reconcile). LauncherApps + the reload trigger are provided by
    // Nyx's SystemServiceModule. Nyx's own local InstalledAppsRepositoryImpl +
    // AppLoadResult port were retired in E3.
    @Binds
    @Singleton
    abstract fun bindInstalledAppsRepository(impl: InstalledAppsRepositoryImpl): InstalledAppsRepository

    @Binds
    @Singleton
    abstract fun bindAppEnumerator(impl: LauncherAppsEnumerator): AppEnumerator

    // Per-target deletion gate for the fail-closed reconcile (AUDIT-1 F7, RHL-INV-6):
    // the Nyx analog of Kolibri's PackagePresence, over the same shared LauncherApps seam.
    @Binds
    @Singleton
    abstract fun bindAppPresence(impl: LauncherAppsPresence): AppPresence

    // Home-info subsystem (HIE Phase C): the narrow settings port is Nyx's
    // PreferencesRepository; the calendar/alarm reader is the shared :common-data impl.
    @Binds
    abstract fun bindTimeInfoSettings(impl: PreferencesRepository): TimeInfoSettings

    @Binds
    @Singleton
    abstract fun bindTimeBasedEventsRepository(impl: TimeBasedEventsRepositoryImpl): TimeBasedEventsRepository

    // Wallpaper (WV5): state persistence via the shared :common-data impl (over Nyx's
    // home_layout DataStore + WallpaperFileManager); the narrow display-settings port
    // via Nyx's own small adapter (WALLPAPER_SHARE_SPEC §5).
    @Binds
    @Singleton
    abstract fun bindWallpaperRepository(impl: WallpaperRepositoryImpl): WallpaperRepository

    @Binds
    @Singleton
    abstract fun bindWallpaperDisplaySettings(impl: NyxWallpaperDisplaySettings): WallpaperDisplaySettings

    @Binds
    abstract fun bindItemIdFactory(impl: UuidItemIdFactory): ItemIdFactory
}
