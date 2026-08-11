package com.github.reygnn.kolibri_launcher.ui.appdrawer

import com.github.reygnn.kolibri_launcher.domain.model.AppInfo
import com.github.reygnn.kolibri_launcher.domain.model.filterByName

/**
 * PURE LOGIC - App Search Decision Engine
 *
 * Entscheidet, welche Apps angezeigt werden und ob ein Auto-Launch
 * ausgelöst werden soll. Keine Android-Dependencies!
 */
class AppSearchFilter {

    /**
     * @param allApps Die vollständige Liste aller Apps
     * @param query Der Suchtext (kann leer sein)
     * @param isAutoLaunchEnabled User-Setting für Auto-Launch
     * @return Das Ergebnis der Berechnung (Liste anzeigen oder App starten)
     */
    fun filterAndDecide(
        allApps: List<AppInfo>,
        query: String,
        isAutoLaunchEnabled: Boolean
    ): FilterResult {

        // 1. Defensive: Leere Liste oder null inputs abfangen
        if (allApps.isEmpty()) {
            return FilterResult.ShowList(emptyList())
        }

        // 2. Filter (case-insensitive) via the shared name filter (AUDIT-15 F2 /
        // AUDIT-16 N2): fold-once + precomputed displayNameLower, one source for
        // every search surface.
        val filteredList = allApps.filterByName(query)

        // 3. Auto-Launch Entscheidung
        // Nur wenn: Query nicht leer UND genau 1 Treffer UND Setting aktiv
        if (query.isNotBlank() &&
            filteredList.size == 1 &&
            isAutoLaunchEnabled) {
            return FilterResult.AutoLaunch(filteredList.first())
        }

        // 4. Standard: Liste anzeigen
        return FilterResult.ShowList(filteredList)
    }

    sealed interface FilterResult {
        /** Zeige diese Liste im Adapter an */
        data class ShowList(val apps: List<AppInfo>) : FilterResult

        /** Starte diese App sofort (und schließe Keyboard) */
        data class AutoLaunch(val app: AppInfo) : FilterResult
    }
}