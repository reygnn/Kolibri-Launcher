package com.github.reygnn.launcher.common.data.installedapps

import android.content.Intent
import android.content.pm.PackageManager
import com.github.reygnn.launcher.core.AppPresence
import com.github.reygnn.launcher.core.ComponentKey
import com.github.reygnn.launcher.core.IoDispatcher
import com.github.reygnn.launcher.core.TimberWrapper
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * [AppPresence] over the Android [PackageManager] — deliberately a DIFFERENT subsystem
 * from the [android.content.pm.LauncherApps]-based enumeration the reconcile diffs
 * against.
 *
 * That cross-surface split is the point (AUDIT-1 F7 review, fix 2, borrowed from Kolibri's
 * `PackagePresenceImpl`). The deletion gate only helps if it can disagree with the
 * enumeration: a check on the *same* `LauncherApps.getActivityList` surface shares that
 * surface's transient failure modes, so a systemic LauncherApps hiccup mid-restore /
 * early-post-unlock would report a still-installed app as absent on BOTH paths and prune
 * it anyway. Querying PackageManager instead means a LauncherApps transient does not
 * poison the re-confirmation.
 *
 * Presence is defined the way a launcher entry is defined — a target is "present" iff an
 * `ACTION_MAIN` / `CATEGORY_LAUNCHER` activity resolves for its package whose component
 * matches the key exactly (component-exact: a package may export several launcher aliases,
 * and only the specific one this key names counts; SPEC-DECISION R-1). The class spellings
 * compared are long-form on both sides (the enumerator builds keys from
 * `LauncherActivityInfo.componentName`; PackageManager returns fully-qualified
 * `activityInfo.name`), so there is no normalization drift.
 *
 * **Does NOT close the genuine mid-restore vector.** If a package is genuinely not yet
 * reinstalled during a restore, PackageManager also reports it absent — correctly, it IS
 * absent right now — and the key would still be pruned. That timing case is handled by the
 * install-session gate ([com.github.reygnn.launcher.core.InstallSessionInspector]),
 * not here; this check only removes the shared-transient failure mode (see nyx
 * ACCEPTED_LIMITATIONS.md, "reconcile during restore"). The session arm is
 * [com.github.reygnn.launcher.core.InstallSessionInspector].
 *
 * **Fail-safe:** any platform failure resolves to `true` (present) so a transient
 * system-API error can never become a prune. The broad `Throwable` catch is the
 * sanctioned system-API-boundary form; `CancellationException` still propagates. Runs on
 * [dispatcher] (the PackageManager call is blocking). Queried only per prune candidate, so
 * a complete reconcile pass calls this zero times.
 */
@Singleton
class PackageManagerPresence @Inject constructor(
    private val packageManager: PackageManager,
    @param:IoDispatcher private val dispatcher: CoroutineDispatcher,
) : AppPresence {

    override suspend fun isPresent(key: ComponentKey): Boolean = withContext(dispatcher) {
        try {
            val intent = Intent(Intent.ACTION_MAIN).apply {
                addCategory(Intent.CATEGORY_LAUNCHER)
                setPackage(key.packageName)
            }
            packageManager
                .queryIntentActivities(intent, PackageManager.ResolveInfoFlags.of(0))
                .any { info ->
                    val activityInfo = info.activityInfo ?: return@any false
                    activityInfo.packageName == key.packageName &&
                        activityInfo.name == key.className
                }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            // Fail-safe: presence could not be determined → treat as present so the
            // reconcile never prunes on a transient system-API error.
            TimberWrapper.silentError(e, "AppPresence check failed for ${key.flat}; failing safe to present")
            true
        }
    }
}
