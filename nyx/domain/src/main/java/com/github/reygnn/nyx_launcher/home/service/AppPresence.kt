package com.github.reygnn.nyx_launcher.home.service

import com.github.reygnn.launcher.core.ComponentKey

/**
 * Single-target presence check: is THIS launcher entry still installed for the
 * primary user, right now?
 *
 * This is the **deletion gate** for the fail-closed reconcile
 * (`ReconcileHomeLayoutUseCase`), the Nyx analog of Kolibri's `PackagePresence`
 * (RECONCILE_FIX_SPEC R-INV-2). The reconciler ([com.github.reygnn.nyx_launcher
 * .home.transition.HomeLayoutReconciler]) prunes any home/dock reference whose key
 * is absent from the enumerated app set. That is correct only if the enumeration is
 * COMPLETE. A non-empty-but-**partial** snapshot — a transient short
 * `LauncherApps.getActivityList` mid-restore / early post-unlock, or a per-item drop
 * in [com.github.reygnn.launcher.core.AppEnumerator] — would otherwise make the
 * reconciler delete the placement of every app that merely failed to appear that
 * pass, permanently (AUDIT-1 F7).
 *
 * So a key missing from the snapshot is only a *candidate* for pruning; the use-case
 * re-confirms each candidate here before the reconciler is allowed to drop it. That
 * decoupling — bulk snapshot proposes, single-target check disposes — is what stops a
 * partial or transient list read from pruning a still-installed app (RHL-INV-6). The
 * mid-restore case (absent now, on its way back) is the other arm's job — see the port
 * note below and [InstallSessionInspector].
 *
 * **Component-exact.** Presence is checked at the `package/class` grain (not just the
 * package), so an app that disables ONE launcher alias (an icon-hide toggle) while
 * keeping others is correctly seen as gone for that specific [ComponentKey] — mirrors
 * Kolibri's component-level favorites gate (SPEC-DECISION R-1).
 *
 * **Fail-safe contract:** any platform failure resolves to `true` ("present"), so a
 * transient system-API error can NEVER turn into a prune. Losing a placement is
 * user-visible and unrecoverable; a stale-but-present placement is harmless and the
 * next reconcile fixes it. The gate therefore errs toward keeping.
 *
 * Pure-Kotlin port: `nyx/domain` is plain-JVM, so the implementation
 * ([com.github.reygnn.nyx_launcher.data.installedapps.PackageManagerPresence]) lives in
 * `:data`. It queries [android.content.pm.PackageManager] — deliberately a DIFFERENT
 * subsystem from the [android.content.pm.LauncherApps]-based enumeration the reconcile
 * diffs against, so a LauncherApps transient can't poison the re-confirmation (AUDIT-1 F7
 * review, fix 2). Nyx-local by design: the sole consumer is Nyx's reconcile, and Kolibri
 * already owns an equivalent gate (`PackagePresence`, same PackageManager technique) for
 * its own stores. Promote both to shared modules only if the consolidation is taken up
 * (see nyx TODO, Option B/C).
 *
 * This is only ONE arm of the gate: it answers "installed now?". A package legitimately
 * absent because it is mid-restore is kept by the separate [InstallSessionInspector] arm,
 * which the use-case ORs with this one.
 */
interface AppPresence {

    /**
     * True if [key] (`package` + long-form `class`) still resolves as a launchable
     * activity for the primary user, OR if presence could not be determined
     * (fail-safe → present). Suspends: the impl hops to IO for the platform query so
     * callers never block on it.
     */
    suspend fun isPresent(key: ComponentKey): Boolean
}
