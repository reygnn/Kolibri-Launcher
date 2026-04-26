package com.github.reygnn.kolibri_launcher.ui.appcontextmenu

/**
 * Parsed shape of the action string sent by [AppContextMenuDialogFragment]
 * via `setFragmentResult`, as consumed by HomeFragment and AppDrawerFragment.
 *
 * Pattern matches the existing pure-logic extracts (LayerButtonsState,
 * SnapIconResolver, ...): this class makes the *decision*, the Fragment
 * carries out the *side effect*. No Android types, no Bundle handling —
 * Bundle reading stays in the Fragment, this class operates on the
 * already-extracted action string.
 *
 * Scope is the union of actions both consumer fragments care about. The
 * dialog filters which actions appear in which menu context — RESET_USAGE
 * only from [com.github.reygnn.kolibri_launcher.domain.model.MenuContext.APP_DRAWER],
 * UNHIDE_APP whenever `isHidden` is true regardless of context. Each
 * consumer's `when` may therefore include branches that, for that
 * consumer, are not expected to fire — these get a documented
 * `is X -> Unit` branch with a comment explaining why.
 *
 * RENAME_APP and RESTORE_NAME exist as [AppContextMenuAction] IDs but are
 * handled inside the dialog itself and never reach a result listener; they
 * collapse to [Unknown] alongside null and unrecognised strings.
 *
 * Compiler-driven exhaustiveness is the point. A consumer-side
 * `when (result)` without `else` will fail to compile once a new branch
 * is added here, forcing every consumer to acknowledge the new action.
 * That is why this is a sealed type rather than continued string
 * comparison at the call site.
 */
sealed interface ContextMenuResult {

    /** "launch_shortcut" — Fragment reads ShortcutInfo from the bundle separately. */
    data object LaunchShortcut : ContextMenuResult

    /** [AppContextMenuAction.ACTION_ID_APP_INFO] */
    data object AppInfo : ContextMenuResult

    /** [AppContextMenuAction.ACTION_ID_TOGGLE_FAVORITE] */
    data object ToggleFavorite : ContextMenuResult

    /** [AppContextMenuAction.ACTION_ID_HIDE_APP] */
    data object HideApp : ContextMenuResult

    /** [AppContextMenuAction.ACTION_ID_UNHIDE_APP] */
    data object UnhideApp : ContextMenuResult

    /** [AppContextMenuAction.ACTION_ID_RESET_USAGE] */
    data object ResetUsage : ContextMenuResult

    /**
     * Catch-all for null bundles, unknown action strings, and known IDs
     * that no consumer of this type handles (RENAME_APP, RESTORE_NAME).
     * Carries the original string so the consumer can log it.
     */
    data class Unknown(val action: String?) : ContextMenuResult

    companion object {
        /**
         * Mirrors the literal in [AppContextMenuDialogFragment]'s
         * `setFragmentResult(... "launch_shortcut" ...)` call. There is
         * currently no shared constant for this in
         * [AppContextMenuAction.Companion]; if one is introduced later,
         * this constant should be replaced by it. Out of scope for this
         * change.
         */
        const val ACTION_LAUNCH_SHORTCUT = "launch_shortcut"

        /**
         * Maps a raw action string (from
         * `bundle.getString(RESULT_KEY_ACTION)`) to a typed result.
         * Pure: no Android types, no IO, no side effects. `null`,
         * empty, and any unrecognised string become [Unknown].
         */
        fun parse(action: String?): ContextMenuResult = when (action) {
            ACTION_LAUNCH_SHORTCUT -> LaunchShortcut
            AppContextMenuAction.ACTION_ID_APP_INFO -> AppInfo
            AppContextMenuAction.ACTION_ID_TOGGLE_FAVORITE -> ToggleFavorite
            AppContextMenuAction.ACTION_ID_HIDE_APP -> HideApp
            AppContextMenuAction.ACTION_ID_UNHIDE_APP -> UnhideApp
            AppContextMenuAction.ACTION_ID_RESET_USAGE -> ResetUsage
            else -> Unknown(action)
        }
    }
}
