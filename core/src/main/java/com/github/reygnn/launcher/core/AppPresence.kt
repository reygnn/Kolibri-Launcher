package com.github.reygnn.launcher.core

/**
 * Single-target presence check: is THIS launcher entry still installed for the
 * primary user, right now?
 *
 * Sibling of [AppEnumerator] (bulk "what's installed") — this is the single-target
 * "is exactly this one still installed" used to GATE deletions. A launcher's layout
 * reconcile prunes any home/dock reference whose key is absent from the enumerated app
 * set; that is correct only if the enumeration is COMPLETE. A non-empty-but-partial
 * snapshot — a transient short `LauncherApps.getActivityList` mid-restore / early
 * post-unlock, or a per-item drop in [AppEnumerator] — would otherwise delete the
 * placement of every app that merely failed to appear that pass, permanently (AUDIT-1
 * F7). So a key missing from the snapshot is only a *candidate* for pruning; the caller
 * re-confirms each candidate here first. Bulk snapshot proposes, single-target check
 * disposes — the decoupling that stops a partial read from pruning a still-installed app.
 *
 * **Component-exact.** Presence is checked at the `package/class` grain (not just the
 * package), so an app that disables ONE launcher alias (an icon-hide toggle) while
 * keeping others is correctly seen as gone for that specific [ComponentKey].
 *
 * **Fail-safe contract:** any platform failure resolves to `true` ("present"), so a
 * transient system-API error can NEVER turn into a prune. Losing a placement is
 * user-visible and unrecoverable; a stale-but-present placement is harmless and the next
 * reconcile fixes it. The gate therefore errs toward keeping.
 *
 * The default implementation (`PackageManagerPresence`, in `:common-data`) queries
 * `PackageManager` — deliberately a DIFFERENT subsystem from the `LauncherApps`-based
 * enumeration the reconcile diffs against, so a `LauncherApps` transient can't poison the
 * re-confirmation. Bound app-side (like [AppEnumerator]); currently consumed only by Nyx's
 * home reconcile, available to any app that needs the same gate.
 *
 * This is only ONE arm of the gate: it answers "installed now?". A package legitimately
 * absent because it is mid-restore is kept by the separate [InstallSessionInspector] arm,
 * which the caller ORs with this one.
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
