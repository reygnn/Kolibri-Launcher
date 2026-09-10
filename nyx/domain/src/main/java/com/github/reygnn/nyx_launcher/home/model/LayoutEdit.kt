package com.github.reygnn.nyx_launcher.home.model

/**
 * Lightweight result for the trivial edits (remove, rename) that either change
 * the layout or don't. See HOME_EDIT_USECASES_SPEC §1. [layout] is `null` ⇔ no
 * change ⇒ the use-case skips the save.
 */
sealed interface LayoutEdit {
    val layout: HomeLayout?

    data class Changed(override val layout: HomeLayout) : LayoutEdit

    data object NoOp : LayoutEdit {
        override val layout: HomeLayout? get() = null
    }
}
