package com.github.reygnn.launcher.common.data.installedapps

import android.content.pm.LauncherApps
import android.os.Process
import android.os.Trace
import com.github.reygnn.launcher.core.AppEnumerator
import com.github.reygnn.launcher.core.AppInfo
import com.github.reygnn.launcher.core.IoDispatcher
import com.github.reygnn.launcher.core.KolibriLog
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext
import java.util.concurrent.CancellationException
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The single, shared [AppEnumerator] (SHARED_INSTALLED_APPS_SPEC §9.1 resolved:
 * LauncherApps is canonical). Replaces Kolibri's former
 * `PackageManager.queryIntentActivities` + per-app `loadLabel` path with
 * `LauncherApps.getActivityList` — one primary-user-aware call that returns
 * `LauncherActivityInfo` carrying both the component and the label, so the
 * per-app label IPC disappears.
 *
 * **Why LauncherApps over PackageManager.**
 * - Primary-user-aware by design (`Process.myUserHandle()`) — single-user is the
 *   only semantic here (MRG-INV-9); work profiles are out of scope
 *   (SHARED_INSTALLED_APPS_SPEC §8).
 * - `LauncherActivityInfo.label` avoids the N× `loadLabel` IPC storm that the old
 *   `drawer_apps_enumerate` trace was written to expose.
 * - It is the modern launcher-facing surface and was already Nyx's path; unifying
 *   on it collapses the `AppEnumerator` port to one impl (§5 step 4).
 *
 * **Contract (AppEnumerator).**
 * - Returns the **raw** list (SIA-INV-3): no sort, no customName, no overlays. The
 *   holder holds it raw; consumers sort/overlay.
 * - **Throws** on enumeration failure — the shared motor
 *   ([InstalledAppsRepositoryImpl]) catches and maps to `AppLoad.Failed`
 *   (SIA-INV-2). We do NOT swallow here: a swallowed failure would collapse to an
 *   empty list and defeat fail-closed reconcile.
 * - [CancellationException] always propagates (never folded into a value): a
 *   cancelled drawer refresh must not be reported as a phantom failure. Mirrors
 *   the house idiom.
 * - An empty result is returned as an **empty list**, not an error (value-honest,
 *   §9.2). "empty ⇒ suspicious" is each app's reconcile policy.
 *
 * Per-item resilience: a single malformed entry (missing component, throwing
 * `label`) is skipped with a non-fatal warning breadcrumb (a non-throwing
 * `KolibriLog.w`, NOT `silentError` — silentError crashes in DEBUG and would make one
 * bad entry fatal to the whole enumeration in DEBUG). The only wholesale failure path
 * is a thrown `getActivityList`/processing error reaching the motor.
 *
 * [launcherApps] is injected so the fail-closed policy is unit-testable without a
 * device (see `LauncherAppsEnumeratorTest`).
 */
@Singleton
class LauncherAppsEnumerator @Inject constructor(
    private val launcherApps: LauncherApps,
    @IoDispatcher private val dispatcher: CoroutineDispatcher,
) : AppEnumerator {

    override suspend fun enumerate(): List<AppInfo> = withContext(dispatcher) {
        // The port owns its dispatcher: enumerate() runs its IPC on @IoDispatcher
        // regardless of the caller's thread, so it is correct when called from a
        // test or any non-IO context. The motor additionally `flowOn(IO)`s its
        // emit/logging — the two IO switches are intentional and independent
        // (same dispatcher = the inner one is a cheap no-op), NOT a redundancy bug.
        // Traced: the full launcher-app enumeration. With LauncherApps this is a
        // single getActivityList call plus in-process label reads — the historical
        // per-app loadLabel IPC is gone, so this section on the Perfetto timeline
        // should be markedly cheaper than the PackageManager path it replaces.
        // finally-balanced, not a swallowing catch (Rule 11). android.os.Trace
        // directly: :common-data cannot import an app's LaunchTrace.
        val activities = try {
            Trace.beginSection("drawer_apps_enumerate")
            // A getActivityList failure is a real LOAD failure, not "zero apps":
            // let it throw straight out to the motor, which turns the whole
            // emission into AppLoad.Failed (no per-step silentError — that would
            // report the same failure twice, anti-flood).
            launcherApps.getActivityList(null, Process.myUserHandle())
        } finally {
            Trace.endSection()
        }

        // Presize to the known upper bound (a few entries may drop on a null
        // component) to avoid backing-array reallocations while filling.
        val result = ArrayList<AppInfo>(activities.size)
        for (info in activities) {
            try {
                val component = info.componentName
                val packageName = component.packageName
                val className = component.className

                val originalName = try {
                    info.label?.toString()?.ifBlank { packageName } ?: packageName
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Throwable) {
                    // A malformed third-party label is an EXPECTED external degradation, not
                    // our bug: log a non-fatal breadcrumb and fall back to the package name.
                    // Must NOT be silentError — that crashes in DEBUG, so one broken app would
                    // make the whole enumeration fatal in DEBUG, contradicting the skip-one-bad-
                    // entry contract below (and diverging from RELEASE behaviour).
                    KolibriLog.w(e, "Error reading label for $packageName")
                    packageName
                }

                // Raw entry: displayName == originalName. customName is an overlay
                // folded in reactively at each app's consumer (SIA-INV-3, §9.3),
                // never baked in here.
                result.add(
                    AppInfo(
                        originalName = originalName,
                        displayName = originalName,
                        packageName = packageName,
                        className = className,
                    )
                )
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                // Per-item resilience: skip one bad entry, keep enumerating. Non-throwing
                // (KolibriLog.w, not silentError) so a single malformed entry never aborts
                // the whole loop in DEBUG — see the label catch above.
                KolibriLog.w(e, "Error processing LauncherActivityInfo")
            }
        }
        result
    }
}
