package com.github.reygnn.nyx_launcher.di

import com.github.reygnn.launcher.common.ui.AppLauncher
import com.github.reygnn.launcher.common.ui.AppLauncherImpl
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

/**
 * Binds the shared [AppLauncher] (`:common-ui`, `LauncherApps.startMainActivity`) so Nyx
 * launches apps the launcher-idiomatic, work-profile-capable way instead of an explicit
 * `startActivity(Intent(ACTION_MAIN/LAUNCHER))` — parity with Kolibri / Launcher3.
 *
 * Own module (mirrors Kolibri's `AppLauncherModule`) so an instrumented test can replace just
 * this binding (`@UninstallModules(AppLauncherModule::class)` + `@BindValue` a fake) without
 * touching the rest of the graph — the real `startMainActivity` is a system call Espresso
 * Intents can't intercept.
 */
@Module
@InstallIn(SingletonComponent::class)
object AppLauncherModule {
    @Provides
    @Singleton
    fun provideAppLauncher(impl: AppLauncherImpl): AppLauncher = impl
}
