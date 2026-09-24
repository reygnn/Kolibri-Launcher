package com.github.reygnn.launcher.core

/**
 * Single-target presence check: is THIS launcher target still installed for the primary
 * user, right now? The shared **deletion gate** used by a launcher's layout/assignment
 * reconcile.
 *
 * Sibling of [AppEnumerator] (bulk "what's installed"). A reconcile prunes any stored
 * reference — a home placement, a favorite, a hidden/swipe assignment, a custom name —
 * whose target is absent from the enumerated app set. That is correct only if the
 * enumeration is COMPLETE. A non-empty-but-partial snapshot (a transient short
 * `LauncherApps.getActivityList` / `queryIntentActivities` mid-restore or early
 * post-unlock, or a per-item drop in [AppEnumerator]) would otherwise delete every
 * reference that merely failed to appear that pass, permanently (AUDIT-1 F7 / Kolibri
 * RECONCILE_FIX_SPEC R-INV). So a target missing from the snapshot is only a *candidate*
 * for pruning; the caller re-confirms each candidate here first. Bulk snapshot proposes,
 * single-target check disposes.
 *
 * Two resolution levels (Kolibri SPEC-DECISION R-1), because stores key at two grains:
 * - [isComponentPresent] — component-exact (`package/class`), for component-keyed stores
 *   (home layout, favorites, swipe, hidden), so an app that disables ONE launcher alias
 *   (an icon-hide toggle) is detected as gone for that specific [ComponentKey].
 * - [isPackagePresent] — package-level, for package-keyed stores (custom names).
 *
 * **Fail-safe contract:** any platform failure resolves to `true` ("present"), so a
 * transient system-API error can NEVER turn into a prune. Losing a stored reference is
 * user-visible and unrecoverable; a stale-but-present one is harmless and the next
 * reconcile fixes it. The gate therefore errs toward keeping.
 *
 * The default implementation (`PackageManagerPresence`, in `:common-data`) queries
 * `PackageManager` — deliberately a DIFFERENT subsystem from the `LauncherApps`-based
 * enumeration the reconcile diffs against, so a `LauncherApps` transient can't poison the
 * re-confirmation. Bound app-side (like [AppEnumerator]). Consumed by Nyx's home reconcile
 * (component grain) and Kolibri's store reconcile (both grains).
 *
 * This gate answers "installed now?". A target legitimately absent because it is
 * mid-restore is kept by the separate [InstallSessionInspector] arm, which a caller may OR
 * with this one (Nyx does).
 */
interface AppPresence {

    /**
     * True if [key] (`package` + long-form `class`) still resolves as a launcher activity
     * for the primary user, OR if presence could not be determined (fail-safe → present).
     * Suspends: the impl hops to IO for the platform query so callers never block on it.
     */
    suspend fun isComponentPresent(key: ComponentKey): Boolean

    /**
     * True if [packageName] still has ANY launcher entry, OR if presence could not be
     * determined (fail-safe → present). The package-level companion to [isComponentPresent],
     * for stores keyed on package rather than component. Suspends (IO).
     */
    suspend fun isPackagePresent(packageName: String): Boolean
}
