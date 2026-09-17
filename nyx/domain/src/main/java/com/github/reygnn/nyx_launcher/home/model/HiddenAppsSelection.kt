package com.github.reygnn.nyx_launcher.home.model

import com.github.reygnn.launcher.core.ComponentKey

/**
 * Pure merge for the Settings hidden-apps manager (Rule 10 — the non-obvious invariant lives
 * here, JVM-testable, not inline in a Fragment dialog lambda).
 *
 * The manager shows only the currently-installed apps ([shownKeys]) with a checked subset
 * ([checkedShown]). Applying it must be a MERGE, not a replace: hidden keys for apps that are
 * NOT shown (uninstalled, or restored from another device's backup) are preserved, while the
 * shown apps take exactly the checked selection.
 */
object HiddenAppsSelection {
    fun merge(
        current: Set<ComponentKey>,
        shownKeys: Set<ComponentKey>,
        checkedShown: Set<ComponentKey>,
    ): Set<ComponentKey> = current.filterTo(mutableSetOf()) { it !in shownKeys } + checkedShown
}
