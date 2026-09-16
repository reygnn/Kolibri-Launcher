package com.github.reygnn.nyx_launcher.home.model

import com.github.reygnn.launcher.core.ComponentKey

/**
 * Pure, position-free folder MEMBERSHIP operations, shared by home folders
 * ([HomeItem.Folder]) and — from DRAWER_FOLDERS_SPEC step 2 on — drawer folders
 * (`DrawerFolder`). It operates on the raw ordered-unique member list only; the
 * caller wraps the result in its own folder type, mints ids, and owns placement.
 * (DRAWER_FOLDERS_SPEC §7 / decision D-4.)
 *
 * The one non-trivial rule lives here, so home and drawer can never drift on it:
 * a folder holds **≥ 2** members, so removing one either shrinks it ([RemoveResult.Removed])
 * or dissolves it to its last survivor ([RemoveResult.Dissolved]). Membership is
 * deduplicated (IHM-INV-7). Id generation and cell/dock placement stay OUT of here —
 * they are the caller's concern (HomeLayoutTransition for home, the drawer transition
 * for drawer).
 */
object FolderMembership {

    /**
     * Members for a NEW folder created by dropping one app onto another: the drop
     * TARGET goes first, then the DRAGGED app (§7-D1 ordering, matching the home
     * grid/dock create paths).
     */
    fun create(target: ComponentKey, dragged: ComponentKey): List<ComponentKey> =
        listOf(target, dragged)

    /**
     * [members] with [key] appended, or [members] unchanged if [key] is already
     * present (IHM-INV-7 uniqueness). The caller detects the no-op by identity/equality
     * of the returned list against the input.
     */
    fun add(members: List<ComponentKey>, key: ComponentKey): List<ComponentKey> =
        if (key in members) members else members + key

    /** Outcome of [remove]. */
    sealed interface RemoveResult {
        /**
         * [key] is not removable as a single member — it is absent, or (invariant-violating
         * imported/hand-edited blob) present more than once, which makes "remove one"
         * ambiguous. The caller leaves the folder untouched (and typically fires a DEBUG
         * `silentError`), rather than mangling the layout.
         */
        data object NotAMember : RemoveResult

        /** The folder still holds ≥ 2 members after the removal. */
        data class Removed(val members: List<ComponentKey>) : RemoveResult

        /**
         * The folder dropped to a single member and dissolves; [survivor] returns to the
         * loose pool (a home tile, or a loose drawer app).
         */
        data class Dissolved(val survivor: ComponentKey) : RemoveResult
    }

    /**
     * Remove [key] from [members]. See [RemoveResult]. Only an exactly-once [key] is a
     * real removal; absent or duplicated → [RemoveResult.NotAMember] (mirrors
     * HomeLayoutTransition.removeFromFolder's not-member + duplicate guards).
     */
    fun remove(members: List<ComponentKey>, key: ComponentKey): RemoveResult {
        if (members.count { it == key } != 1) return RemoveResult.NotAMember
        val remaining = members.filterNot { it == key }
        return if (remaining.size >= 2) RemoveResult.Removed(remaining)
        else RemoveResult.Dissolved(remaining.single())
    }
}
