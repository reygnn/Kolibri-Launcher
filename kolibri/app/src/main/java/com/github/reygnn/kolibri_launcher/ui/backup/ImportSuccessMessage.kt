package com.github.reygnn.kolibri_launcher.ui.backup

/**
 * PURE LOGIC - Import Success Message
 *
 * Entscheidet anhand von importedCount/skippedCount, welche Erfolgsmeldung
 * nach einem Import gezeigt werden soll. Die StringResource-Auflösung und
 * das getString(...) mit Format-Argumenten bleiben im Fragment -
 * wir liefern nur die strukturierte Entscheidung.
 *
 * Varianten:
 *  - [AppsImportedWithSkipped]: Apps importiert UND welche übersprungen (nicht installiert)
 *  - [AppsImported]: Apps importiert, keine übersprungen
 *  - [SettingsOnly]: gar keine Apps importiert (nur Settings/Gesten/Theme/...)
 *
 * Verwendung im Fragment:
 *
 *   val message = when (val m = ImportSuccessMessage.select(state.importedCount, state.skippedCount)) {
 *       is ImportSuccessMessage.AppsImportedWithSkipped ->
 *           getString(R.string.backup_import_success_with_skipped, m.importedCount, m.skippedCount)
 *       is ImportSuccessMessage.AppsImported ->
 *           getString(R.string.backup_import_success_simple, m.importedCount)
 *       ImportSuccessMessage.SettingsOnly ->
 *           getString(R.string.backup_import_success_settings_only)
 *   }
 */
sealed class ImportSuccessMessage {
    data class AppsImportedWithSkipped(
        val importedCount: Int,
        val skippedCount: Int,
    ) : ImportSuccessMessage()

    data class AppsImported(
        val importedCount: Int,
    ) : ImportSuccessMessage()

    data object SettingsOnly : ImportSuccessMessage()

    companion object {
        fun select(importedCount: Int, skippedCount: Int): ImportSuccessMessage = when {
            importedCount > 0 && skippedCount > 0 ->
                AppsImportedWithSkipped(importedCount, skippedCount)
            importedCount > 0 ->
                AppsImported(importedCount)
            else ->
                SettingsOnly
        }
    }
}
