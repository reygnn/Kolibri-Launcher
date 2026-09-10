package com.github.reygnn.nyx_launcher.home.model

/**
 * Outcome of a move / place. A sealed identifier, not a bare [HomeLayout]:
 * `:app` maps it to animation / announce / toast (CLAUDE.md rule 16).
 * See MOVE_ITEM_SPEC §2.
 *
 * [layout] is `null` exactly when the state did not change ([NoOp], [Rejected]),
 * which is the use-case's signal to skip the DataStore write.
 */
sealed interface MoveResult {
    val layout: HomeLayout?

    /** Item relocated to the target (also the outcome of a fresh placement). */
    data class Moved(override val layout: HomeLayout) : MoveResult

    /** App dropped on app → a new folder `[targetApp, sourceApp]` (MIU §7-D1). */
    data class FolderCreated(override val layout: HomeLayout, val folder: ItemId) : MoveResult

    /** App dropped on a folder → appended to its members. */
    data class AddedToFolder(override val layout: HomeLayout, val folder: ItemId) : MoveResult

    /** No change (self-drop, or a programmer-error precondition — MIU-INV-2). */
    data object NoOp : MoveResult {
        override val layout: HomeLayout? get() = null
    }

    /** A real, user-reachable refusal. */
    data class Rejected(val reason: Reason) : MoveResult {
        override val layout: HomeLayout? get() = null
    }

    enum class Reason { TARGET_OCCUPIED_INCOMPATIBLE, OFF_GRID, DOCK_FULL }
}
