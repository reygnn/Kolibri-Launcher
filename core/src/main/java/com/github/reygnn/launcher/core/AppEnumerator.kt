package com.github.reygnn.launcher.core

/**
 * The one product-variant seam of the installed-apps subsystem: *how* the raw
 * launchable-activity list is obtained from the platform (SHARED_INSTALLED_APPS_SPEC
 * §3 "Naht", SIA-INV-4 / MRG-INV-4). Everything else in the subsystem — holding,
 * debounce, keep-last-good fallback, reload, broadcast wiring — is shared and
 * knows nothing about the platform API behind this port.
 *
 * **§9.1 resolved: LauncherApps is canonical.** The port existed so the rest of
 * the cut would not wait on the PackageManager-vs-LauncherApps decision. That
 * decision is now made (LauncherApps: modern, primary-user-aware, hands back
 * `LauncherActivityInfo` with label + component directly, no per-app `loadLabel`
 * IPC). The port therefore collapses to a **single** shared implementation,
 * `LauncherAppsEnumerator` in `:common-data` — no per-app binding remains
 * (SHARED_INSTALLED_APPS_SPEC §5 step 4). The interface is kept (not inlined)
 * because it is the injection point that keeps the shared motor JVM-testable and
 * keeps `:core` free of `android.*`.
 *
 * **Contract.**
 * - [enumerate] returns the raw, enumerated list (SIA-INV-3: no overlays, no
 *   customName folding; sorting is the consumer's job — the holder holds *roh*).
 * - It **throws** on an enumeration failure; the shared motor catches and maps to
 *   [AppLoad.Failed] (SIA-INV-2). Implementations must let `CancellationException`
 *   propagate (the house idiom: rethrow it before any broad `catch (Throwable)`).
 * - An empty result is a legitimate empty list (value-honest, §9.2) — the
 *   enumerator does NOT translate "empty" into an error; that is a per-app
 *   reconcile policy.
 *
 * `suspend` so the implementation can run its IPC off the caller's thread; the
 * motor owns the sharing scope, not the enumerator.
 */
interface AppEnumerator {
    /**
     * Enumerate the launchable activities for the primary user, as raw [AppInfo].
     * Throws on failure (mapped to [AppLoad.Failed] upstream); rethrows
     * cancellation; may return an empty list.
     */
    suspend fun enumerate(): List<AppInfo>
}
