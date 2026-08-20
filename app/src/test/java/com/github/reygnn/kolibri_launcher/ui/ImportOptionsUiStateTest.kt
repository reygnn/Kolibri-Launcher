package com.github.reygnn.kolibri_launcher.ui

import com.github.reygnn.kolibri_launcher.domain.model.BackupPreview
import com.github.reygnn.kolibri_launcher.rule.TimberRule
import com.github.reygnn.kolibri_launcher.ui.backup.CheckboxSpec
import com.github.reygnn.kolibri_launcher.ui.backup.ImportOptionsUiState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class ImportOptionsUiStateTest {

    @get:Rule
    val timberRule = TimberRule()

    /** Preview voll ausgefüllt - alle Optionen "aktiv". */
    private fun fullPreview(): BackupPreview = BackupPreview(
        version = "1.0.0",
        timestamp = 1_700_000_000L,
        favoriteCount = 5,
        orderCount = 5,
        hiddenCount = 2,
        customNamesCount = 3,
        hasSwipeLeft = true,
        hasSwipeRight = true,
        hasThemeSettings = true,
        hasWallpaper = false,
        wallpaperLayerCount = 0,
        hasTimeBasedEvents = true,
        hasQualityOfLife = true,
        hasPowerUserSettings = true,
    )

    /** Preview komplett leer. */
    private fun emptyPreview(): BackupPreview = BackupPreview(
        version = "1.0.0",
        timestamp = 0L,
        favoriteCount = 0,
        orderCount = 0,
        hiddenCount = 0,
        customNamesCount = 0,
        hasSwipeLeft = false,
        hasSwipeRight = false,
        hasThemeSettings = false,
        hasWallpaper = false,
        wallpaperLayerCount = 0,
        hasTimeBasedEvents = false,
        hasQualityOfLife = false,
        hasPowerUserSettings = false,
    )

    // ========== CHECKBOX SPEC CONVENTION ==========

    @Test
    fun `visibleIf sets both visible and checked to the same value`() {
        assertEquals(CheckboxSpec(visible = true, checked = true), CheckboxSpec.visibleIf(true))
        assertEquals(CheckboxSpec(visible = false, checked = false), CheckboxSpec.visibleIf(false))
    }

    // ========== TIMESTAMP ==========

    @Test
    fun `dateHasTimestamp is true when timestamp is positive`() {
        val state = ImportOptionsUiState.from(fullPreview())
        assertTrue(state.dateHasTimestamp)
    }

    @Test
    fun `dateHasTimestamp is false when timestamp is zero`() {
        val state = ImportOptionsUiState.from(emptyPreview())
        assertFalse(state.dateHasTimestamp)
    }

    @Test
    fun `dateHasTimestamp is false when timestamp is negative (defensive)`() {
        val state = ImportOptionsUiState.from(fullPreview().copy(timestamp = -1L))
        assertFalse(state.dateHasTimestamp)
    }

    // ========== COUNT-BASED OPTIONS ==========

    @Test
    fun `favorites visible when count is greater than zero`() {
        val state = ImportOptionsUiState.from(fullPreview().copy(favoriteCount = 1))
        assertTrue(state.favorites.visible)
        assertTrue(state.favorites.checked)
    }

    @Test
    fun `favorites hidden when count is zero`() {
        val state = ImportOptionsUiState.from(fullPreview().copy(favoriteCount = 0))
        assertFalse(state.favorites.visible)
        assertFalse(state.favorites.checked)
    }

    @Test
    fun `order follows orderCount`() {
        assertTrue(ImportOptionsUiState.from(fullPreview().copy(orderCount = 1)).order.visible)
        assertFalse(ImportOptionsUiState.from(fullPreview().copy(orderCount = 0)).order.visible)
    }

    @Test
    fun `hiddenApps follows hiddenCount`() {
        assertTrue(ImportOptionsUiState.from(fullPreview().copy(hiddenCount = 1)).hiddenApps.visible)
        assertFalse(ImportOptionsUiState.from(fullPreview().copy(hiddenCount = 0)).hiddenApps.visible)
    }

    @Test
    fun `customNames follows customNamesCount`() {
        assertTrue(ImportOptionsUiState.from(fullPreview().copy(customNamesCount = 1)).customNames.visible)
        assertFalse(ImportOptionsUiState.from(fullPreview().copy(customNamesCount = 0)).customNames.visible)
    }

    // ========== SWIPE ACTIONS - combined logic ==========

    @Test
    fun `swipeActionCount is 0 when neither left nor right set`() {
        val state = ImportOptionsUiState.from(
            fullPreview().copy(hasSwipeLeft = false, hasSwipeRight = false),
        )
        assertEquals(0, state.swipeActionCount)
        assertFalse(state.swipeActions.visible)
    }

    @Test
    fun `swipeActionCount is 1 when only left is set`() {
        val state = ImportOptionsUiState.from(
            fullPreview().copy(hasSwipeLeft = true, hasSwipeRight = false),
        )
        assertEquals(1, state.swipeActionCount)
        assertTrue(state.swipeActions.visible)
    }

    @Test
    fun `swipeActionCount is 1 when only right is set`() {
        val state = ImportOptionsUiState.from(
            fullPreview().copy(hasSwipeLeft = false, hasSwipeRight = true),
        )
        assertEquals(1, state.swipeActionCount)
        assertTrue(state.swipeActions.visible)
    }

    @Test
    fun `swipeActionCount is 2 when both are set`() {
        val state = ImportOptionsUiState.from(
            fullPreview().copy(hasSwipeLeft = true, hasSwipeRight = true),
        )
        assertEquals(2, state.swipeActionCount)
        assertTrue(state.swipeActions.visible)
    }

    // ========== BOOLEAN-BASED OPTIONS ==========

    @Test
    fun `themeSettings follows hasThemeSettings`() {
        assertTrue(ImportOptionsUiState.from(fullPreview().copy(hasThemeSettings = true)).themeSettings.visible)
        assertFalse(ImportOptionsUiState.from(fullPreview().copy(hasThemeSettings = false)).themeSettings.visible)
    }

    @Test
    fun `wallpaper follows hasWallpaper (independent of theme)`() {
        assertTrue(ImportOptionsUiState.from(fullPreview().copy(hasWallpaper = true)).wallpaper.visible)
        assertFalse(ImportOptionsUiState.from(fullPreview().copy(hasWallpaper = false)).wallpaper.visible)
    }

    @Test
    fun `timeBasedEvents follows hasTimeBasedEvents`() {
        assertTrue(ImportOptionsUiState.from(fullPreview().copy(hasTimeBasedEvents = true)).timeBasedEvents.visible)
        assertFalse(ImportOptionsUiState.from(fullPreview().copy(hasTimeBasedEvents = false)).timeBasedEvents.visible)
    }

    @Test
    fun `qualityOfLife follows hasQualityOfLife`() {
        assertTrue(ImportOptionsUiState.from(fullPreview().copy(hasQualityOfLife = true)).qualityOfLife.visible)
        assertFalse(ImportOptionsUiState.from(fullPreview().copy(hasQualityOfLife = false)).qualityOfLife.visible)
    }

    @Test
    fun `powerUserSettings follows hasPowerUserSettings`() {
        assertTrue(ImportOptionsUiState.from(fullPreview().copy(hasPowerUserSettings = true)).powerUserSettings.visible)
        assertFalse(ImportOptionsUiState.from(fullPreview().copy(hasPowerUserSettings = false)).powerUserSettings.visible)
    }

    // ========== GLOBAL SWEEP ==========

    @Test
    fun `empty preview disables all options`() {
        val state = ImportOptionsUiState.from(emptyPreview())
        assertFalse(state.favorites.visible)
        assertFalse(state.order.visible)
        assertFalse(state.hiddenApps.visible)
        assertFalse(state.customNames.visible)
        assertFalse(state.swipeActions.visible)
        assertFalse(state.themeSettings.visible)
        assertFalse(state.timeBasedEvents.visible)
        assertFalse(state.qualityOfLife.visible)
        assertFalse(state.powerUserSettings.visible)
    }

    @Test
    fun `full preview enables all options`() {
        val state = ImportOptionsUiState.from(fullPreview())
        assertTrue(state.favorites.visible)
        assertTrue(state.order.visible)
        assertTrue(state.hiddenApps.visible)
        assertTrue(state.customNames.visible)
        assertTrue(state.swipeActions.visible)
        assertTrue(state.themeSettings.visible)
        assertTrue(state.timeBasedEvents.visible)
        assertTrue(state.qualityOfLife.visible)
        assertTrue(state.powerUserSettings.visible)
    }

    @Test
    fun `convention invariant - every checkbox has visible equal to checked`() {
        // Schlägt fehl, falls jemand diese Konvention bricht.
        val state = ImportOptionsUiState.from(fullPreview())
        listOf(
            "favorites" to state.favorites,
            "order" to state.order,
            "hiddenApps" to state.hiddenApps,
            "customNames" to state.customNames,
            "swipeActions" to state.swipeActions,
            "themeSettings" to state.themeSettings,
            "timeBasedEvents" to state.timeBasedEvents,
            "qualityOfLife" to state.qualityOfLife,
            "powerUserSettings" to state.powerUserSettings,
        ).forEach { (name, spec) ->
            assertEquals("visible != checked for $name: $spec", spec.visible, spec.checked)
        }
    }
}
