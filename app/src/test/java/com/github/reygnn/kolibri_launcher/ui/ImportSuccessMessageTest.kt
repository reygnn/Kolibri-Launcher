package com.github.reygnn.kolibri_launcher.ui

import com.github.reygnn.kolibri_launcher.rule.TimberRule
import com.github.reygnn.kolibri_launcher.ui.backup.ImportSuccessMessage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class ImportSuccessMessageTest {

    @get:Rule
    val timberRule = TimberRule()

    // ========== SELECT VARIANT ==========

    @Test
    fun `select returns AppsImportedWithSkipped when both counts positive`() {
        val message = ImportSuccessMessage.select(importedCount = 5, skippedCount = 2)
        assertTrue(message is ImportSuccessMessage.AppsImportedWithSkipped)
        val m = message as ImportSuccessMessage.AppsImportedWithSkipped
        assertEquals(5, m.importedCount)
        assertEquals(2, m.skippedCount)
    }

    @Test
    fun `select returns AppsImported when imported positive and skipped zero`() {
        val message = ImportSuccessMessage.select(importedCount = 5, skippedCount = 0)
        assertTrue(message is ImportSuccessMessage.AppsImported)
        assertEquals(5, (message as ImportSuccessMessage.AppsImported).importedCount)
    }

    @Test
    fun `select returns SettingsOnly when imported zero and skipped zero`() {
        val message = ImportSuccessMessage.select(importedCount = 0, skippedCount = 0)
        assertEquals(ImportSuccessMessage.SettingsOnly, message)
    }

    @Test
    fun `select returns SettingsOnly when imported zero and skipped positive (defensive)`() {
        // Edge-Case: skipped > 0 aber imported == 0 (z.B. ALLE Apps fehlten im System)
        // -> Fallback auf SettingsOnly statt unpassendem „0 apps imported, N skipped".
        // Diese Regel ist bewusst und muss erhalten bleiben.
        val message = ImportSuccessMessage.select(importedCount = 0, skippedCount = 3)
        assertEquals(ImportSuccessMessage.SettingsOnly, message)
    }

    // ========== BOUNDARY ==========

    @Test
    fun `select returns AppsImported at boundary imported equals 1`() {
        val message = ImportSuccessMessage.select(importedCount = 1, skippedCount = 0)
        assertTrue(message is ImportSuccessMessage.AppsImported)
    }

    @Test
    fun `select returns AppsImportedWithSkipped at boundary skipped equals 1`() {
        val message = ImportSuccessMessage.select(importedCount = 1, skippedCount = 1)
        assertTrue(message is ImportSuccessMessage.AppsImportedWithSkipped)
    }

    // ========== DATA CLASS EQUALITY ==========

    @Test
    fun `AppsImported has value-based equality`() {
        assertEquals(
            ImportSuccessMessage.AppsImported(3),
            ImportSuccessMessage.AppsImported(3),
        )
    }

    @Test
    fun `AppsImportedWithSkipped has value-based equality`() {
        assertEquals(
            ImportSuccessMessage.AppsImportedWithSkipped(3, 2),
            ImportSuccessMessage.AppsImportedWithSkipped(3, 2),
        )
    }

    @Test
    fun `SettingsOnly is a singleton`() {
        // data object -> alle Referenzen sind identisch
        assertTrue(ImportSuccessMessage.SettingsOnly === ImportSuccessMessage.SettingsOnly)
    }
}
