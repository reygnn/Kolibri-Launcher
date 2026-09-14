package com.github.reygnn.nyx_launcher.home.model

/**
 * Outcome of extracting a member from a folder. See REMOVE_FROM_FOLDER_SPEC §1.
 * Reuses [MoveResult.Reason]. [layout] is `null` ⇔ no change.
 */
sealed interface FolderEditResult : LayoutEditResult {
    override val layout: HomeLayout?

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

    /**
     * Member dragged straight from the source folder into ANOTHER grid folder [to],
     * which had >= 3 members so it merely shrinks (stays >= 2, RFF-INV-1). No
     * extraction to empty space: the member leaves [from] and joins [to] in one
     * transition. [to] is its own uniqueness scope (scoped IHM-INV-7), so the member
     * may still be a top-level tile / a member of a third folder.
     */
    data class MovedBetweenFolders(
        override val layout: HomeLayout,
        val from: ItemId,
        val to: ItemId,
    ) : FolderEditResult

    /**
     * Member dragged into another grid folder [to], where the source folder had
     * exactly 2 members and therefore dissolves (RFF-INV-1/-2): the [survivor] is
     * promoted to a top-level app at the source folder's old position, and the moved
     * member now lives in [to]. The source folder's [ItemId] is retired.
     */
    data class MovedBetweenFoldersDissolve(
        override val layout: HomeLayout,
        val to: ItemId,
        val survivor: ItemId,
    ) : FolderEditResult

    data object NoOp : FolderEditResult {
        override val layout: HomeLayout? get() = null
    }

    data class Rejected(val reason: MoveResult.Reason) : FolderEditResult {
        override val layout: HomeLayout? get() = null
    }
}
