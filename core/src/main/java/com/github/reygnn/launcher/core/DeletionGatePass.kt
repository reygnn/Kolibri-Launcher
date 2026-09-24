package com.github.reygnn.launcher.core

/**
 * The shared per-pass application of the two-arm deletion gate, used by BOTH launchers' reconciles
 * (AUDIT-1 F7; root `TODO.md` "Drift-Prävention" step a). A reconcile proposes prune *candidates* —
 * keys a store references but the fresh enumeration missed — and this gate decides, per candidate,
 * whether it is genuinely gone (prune) or merely transiently absent (keep). It is the ONE place the
 * fail-safe-to-keep policy lives, so nyx's home reconcile and kolibri's four store reconciles can
 * never drift on it.
 *
 * A candidate is KEPT if EITHER arm says so:
 * - [AppPresence] — a cross-surface `PackageManager` re-confirm (a `LauncherApps` enumeration
 *   transient can't poison it); fail-safe → present.
 * - [InstallSessionInspector] — the package has an active install/restore session (Launcher3-style
 *   promise); fail-safe (a `null`/undetermined read) → keep.
 *
 * Only a candidate that is BOTH absent AND session-less is pruned.
 *
 * **One instance per reconcile pass.** The install/restore-session set is read at most ONCE, lazily
 * (only when some candidate is absent from presence), and cached for the rest of the pass — so a
 * pass performs at most a SINGLE `PackageInstaller` enumeration no matter how many keys it gates,
 * and a healthy all-present pass performs ZERO session IPC. Presence is checked first; the session
 * set is consulted only on a presence-absent candidate (the `||` short-circuit). Create a fresh
 * instance per pass; kolibri shares ONE instance across its four stores so the single read is
 * amortized over all of them, nyx creates one per home-reconcile pass.
 *
 * Two grains, because stores key at two levels (Kolibri SPEC-DECISION R-1): [keepComponent] for
 * component-keyed stores (home layout, favorites, swipe, hidden), [keepPackage] for package-keyed
 * stores (custom names). A caller holding a flattened `"pkg/class"` string parses it with
 * [ComponentKey.parse] first; a malformed key (parse → `null`) is not a real component and is
 * handled caller-side (pruned), never routed here.
 *
 * Not a `@Singleton` and holds no platform state itself — it just sequences the two injected ports.
 * Both ports swallow their own platform errors (fail-safe to keep), so this class never needs a
 * try/catch and only [kotlinx.coroutines.CancellationException] propagates through it.
 */
class DeletionGatePass(
    private val presence: AppPresence,
    private val sessions: InstallSessionInspector,
) {
    private var sessionPackages: Set<String>? = null
    private var sessionsRead = false

    /**
     * Keep [key] if it still resolves as a launcher component ([AppPresence.isComponentPresent]) OR
     * its package has an active install/restore session. Presence first; the session set is read
     * only when presence is absent (`||` short-circuit), so a present key costs zero session IPC.
     */
    suspend fun keepComponent(key: ComponentKey): Boolean =
        presence.isComponentPresent(key) || isRestoring(key.packageName)

    /**
     * Keep [packageName] if it still exposes any launcher entry ([AppPresence.isPackagePresent]) OR
     * has an active install/restore session. The package-grain companion to [keepComponent].
     */
    suspend fun keepPackage(packageName: String): Boolean =
        presence.isPackagePresent(packageName) || isRestoring(packageName)

    /**
     * True if [packageName] has an active install/restore session, OR if that is undetermined (a
     * `null` read → fail-safe keep). Reads the session set once per pass ([sessionsRead] latch),
     * then membership-tests every subsequent candidate against the cached set.
     */
    private suspend fun isRestoring(packageName: String): Boolean {
        if (!sessionsRead) {
            sessionPackages = sessions.activeSessionPackages()
            sessionsRead = true
        }
        val packages = sessionPackages
        // null (undetermined) → fail-safe keep; otherwise keep iff a session targets the package.
        return packages == null || packageName in packages
    }
}
