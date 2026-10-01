package com.github.reygnn.nyx_launcher.home.model

/**
 * What an import restores (a runtime choice of the restore dialog, not persisted). Hidden
 * apps have their own switch, like Kolibri's (2b-3): when on, B13 holds and the set is
 * replaced; a backup without the field leaves the current set standing. Drawer folders are
 * restored with the home layout.
 */
data class ImportOptions(
    val importLayout: Boolean = true,
    val importHiddenApps: Boolean = true,
    val importSettings: Boolean = true,
    val importWallpaper: Boolean = true,
) {
    val importNothing: Boolean get() = !importLayout && !importHiddenApps && !importSettings && !importWallpaper
}
