package com.github.reygnn.launcher.core

/**
 * "Is a package mid-install/restore right now?" — the second arm of the layout-reconcile
 * deletion gate, alongside [AppPresence] (AUDIT-1 F7 review, fix 3).
 *
 * [AppPresence] answers "is it installed *now*"; during a device restore the honest answer
 * for a not-yet-reinstalled app is "no", yet its home/dock placement must be kept, because
 * the package is on its way back. This is the one vector no presence check (same-surface or
 * cross-surface) can close — the app really is absent at this instant.
 *
 * The fix is the mechanism AOSP Launcher3 uses: never treat "absent from the app list" as a
 * delete while an install/restore session for that package is in flight — Launcher3 keeps
 * such items as promise icons and only removes one when its session is genuinely abandoned.
 * The caller ORs this signal with presence: a candidate is pruned only if it is BOTH absent
 * AND session-less.
 *
 * The default implementation (`PackageManagerInstallSessions`, in `:common-data`) reads
 * `PackageInstaller` sessions. Bound app-side; currently consumed only by Nyx's home
 * reconcile, available to any app that needs the same gate.
 */
interface InstallSessionInspector {

    /**
     * The set of package names that currently have an active install/restore session, or `null`
     * if that could NOT be determined (fail-safe → the caller keeps every candidate).
     *
     * Batch by design (AUDIT-1 F7 review, point 5): a reconcile reads this ONCE per pass and
     * membership-tests each prune candidate against the returned set, so a pass performs at most
     * a SINGLE `PackageInstaller` enumeration no matter how many keys it must gate — instead of
     * one full enumeration per candidate. The three-way outcome is deliberate: a package in the
     * set is being restored (keep); a package absent from a non-null set is genuinely session-less
     * (prunable if also absent from presence); a `null` result means the query failed and the
     * caller must keep every candidate (the per-package fail-safe-to-keep contract, now expressed
     * once for the whole set). Suspends: the impl hops to IO for the platform query.
     */
    suspend fun activeSessionPackages(): Set<String>?
}
