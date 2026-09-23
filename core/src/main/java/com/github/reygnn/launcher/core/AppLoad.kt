package com.github.reygnn.launcher.core

/**
 * Outcome of enumerating the installed-app list — the one canonical envelope
 * shared by both apps (SHARED_INSTALLED_APPS_SPEC §2 "Ergebnis-Envelope",
 * Klasse C; MONOREPO_MERGE_SPEC §2). Replaces Kolibri's former `AppLoad` and
 * Nyx's `AppLoadResult`.
 *
 * The fail-as-value contract that replaces the old collapse-to-`emptyList()`:
 * a load failure stays [Failed], distinguishable from a genuinely empty device
 * ([Loaded] with an empty list). Collapsing a failure into an empty list made
 * "empty" ambiguous and rendered downstream retry/error-recovery dead (a
 * `stateIn` StateFlow never delivers an upstream exception to its collector).
 *
 * **Empty policy (§9.2, resolved).** The loader is *value-honest*: an empty
 * enumeration is a legitimate [Loaded] with an empty list, NOT a failure. The
 * "empty ⇒ suspicious" rule (Nyx's former `ENUMERATION_EMPTY`) is a **reconcile
 * policy at each app's Klasse-B edge**, not baked into the shared envelope — so
 * [AppLoad] deliberately carries **no `Reason` enum**. Kolibri already treats
 * `Loaded(empty)` as legitimate (it skips reconcile on empty, IAL-INV-3); Nyx
 * preserves its fail-closed home-layout behaviour by having
 * `ReconcileHomeLayoutUseCase` skip on `Loaded(empty)` exactly as it used to
 * skip on `ENUMERATION_EMPTY` (RHL-INV-1).
 *
 * The loader catches its own errors (it must not crash) but represents them as
 * [Failed], never as `Loaded(emptyList())`. Cancellation always propagates
 * (SIA-INV-2).
 */
sealed interface AppLoad {

    /** The list was enumerated successfully; [apps] may legitimately be empty. */
    data class Loaded(val apps: List<AppInfo>) : AppLoad

    /**
     * The list could not be enumerated. [cause] is the caught Throwable, carried
     * for optional logging; consumers branch on the type, not the cause.
     */
    data class Failed(val cause: Throwable) : AppLoad
}
