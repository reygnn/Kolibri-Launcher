package com.github.reygnn.kolibri_launcher.domain.model

/**
 * Outcome of loading the installed-app list from the PackageManager
 * (INSTALLED_APPS_LOAD_SPEC, Belang A).
 *
 * The fail-as-value contract that replaces the old collapse-to-`emptyList()`:
 * a load failure stays [Failed], distinguishable from a genuinely empty device
 * ([Loaded] with an empty list). Collapsing a failure into an empty list made
 * "empty" ambiguous and rendered the downstream retry/error-recovery apparatus
 * dead (a `stateIn` StateFlow never delivers an upstream exception to its
 * collector). Same failure-as-value family as `AppLoadResult`,
 * `ConsentReadResult` and the DataStore read contract — carried one repo boundary
 * lower.
 *
 * The loader catches its own errors (it must not crash) but represents them as
 * [Failed], never as `Loaded(emptyList())`. Cancellation still propagates.
 */
sealed interface AppLoad {

    /** The list was loaded successfully; [apps] may legitimately be empty. */
    data class Loaded(val apps: List<AppInfo>) : AppLoad

    /**
     * The list could not be loaded. [cause] is the caught Throwable, carried for
     * optional logging; consumers branch on the type, not the cause.
     */
    data class Failed(val cause: Throwable) : AppLoad
}
