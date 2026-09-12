package com.github.reygnn.nyx_launcher.home.model

/**
 * Common surface of the edit-use-case results ([MoveResult], [FolderEditResult],
 * [LayoutEdit]): [layout] is the new layout to persist, or `null` ⇔ no change
 * (NoOp / Rejected). Lets the five edit use-cases share one IO shell
 * (`runLayoutEdit`, AUDIT-1 A1-14) instead of repeating read/transform/save.
 */
interface LayoutEditResult {
    /** The transformed layout to save, or `null` when nothing changed. */
    val layout: HomeLayout?
}
