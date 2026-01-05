package com.github.reygnn.kolibri_launcher.data.service

import android.content.Context
import android.content.pm.LauncherApps
import android.content.pm.ShortcutInfo
import com.github.reygnn.kolibri_launcher.domain.service.ShortcutLaunchException
import com.github.reygnn.kolibri_launcher.domain.service.ShortcutLauncherService
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Implementierung von ShortcutLauncherService.
 *
 * Wrapped den Android LauncherApps System Service.
 */
@Singleton
class ShortcutLauncherServiceImpl @Inject constructor(
    @ApplicationContext private val context: Context
) : ShortcutLauncherService {

    private val launcherApps: LauncherApps? by lazy {
        context.getSystemService(Context.LAUNCHER_APPS_SERVICE) as? LauncherApps
    }

    override fun startShortcut(shortcut: ShortcutInfo) {
        val service = launcherApps
            ?: throw ShortcutLaunchException("LauncherApps service is not available")

        try {
            service.startShortcut(shortcut, null, null)
        } catch (e: Exception) {
            throw ShortcutLaunchException("Failed to start shortcut: ${shortcut.id}", e)
        }
    }

    override fun isAvailable(): Boolean = launcherApps != null
}