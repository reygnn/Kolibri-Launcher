package com.github.reygnn.nyx_launcher.home.model

import com.github.reygnn.launcher.core.ComponentKey

/**
 * A drawer-internal drop target (DRAWER_FOLDERS_SPEC §8), deliberately decoupled
 * from the home [DropTarget] (which carries cell/dock positions). The drawer has no
 * placement — "loose in the drawer" is implicit — so a drop only ever creates a
 * folder or adds to one. Extracting a member from an opened folder is a separate
 * operation (see `DrawerFoldersTransition.extract`), not a drop target.
 */
sealed interface DrawerDropTarget {
    /** Dropped a loose app onto another loose app → create a folder from the two. */
    data class OntoApp(val target: ComponentKey) : DrawerDropTarget

    /** Dropped a loose app onto a folder → add it as a member. */
    data class OntoFolder(val folderId: DrawerFolderId) : DrawerDropTarget
}
