package com.github.reygnn.kolibri_launcher.domain.model

/**
 * Eine sealed class, die alle möglichen Aktionen im App-Kontextmenü repräsentiert.
 * Sie ist rein datenbasiert und enthält keine UI-Elemente wie Icons.
 */
sealed class AppContextMenuAction {

    /**
     * Stellt eine von einer App bereitgestellte Verknüpfung dar.
     * Enthält den [LauncherShortcut], um ihn später starten zu können.
     */
    data class Shortcut(val shortcut: LauncherShortcut) : AppContextMenuAction()

    /**
     * Stellt eine Standard-Launcher-Aktion dar, die durch eine ID und ein
     * Domain-Label-Identifier definiert ist. Der Adapter mappt das
     * Identifier-Label am Render-Zeitpunkt zu einer `R.string.*`-Resource —
     * so kann der Build-Pfad dieser Liste auf JVM-Seite getestet werden,
     * ohne Android-Runtime und ohne dass die Domain `R.string`-Konstanten
     * referenziert.
     */
    data class LauncherAction(val id: String, val label: LauncherActionLabel) : AppContextMenuAction()

    /**
     * Stellt einen nicht klickbaren, visuellen Trenner dar.
     * Der Adapter kann diesen Typ erkennen und ein anderes Layout dafür verwenden.
     */
    object Separator : AppContextMenuAction()

    companion object {
        // IDs für die Standardaktionen, die als Ergebnis an das aufrufende Fragment gesendet werden.
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
