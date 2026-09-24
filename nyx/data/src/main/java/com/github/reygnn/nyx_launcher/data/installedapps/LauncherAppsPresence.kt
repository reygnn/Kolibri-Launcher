package com.github.reygnn.nyx_launcher.data.installedapps

import android.content.pm.LauncherApps
import android.os.Process
import com.github.reygnn.launcher.core.ComponentKey
import com.github.reygnn.launcher.core.IoDispatcher
import com.github.reygnn.launcher.core.TimberWrapper
import com.github.reygnn.nyx_launcher.home.service.AppPresence
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * [AppPresence] over the same shared [LauncherApps] seam the enumerator uses
 * (SIA-INV-4). Presence is defined exactly as the drawer list is built — a target is
 * "present" iff `LauncherApps.getActivityList` returns it for the primary user — so the
 * deletion gate stays consistent with what the user actually sees.
 *
 * Queried per candidate (a key the layout references but the last snapshot missed), so
 * a normal COMPLETE reconcile pass calls this zero times: the use-case only reaches the
 * gate for keys absent from the enumeration. On a healthy device that set is empty, so
 * there is no per-reconcile IPC cost added to the common path (AUDIT-1 F7 fix).
 *
 * Primary-user only (`Process.myUserHandle()`) — single-user is the sole semantic here
 * (MRG-INV-9); work profiles are out of scope. A package-scoped `getActivityList` is
 * one cheap IPC per candidate, then an in-process exact-component match; no per-app
 * `loadLabel` and no full-device scan.
 *
 * **Fail-safe:** any failure resolves to `true` (present). The broad `Throwable` catch
 * is the sanctioned system-API-boundary form — a real failure reported as the SAFE
 * value, never a swallowed programmer error — and `CancellationException` still
 * propagates (house idiom). A transient platform error must never become a prune
 * (RHL-INV-6 / R-INV-2).
 *
 * [launcherApps] is injected so the gate is unit-testable without a device (see
 * `LauncherAppsPresenceTest`).
 */
@Singleton
class LauncherAppsPresence @Inject constructor(
    private val launcherApps: LauncherApps,
    @param:IoDispatcher private val dispatcher: CoroutineDispatcher,
) : AppPresence {

    override suspend fun isPresent(key: ComponentKey): Boolean = withContext(dispatcher) {
        try {
            // Package-scoped enumeration, then an EXACT package/class match: a package
            // may export several launcher aliases, and only the specific one this key
            // names counts as present (component-exact, SPEC-DECISION R-1). The
            // enumerator builds keys from LauncherActivityInfo.componentName too, so the
            // class spellings compared here are the same long form (no normalization
            // drift between enumerate() and this check).
            launcherApps.getActivityList(key.packageName, Process.myUserHandle())
                .any { info ->
                    val component = info.componentName
                    component.packageName == key.packageName &&
                        component.className == key.className
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
