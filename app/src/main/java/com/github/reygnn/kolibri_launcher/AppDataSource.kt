package com.github.reygnn.kolibri_launcher

/**
 * Interface zur Abstraktion der Datenquelle für installierte Apps.
 * Ermöglicht das Testen der Backup-Logik ohne Android-Abhängigkeiten.
 */
interface AppDataSource {
    /**
     * Ruft die ComponentNamen aller installierten Launcher-Apps ab.
     * z.B. "com.app1/.MainActivity"
     */
    suspend fun getInstalledComponents(): Set<String>
}