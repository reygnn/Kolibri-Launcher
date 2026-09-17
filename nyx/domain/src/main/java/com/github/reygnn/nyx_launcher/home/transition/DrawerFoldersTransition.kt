package com.github.reygnn.nyx_launcher.home.transition

import com.github.reygnn.launcher.core.ComponentKey
import com.github.reygnn.nyx_launcher.home.model.DrawerDropTarget
import com.github.reygnn.nyx_launcher.home.model.DrawerFolder
import com.github.reygnn.nyx_launcher.home.model.DrawerFolderId
import com.github.reygnn.nyx_launcher.home.model.DrawerFolders
import com.github.reygnn.nyx_launcher.home.model.FolderMembership

/**
 * Pure drawer-folder transitions (DRAWER_FOLDERS_SPEC §8). Membership semantics reuse
 * the shared [FolderMembership] (create/add/remove — identical to home); the only
 * drawer-specific rule added here is **DFOLD-INV-3**: an app is in at most one drawer
 * folder, so creating/adding strips it from any other folder first (dissolving one
 * that thereby drops below two members, DFOLD-INV-1). No positions, no ids beyond the
 * injected factory (domain stays RNG-free) — the drawer has no placement to compute.
 *
 * Every function is total: a structurally-impossible input (self-fold, unknown folder,
 * not-a-member, an already-present member) returns `null` = "no change", which the
 * caller feeds straight to [DrawerFoldersRepository.update] (null ⇒ no write). It is
 * never dressed up as a user-facing rejection.
 */
object DrawerFoldersTransition {

    /**
     * Apply a drawer [target] drop of the loose app [source]. Returns the new
     * membership, or `null` for a no-op.
     */
    fun drop(
        folders: DrawerFolders,
        source: ComponentKey,
        target: DrawerDropTarget,
        newFolderId: () -> DrawerFolderId,
    ): DrawerFolders? = when (target) {
        is DrawerDropTarget.OntoApp -> createFolder(folders, target.target, source, newFolderId)
        is DrawerDropTarget.OntoFolder -> addToFolder(folders, target.folderId, source)
    }

    /**
     * Bulk-add ALL of [keys] to the folder [folderId] at once (vendor "add all"). Each key
     * is pulled from any OTHER drawer folder first (DFOLD-INV-3), dissolving one that thereby
     * drops below two members (DFOLD-INV-1); keys already in this folder are skipped. Returns
     * the new membership, or `null` if the folder is unknown or nothing would change.
     */
    fun addAll(folders: DrawerFolders, folderId: DrawerFolderId, keys: List<ComponentKey>): DrawerFolders? {
        val folder = folders.folders.firstOrNull { it.id == folderId } ?: return null
        val toAdd = keys.distinct().filter { it !in folder.members }
        if (toAdd.isEmpty()) return null
        // The target holds none of [toAdd] (filtered above), so stripping [toAdd] from every
        // folder leaves the target's existing members intact; we then append them here.
        val newMembers = toAdd.fold(folder.members) { acc, key -> FolderMembership.add(acc, key) }
        return folders.without(toAdd.toSet()).replace(folderId) { it.copy(members = newMembers) }
    }

    /**
     * Create a NEW drawer folder titled [title] holding all of [keys] at once (the drawer
     * overflow "create folder by maker" action). Each key is pulled from any other drawer
     * folder first (DFOLD-INV-3), dissolving one that thereby drops below two members
     * (DFOLD-INV-1). Returns the new membership, or `null` if fewer than two DISTINCT keys
     * remain — a folder needs ≥ 2 members (DFOLD-INV-1), so a one-app "maker" is a no-op.
     */
    fun createFolderFrom(
        folders: DrawerFolders,
        title: String,
        keys: List<ComponentKey>,
        newFolderId: () -> DrawerFolderId,
    ): DrawerFolders? {
        val members = keys.distinct()
        if (members.size < 2) return null
        // DFOLD-INV-3: every member ends up in the new folder, so pull them out of any
        // existing folder first (dissolving one that drops below two).
        val stripped = folders.without(members.toSet())
        val newFolder = DrawerFolder(newFolderId(), title = title, members = members)
        return DrawerFolders(stripped.folders + newFolder)
    }

    /**
     * Extract [member] from the opened folder [folderId]: the folder shrinks, or — if it
     * would drop below two members — dissolves entirely (DFOLD-INV-1). Both the extracted
     * member and any dissolved-folder survivor become loose implicitly (they are simply no
     * longer in any folder). `null` if the folder is unknown or [member] is not in it.
     */
    fun extract(folders: DrawerFolders, folderId: DrawerFolderId, member: ComponentKey): DrawerFolders? {
        val folder = folders.folders.firstOrNull { it.id == folderId } ?: return null
        return when (val result = FolderMembership.remove(folder.members, member)) {
            FolderMembership.RemoveResult.NotAMember -> null
            is FolderMembership.RemoveResult.Removed ->
                folders.replace(folderId) { it.copy(members = result.members) }
            is FolderMembership.RemoveResult.Dissolved ->
                DrawerFolders(folders.folders.filterNot { it.id == folderId })
        }
    }

    /**
     * Rename [folderId] to [title] (already normalized by the caller). `null` if the
     * folder is unknown or the title is unchanged (= no write). Rename is not a
     * membership op — the folder's [DrawerFolderId] and members are untouched (DFOLD-INV-5).
     */
    fun rename(folders: DrawerFolders, folderId: DrawerFolderId, title: String): DrawerFolders? {
        val folder = folders.folders.firstOrNull { it.id == folderId } ?: return null
        if (folder.title == title) return null
        return folders.replace(folderId) { it.copy(title = title) }
    }

    // ---- internals ----

    private fun createFolder(
        folders: DrawerFolders,
        target: ComponentKey,
        dragged: ComponentKey,
        newFolderId: () -> DrawerFolderId,
    ): DrawerFolders? {
        if (target == dragged) return null // can't fold an app onto itself
        // DFOLD-INV-3: both apps end up in the new folder, so pull them out of any
        // existing folder first (dissolving one that drops below two).
        val stripped = folders.without(setOf(target, dragged))
        val newFolder = DrawerFolder(newFolderId(), title = "", members = FolderMembership.create(target, dragged))
        return DrawerFolders(stripped.folders + newFolder)
    }

    private fun addToFolder(
        folders: DrawerFolders,
        folderId: DrawerFolderId,
        source: ComponentKey,
    ): DrawerFolders? {
        val folder = folders.folders.firstOrNull { it.id == folderId } ?: return null
        val newMembers = FolderMembership.add(folder.members, source)
        if (newMembers === folder.members) return null // already a member of this folder → no-op
        // DFOLD-INV-3: remove [source] from every OTHER folder first (it is not in this
        // one — the guard above), dissolving any that drop below two, then add it here.
        return folders.without(setOf(source)).replace(folderId) { it.copy(members = newMembers) }
    }

    /**
     * [keys] removed from every folder; a folder that drops below two members dissolves
     * (its remaining member goes loose). Folders untouched by [keys] keep their identity.
     */
    private fun DrawerFolders.without(keys: Set<ComponentKey>): DrawerFolders = DrawerFolders(
        folders.mapNotNull { folder ->
            val remaining = folder.members.filterNot { it in keys }
            when {
                remaining.size == folder.members.size -> folder // unchanged
                remaining.size >= 2 -> folder.copy(members = remaining)
                else -> null // dissolved
            }
        },
    )

    private fun DrawerFolders.replace(
        id: DrawerFolderId,
        transform: (DrawerFolder) -> DrawerFolder,
    ): DrawerFolders = DrawerFolders(folders.map { if (it.id == id) transform(it) else it })
}
