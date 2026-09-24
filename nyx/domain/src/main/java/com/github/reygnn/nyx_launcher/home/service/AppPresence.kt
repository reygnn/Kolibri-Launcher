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
 * decoupling — bulk snapshot proposes, single-target check disposes — is what makes a
 * partial or transient list read unable to cause data loss (RHL-INV-6).
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
 * ([com.github.reygnn.nyx_launcher.data.installedapps.LauncherAppsPresence]) lives in
 * `:data` over the same shared `LauncherApps` seam the enumerator uses (SIA-INV-4).
 * Nyx-local by design: the sole consumer is Nyx's reconcile, and Kolibri already owns
 * an equivalent gate for its own stores. Promote to `:common-data` only if Kolibri
 * ever needs the LauncherApps-based variant too.
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
