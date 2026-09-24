package com.github.reygnn.nyx_launcher.home.service

/**
 * "Is a package mid-install/restore right now?" — the second half of the reconcile
 * deletion gate, alongside [AppPresence] (AUDIT-1 F7 review, fix 3).
 *
 * [AppPresence] answers "is it installed *now*"; during a device restore the honest answer
 * for a not-yet-reinstalled app is "no", yet its home/dock placement must be kept, because
 * the package is on its way back. This is the one vector neither a same-surface nor a
 * cross-surface presence check can close (the app really is absent at this instant).
 *
 * The fix is the mechanism AOSP Launcher3 uses: never treat "absent from the app list" as
 * a delete while an install/restore session for that package is in flight — Launcher3 keeps
 * such items as promise icons and only removes one when its session is genuinely abandoned.
 * Here the reconcile ORs this signal with presence: a candidate is pruned only if it is
 * BOTH absent AND session-less.
 *
 * Pure-Kotlin port (nyx/domain is plain-JVM); the implementation
 * ([com.github.reygnn.nyx_launcher.data.installedapps.PackageManagerInstallSessions]) reads
 * `PackageInstaller` sessions in `:data`.
 */
interface InstallSessionInspector {

    /**
     * True if [packageName] has an active install/restore session in progress, OR if that
     * could not be determined (fail-safe → keep). Suspends: the impl hops to IO for the
     * platform query.
     */
    suspend fun hasActiveSession(packageName: String): Boolean
}
