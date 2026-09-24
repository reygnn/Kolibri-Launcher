package com.github.reygnn.launcher.common.data.installedapps

import android.content.pm.PackageManager
import com.github.reygnn.launcher.core.InstallSessionInspector
import com.github.reygnn.launcher.core.IoDispatcher
import com.github.reygnn.launcher.core.TimberWrapper
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * [InstallSessionInspector] over `PackageManager.getPackageInstaller()` (AUDIT-1 F7 review,
 * fix 3). A package counts as mid-install/restore iff there is an active session whose
 * target package matches — the same source AOSP Launcher3 consults before it keeps a
 * placement as a promise icon.
 *
 * Uses `getAllSessions()` (not `getMySessions()`): a restore/install session is created by
 * the installer or the restore agent, not by this launcher, so we must see sessions we do
 * not own. Foreign-session visibility (and a non-null `appPackageName`) is granted to the
 * active default home app; when nyx is not the current launcher this may return nothing, in
 * which case the presence check and its fail-safe still govern.
 *
 * **Fail-safe:** any failure resolves to `true` (keep). If sessions cannot be read, the
 * conservative choice is to not prune — a lingering dead placement self-heals on the next
 * reconcile once the query works, whereas a wrongly-pruned placement during restore is
 * unrecoverable. The broad `Throwable` catch is the sanctioned system-API-boundary form;
 * `CancellationException` still propagates. Runs on [dispatcher] (the query is blocking);
 * consulted only for a candidate the presence check already reported absent.
 */
@Singleton
class PackageManagerInstallSessions @Inject constructor(
    private val packageManager: PackageManager,
    @param:IoDispatcher private val dispatcher: CoroutineDispatcher,
) : InstallSessionInspector {

    override suspend fun hasActiveSession(packageName: String): Boolean = withContext(dispatcher) {
        try {
            packageManager.packageInstaller.allSessions.any { session ->
                session.isActive && session.appPackageName == packageName
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            // Fail-safe: sessions could not be read → keep, so a restore-in-progress is
            // never pruned on a transient query failure.
            TimberWrapper.silentError(e, "Install-session check failed for $packageName; failing safe to keep")
            true
        }
    }
}
