package com.github.reygnn.kolibri_launcher

import android.content.pm.ShortcutInfo

/**
 * Ein Interface, das die Logik zum Abrufen von App-Verknüpfungen (Shortcuts) abstrahiert.
 * Dies ermöglicht es, die echte Implementierung, die System-APIs verwendet,
 * in Tests durch einen Mock zu ersetzen.
 */
interface ShortcutRepository : Purgeable {
    /**
     * Ruft die dynamischen und manifest-basierten Shortcuts für ein bestimmtes App-Paket ab.
     * @param packageName Der Paketname der App (z.B. "com.google.android.gm").
     * @return Eine Liste von ShortcutInfo-Objekten oder eine leere Liste, wenn keine gefunden wurden
     *         oder die Berechtigung fehlt.
     */
    fun getShortcutsForPackage(packageName: String): List<ShortcutInfo>
}