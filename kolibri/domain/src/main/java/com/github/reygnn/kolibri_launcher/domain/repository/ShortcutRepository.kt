package com.github.reygnn.kolibri_launcher.domain.repository

import com.github.reygnn.kolibri_launcher.domain.model.LauncherShortcut

/**
 * Abstracts the logic for retrieving app shortcuts. The interface lets
 * tests swap the real implementation (which uses system APIs) for a
 * test double.
 */
interface ShortcutRepository : Purgeable {
    /**
     * Retrieves the dynamic and manifest-declared shortcuts for a given app package.
     * @param packageName The app's package name (e.g. "com.google.android.gm").
     * @return A list of [LauncherShortcut] objects, or an empty list when none
     *         were found or the permission is missing.
     */
    fun getShortcutsForPackage(packageName: String): List<LauncherShortcut>
}
