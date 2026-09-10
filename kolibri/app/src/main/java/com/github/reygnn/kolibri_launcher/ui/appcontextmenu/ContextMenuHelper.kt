package com.github.reygnn.kolibri_launcher.ui.appcontextmenu

import androidx.fragment.app.DialogFragment
import androidx.fragment.app.FragmentManager
import com.github.reygnn.kolibri_launcher.core.TimberWrapper
import com.github.reygnn.kolibri_launcher.domain.model.AppInfo
import com.github.reygnn.kolibri_launcher.domain.model.MenuContext

/**
 * Zentraler Helper zum Anzeigen und Schließen des Kontextmenüs.
 * Verhindert Code-Duplizierung in HomeFragment und AppDrawerFragment.
 */
object ContextMenuHelper {

    /**
     * Schließt ein eventuell offenes Kontextmenü sicher.
     * Verhindert Memory Leaks, da keine Referenz gehalten werden muss.
     */
    fun dismiss(fragmentManager: FragmentManager) {
        try {
            val existingFragment = fragmentManager.findFragmentByTag(AppContextMenuDialogFragment.TAG)
            if (existingFragment is DialogFragment) {
                existingFragment.dismissAllowingStateLoss()
            }
        } catch (e: Throwable) {
            TimberWrapper.silentError(e, "Error dismissing context menu")
        }
    }

    /**
     * Öffnet das Kontextmenü sicher. Schliesst vorherige Instanzen automatisch.
     */
    fun show(
        fragmentManager: FragmentManager,
        app: AppInfo,
        menuContext: MenuContext,
        hasUsage: Boolean = false
    ) {
        try {
            // 1. Safety First: Altes aufräumen
            dismiss(fragmentManager)

            // 2. Neuen Dialog erstellen
            val dialog = AppContextMenuDialogFragment.newInstance(
                app,
                menuContext,
                hasUsage
            )

            // 3. Anzeigen
            dialog.show(fragmentManager, AppContextMenuDialogFragment.TAG)

        } catch (e: Throwable) {
            TimberWrapper.silentError(e, "Error showing context menu for ${app.packageName}")
        }
    }
}