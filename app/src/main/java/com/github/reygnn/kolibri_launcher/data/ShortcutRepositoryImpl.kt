package com.github.reygnn.kolibri_launcher.data

import android.content.Context
import android.content.Intent
import android.content.pm.LauncherApps
import android.content.pm.ShortcutInfo
import android.os.Process
import com.github.reygnn.kolibri_launcher.core.TimberWrapper
import com.github.reygnn.kolibri_launcher.domain.repository.ShortcutRepository
import dagger.hilt.android.qualifiers.ApplicationContext
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class ShortcutRepositoryImpl @Inject constructor(
    @param:ApplicationContext private val context: Context
) : ShortcutRepository {

    private val launcherApps: LauncherApps? by lazy {
        try {
            context.getSystemService(Context.LAUNCHER_APPS_SERVICE) as? LauncherApps
        } catch (e: Throwable) {  // Throwable statt Exception
            TimberWrapper.silentError(e, "Failed to get LauncherApps service")
            null
        }
    }

    private fun isDefaultLauncher(): Boolean {
        return try {
            val intent = Intent(Intent.ACTION_MAIN).apply {
                addCategory(Intent.CATEGORY_HOME)
            }
            val resolveInfo = context.packageManager.resolveActivity(intent, 0)
            resolveInfo?.activityInfo?.packageName == context.packageName
        } catch (e: Throwable) {  // Throwable statt Exception
            TimberWrapper.silentError(e, "Failed to check default launcher status")
            false
        }
    }

    override fun getShortcutsForPackage(packageName: String): List<ShortcutInfo> {
        if (packageName.isBlank()) {
            Timber.Forest.w("Attempted to get shortcuts for blank package name")
            return emptyList()
        }

        if (!isDefaultLauncher()) {
            Timber.Forest.d("Not the default launcher - cannot access shortcuts for $packageName")
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
            Timber.Forest.i("Shortcut access denied for $packageName - launcher status may have changed")
            emptyList()
        } catch (e: IllegalStateException) {
            TimberWrapper.silentError(e, "User locked or LauncherApps unavailable for: $packageName")
            emptyList()
        } catch (e: IllegalArgumentException) {
            TimberWrapper.silentError(e, "Invalid package name: $packageName")
            emptyList()
        } catch (e: Throwable) {  // Throwable statt Exception
            TimberWrapper.silentError(e, "Unexpected error while retrieving shortcuts for $packageName")
            emptyList()
        }
    }

    override suspend fun purgeRepository() {
        // NICHTS TUN!
        // Der ShortcutRepositoryImpl liest Shortcuts direkt vom Android LauncherApps Service.
        // Diese System-Daten (App Shortcuts) werden von den installierten Apps bereitgestellt
        // und können/sollten nicht durch einen Factory Reset geleert werden.
        // Shortcuts sind keine User-Einstellungen, sondern App-Metadaten.
    }
}