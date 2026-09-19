package com.github.reygnn.kolibri_launcher.di

import com.github.reygnn.kolibri_launcher.ui.main.AppLauncher
import com.github.reygnn.kolibri_launcher.ui.main.AppLauncherImpl
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

/**
 * The app-launch seam in its own module so an instrumented test can replace just
 * this binding (`@UninstallModules(AppLauncherModule::class)` + `@BindValue` a
 * fake), without disturbing the rest of [AppModule]. Behind an interface so a
 * test can supply a fake that returns a chosen
 * [com.github.reygnn.launcher.common.ui.AppLaunchResult] without the real
 * `LauncherApps` (see [AppLauncher] KDoc — `LauncherApps.startMainActivity` is a
 * system call Espresso Intents can't intercept, so the fake is the seam).
 */
@Module
@InstallIn(SingletonComponent::class)
object AppLauncherModule {
    @Provides
    @Singleton
    fun provideAppLauncher(impl: AppLauncherImpl): AppLauncher = impl
}
