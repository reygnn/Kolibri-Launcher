package com.github.reygnn.kolibri_launcher.data

import com.github.reygnn.kolibri_launcher.domain.model.AppInfo

// =================================================================================
// --- TEST DATA SOURCE: Die zentrale Wahrheit für unsere Tests ---
// =================================================================================

/**
 * Dient als zentrale "In-Memory-Datenbank" für den Testzyklus.
 * Beide Fake-Repositories greifen auf diese eine Datenquelle zu,
 * um Konsistenz zu gewährleisten.
 */
object TestDataSource {
    // Die unveränderliche Liste der "installierten" Apps
    private val rawApps = listOf(
        AppInfo("Alpha Browser", "Alpha Browser", "com.alpha.browser", "com.alpha.browser.Main"),
        AppInfo(
            "Beta Calculator",
            "Beta Calculator",
            "com.beta.calculator",
            "com.beta.calculator.Main"
        ),
        AppInfo("Zeta Clock", "Zeta Clock", "com.zeta.clock", "com.zeta.clock.Main")
    )

    // Die veränderliche Map der benutzerdefinierten Namen
    private val customNames = mutableMapOf<String, String>()

    /**
     * Erstellt die prozessierte und sortierte App-Liste, die die UI anzeigen würde.
     * Sie wendet die benutzerdefinierten Namen auf die Rohdaten an.
     */
    fun getProcessedList(): List<AppInfo> {
        return rawApps.map { app ->
            app.copy(displayName = customNames[app.packageName] ?: app.originalName)
        }.sortedBy { it.displayName.lowercase() }
    }

    /** Setzt die benutzerdefinierten Namen für den nächsten Test zurück. */
    fun clearCustomNames() {
        customNames.clear()
    }

    fun setCustomName(packageName: String, name: String) {
        customNames[packageName] = name
    }

    fun removeCustomName(packageName: String) {
        customNames.remove(packageName)
    }

    fun getDisplayName(packageName: String, originalName: String): String {
        return customNames[packageName] ?: originalName
    }

    fun hasCustomName(packageName: String): Boolean {
        return customNames.containsKey(packageName)
    }

    fun getAllCustomNames(): Map<String, String> {
        return customNames.toMap()
    }
}