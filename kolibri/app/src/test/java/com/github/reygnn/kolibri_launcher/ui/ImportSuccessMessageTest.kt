package com.github.reygnn.kolibri_launcher.ui

import com.github.reygnn.kolibri_launcher.rule.TimberRule
import com.github.reygnn.kolibri_launcher.ui.backup.ImportSuccessMessage
import com.google.common.truth.Truth.assertThat
import org.junit.Rule
import org.junit.Test

class ImportSuccessMessageTest {

    @get:Rule
    val timberRule = TimberRule()

    // ========== SELECT VARIANT ==========

    @Test
    fun `select returns AppsImportedWithSkipped when both counts positive`() {
        val message = ImportSuccessMessage.select(importedCount = 5, skippedCount = 2)
        assertThat(message).isInstanceOf(ImportSuccessMessage.AppsImportedWithSkipped::class.java)
        val m = message as ImportSuccessMessage.AppsImportedWithSkipped
        assertThat(m.importedCount).isEqualTo(5)
        assertThat(m.skippedCount).isEqualTo(2)
    }

    @Test
    fun `select returns AppsImported when imported positive and skipped zero`() {
        val message = ImportSuccessMessage.select(importedCount = 5, skippedCount = 0)
        assertThat(message).isInstanceOf(ImportSuccessMessage.AppsImported::class.java)
        assertThat((message as ImportSuccessMessage.AppsImported).importedCount).isEqualTo(5)
    }

    @Test
    fun `select returns SettingsOnly when imported zero and skipped zero`() {
        val message = ImportSuccessMessage.select(importedCount = 0, skippedCount = 0)
        assertThat(message).isEqualTo(ImportSuccessMessage.SettingsOnly)
    }

    @Test
    fun `select returns SettingsOnly when imported zero and skipped positive (defensive)`() {
        // Edge-Case: skipped > 0 aber imported == 0 (z.B. ALLE Apps fehlten im System)
        // -> Fallback auf SettingsOnly statt unpassendem „0 apps imported, N skipped".
        // Diese Regel ist bewusst und muss erhalten bleiben.
        val message = ImportSuccessMessage.select(importedCount = 0, skippedCount = 3)
        assertThat(message).isEqualTo(ImportSuccessMessage.SettingsOnly)
    }

    // ========== BOUNDARY ==========

    @Test
    fun `select returns AppsImported at boundary imported equals 1`() {
        val message = ImportSuccessMessage.select(importedCount = 1, skippedCount = 0)
        assertThat(message).isInstanceOf(ImportSuccessMessage.AppsImported::class.java)
    }

    @Test
    fun `select returns AppsImportedWithSkipped at boundary skipped equals 1`() {
        val message = ImportSuccessMessage.select(importedCount = 1, skippedCount = 1)
        assertThat(message).isInstanceOf(ImportSuccessMessage.AppsImportedWithSkipped::class.java)
    }

    // ========== DATA CLASS EQUALITY ==========

    @Test
    fun `AppsImported has value-based equality`() {
        assertThat(ImportSuccessMessage.AppsImported(3)).isEqualTo(ImportSuccessMessage.AppsImported(3))
    }

    @Test
    fun `AppsImportedWithSkipped has value-based equality`() {
        assertThat(ImportSuccessMessage.AppsImportedWithSkipped(3, 2)).isEqualTo(ImportSuccessMessage.AppsImportedWithSkipped(3, 2))
    }

    @Test
    fun `SettingsOnly is a singleton`() {
        // data object -> alle Referenzen sind identisch
        assertThat(ImportSuccessMessage.SettingsOnly === ImportSuccessMessage.SettingsOnly).isTrue()
    }
}
