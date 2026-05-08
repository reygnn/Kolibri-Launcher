package com.github.reygnn.kolibri_launcher.domain.model

/**
 * Sealed class representing every possible action in the app context menu.
 * Pure data — no UI elements such as icons.
 */
sealed class AppContextMenuAction {

    /**
     * An app-provided shortcut. Carries the [LauncherShortcut] so it can
     * be launched later.
     */
    data class Shortcut(val shortcut: LauncherShortcut) : AppContextMenuAction()

    /**
     * A standard launcher action identified by an ID and a domain-side
     * label identifier. The adapter maps the label identifier to an
     * `R.string.*` resource at render time, so the build path of this
     * list is JVM-testable without the Android runtime and without the
     * domain referencing `R.string` constants.
     */
    data class LauncherAction(val id: String, val label: LauncherActionLabel) : AppContextMenuAction()

    /**
     * A non-clickable visual separator. The adapter can recognise this
     * type and use a different layout for it.
     */
    object Separator : AppContextMenuAction()

    companion object {
        // IDs for the standard actions sent back as results to the calling fragment.
        const val ACTION_ID_APP_INFO = "app_info"
        const val ACTION_ID_TOGGLE_FAVORITE = "toggle_favorite"
        const val ACTION_ID_HIDE_APP = "hide_app"
        const val ACTION_ID_UNHIDE_APP = "unhide_app"
        const val ACTION_ID_RESET_USAGE = "reset_usage"
        const val ACTION_ID_RENAME_APP = "rename_app"
        const val ACTION_ID_RESTORE_NAME = "restore_name"
    }
}

/**
 * Domain identifiers for the labels of [AppContextMenuAction.LauncherAction].
 * UI-side maps each variant to a string resource via
 * `LauncherActionLabel.toStringResId()`.
 */
sealed class LauncherActionLabel {
    object AddToFavorites : LauncherActionLabel()
    object RemoveFromFavorites : LauncherActionLabel()
    object RestoreOriginalName : LauncherActionLabel()
    object RenameApp : LauncherActionLabel()
    object UnhideAppInDrawer : LauncherActionLabel()
    object HideAppFromDrawer : LauncherActionLabel()
    object ResetUsage : LauncherActionLabel()
    object AppInfo : LauncherActionLabel()
}
