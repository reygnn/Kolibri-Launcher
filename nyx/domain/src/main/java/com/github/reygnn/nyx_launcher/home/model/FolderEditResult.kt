package com.github.reygnn.nyx_launcher.home.model

/**
 * Outcome of extracting a member from a folder. See REMOVE_FROM_FOLDER_SPEC §1.
 * Reuses [MoveResult.Reason]. [layout] is `null` ⇔ no change.
 */
sealed interface FolderEditResult {
    val layout: HomeLayout?

    /** Folder had >= 3 members → it shrinks; the extracted app lands at target. */
    data class Extracted(override val layout: HomeLayout, val app: ItemId) : FolderEditResult

    /**
     * Folder had exactly 2 → it dissolves: the [extracted] app lands at target and
     * the [survivor] is promoted to a top-level app at the folder's old position
     * (RFF-INV-1/-2). The folder's [ItemId] is retired.
     */
    data class FolderDissolved(
        override val layout: HomeLayout,
        val extracted: ItemId,
        val survivor: ItemId,
    ) : FolderEditResult

    data object NoOp : FolderEditResult {
        override val layout: HomeLayout? get() = null
    }

    data class Rejected(val reason: MoveResult.Reason) : FolderEditResult {
        override val layout: HomeLayout? get() = null
    }
}
