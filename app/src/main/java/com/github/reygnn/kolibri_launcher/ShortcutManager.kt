/*
    * Copyright (C) 2025 reygnn (Ulrich Kaufmann)
    *
    * This program is free software: you can redistribute it and/or modify
    * it under the terms of the GNU General Public License as published by
    * the Free Software Foundation, either version 3 of the License, or
    * (at your option) any later version.
    */

package com.github.reygnn.kolibri_launcher

import android.content.Context
import android.content.Intent
import android.content.pm.LauncherApps
import android.content.pm.ShortcutInfo
import android.os.Process
import dagger.hilt.android.qualifiers.ApplicationContext
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class ShortcutManager @Inject constructor(
    @param:ApplicationContext private val context: Context
) : ShortcutRepository {

    private val launcherApps: LauncherApps? by lazy {
        try {
            context.getSystemService(Context.LAUNCHER_APPS_SERVICE) as? LauncherApps
        } catch (e: Exception) {
            TimberWrapper.silentError(e, "Failed to get LauncherApps service")
            null
        }
    }

    /**
     * Prüft, ob diese App der aktuelle Default Launcher ist
     */
    private fun isDefaultLauncher(): Boolean {
        return try {
            val intent = Intent(Intent.ACTION_MAIN).apply {
                addCategory(Intent.CATEGORY_HOME)
            }
            val resolveInfo = context.packageManager.resolveActivity(
                intent,
                0
            )
            resolveInfo?.activityInfo?.packageName == context.packageName
        } catch (e: Exception) {
            TimberWrapper.silentError(e, "Failed to check default launcher status")
            false
        }
    }

    override fun getShortcutsForPackage(packageName: String): List<ShortcutInfo> {
        if (packageName.isBlank()) {
            Timber.w("Attempted to get shortcuts for blank package name")
            return emptyList()
        }

        if (!isDefaultLauncher()) {
            Timber.d("Not the default launcher - cannot access shortcuts for $packageName")
            return emptyList()
        }

        val service = launcherApps ?: run {
            TimberWrapper.silentError("LauncherApps service not available")
            return emptyList()
        }

        return try {
            val query = LauncherApps.ShortcutQuery().apply {
                setPackage(packageName)
                setQueryFlags(
                    LauncherApps.ShortcutQuery.FLAG_MATCH_DYNAMIC or
                            LauncherApps.ShortcutQuery.FLAG_MATCH_MANIFEST or
                            LauncherApps.ShortcutQuery.FLAG_MATCH_PINNED
                )
            }

            service.getShortcuts(query, Process.myUserHandle()) ?: emptyList()

        } catch (e: SecurityException) {
            // Das ist KEIN Fehler, sondern erwartetes Verhalten wenn nicht Default Launcher
            // Fallback: Race-Condition zwischen isDefaultLauncher-Check und getShortcuts
            Timber.i("Shortcut access denied for $packageName - launcher status may have changed")
            emptyList()
        } catch (e: IllegalStateException) {
            TimberWrapper.silentError(e, "User locked or LauncherApps unavailable for: $packageName")
            emptyList()
        } catch (e: IllegalArgumentException) {
            TimberWrapper.silentError(e, "Invalid package name: $packageName")
            emptyList()
        } catch (e: Exception) {
            TimberWrapper.silentError(e, "Unexpected error while retrieving shortcuts for $packageName")
            emptyList()
        }
    }

    override fun purgeRepository() {
        // Für Tests - keine Implementierung nötig in Production
    }
}