package com.github.reygnn.kolibri_launcher.ui.appcontextmenu

import android.content.pm.ShortcutInfo
import androidx.annotation.StringRes

/**
 * Eine sealed class, die alle möglichen Aktionen im App-Kontextmenü repräsentiert.
 * Sie ist rein datenbasiert und enthält keine UI-Elemente wie Icons.
 */
sealed class AppContextMenuAction {

    /**
     * Stellt eine von einer App bereitgestellte Verknüpfung dar.
     * Enthält die Original-ShortcutInfo, um sie später starten zu können.
     */
    data class Shortcut(val shortcutInfo: ShortcutInfo) : AppContextMenuAction()

    /**
     * Stellt eine Standard-Launcher-Aktion dar, die durch eine ID und einen
     * String-Resource-Verweis definiert ist. Der Adapter löst den Verweis am
     * Render-Zeitpunkt via `Context.getString` auf — so kann der Build-Pfad
     * dieser Liste auf JVM-Seite getestet werden, ohne Android-Runtime.
     */
    data class LauncherAction(val id: String, @param:StringRes val labelRes: Int) : AppContextMenuAction()

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