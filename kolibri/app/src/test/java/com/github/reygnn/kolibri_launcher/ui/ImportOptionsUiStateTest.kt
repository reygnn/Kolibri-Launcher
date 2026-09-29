package com.github.reygnn.kolibri_launcher.ui

import com.github.reygnn.kolibri_launcher.domain.model.BackupPreview
import com.github.reygnn.kolibri_launcher.rule.TimberRule
import com.github.reygnn.kolibri_launcher.ui.backup.CheckboxSpec
import com.github.reygnn.kolibri_launcher.ui.backup.ImportOptionsUiState
import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
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
        assertThat(CheckboxSpec.visibleIf(true)).isEqualTo(CheckboxSpec(visible = true, checked = true))
        assertThat(CheckboxSpec.visibleIf(false)).isEqualTo(CheckboxSpec(visible = false, checked = false))
    }

    // ========== TIMESTAMP ==========

    @Test
    fun `dateHasTimestamp is true when timestamp is positive`() {
        val state = ImportOptionsUiState.from(fullPreview())
        assertThat(state.dateHasTimestamp).isTrue()
    }

    @Test
    fun `dateHasTimestamp is false when timestamp is zero`() {
        val state = ImportOptionsUiState.from(emptyPreview())
        assertThat(state.dateHasTimestamp).isFalse()
    }

    @Test
    fun `dateHasTimestamp is false when timestamp is negative (defensive)`() {
        val state = ImportOptionsUiState.from(fullPreview().copy(timestamp = -1L))
        assertThat(state.dateHasTimestamp).isFalse()
    }

    // ========== COUNT-BASED OPTIONS ==========

    @Test
    fun `favorites visible when count is greater than zero`() {
        val state = ImportOptionsUiState.from(fullPreview().copy(favoriteCount = 1))
        assertThat(state.favorites.visible).isTrue()
        assertThat(state.favorites.checked).isTrue()
    }

    @Test
    fun `favorites hidden when count is zero`() {
        val state = ImportOptionsUiState.from(fullPreview().copy(favoriteCount = 0))
        assertThat(state.favorites.visible).isFalse()
        assertThat(state.favorites.checked).isFalse()
    }

    @Test
    fun `order follows orderCount`() {
        assertThat(ImportOptionsUiState.from(fullPreview().copy(orderCount = 1)).order.visible).isTrue()
        assertThat(ImportOptionsUiState.from(fullPreview().copy(orderCount = 0)).order.visible).isFalse()
    }

    @Test
    fun `hiddenApps follows hiddenCount`() {
        assertThat(ImportOptionsUiState.from(fullPreview().copy(hiddenCount = 1)).hiddenApps.visible).isTrue()
        assertThat(ImportOptionsUiState.from(fullPreview().copy(hiddenCount = 0)).hiddenApps.visible).isFalse()
    }

    @Test
    fun `customNames follows customNamesCount`() {
        assertThat(ImportOptionsUiState.from(fullPreview().copy(customNamesCount = 1)).customNames.visible).isTrue()
        assertThat(ImportOptionsUiState.from(fullPreview().copy(customNamesCount = 0)).customNames.visible).isFalse()
    }

    // ========== SWIPE ACTIONS - combined logic ==========

    @Test
    fun `swipeActionCount is 0 when neither left nor right set`() {
        val state = ImportOptionsUiState.from(
            fullPreview().copy(hasSwipeLeft = false, hasSwipeRight = false),
        )
        assertThat(state.swipeActionCount).isEqualTo(0)
        assertThat(state.swipeActions.visible).isFalse()
    }

    @Test
    fun `swipeActionCount is 1 when only left is set`() {
        val state = ImportOptionsUiState.from(
            fullPreview().copy(hasSwipeLeft = true, hasSwipeRight = false),
        )
        assertThat(state.swipeActionCount).isEqualTo(1)
        assertThat(state.swipeActions.visible).isTrue()
    }

    @Test
    fun `swipeActionCount is 1 when only right is set`() {
        val state = ImportOptionsUiState.from(
            fullPreview().copy(hasSwipeLeft = false, hasSwipeRight = true),
        )
        assertThat(state.swipeActionCount).isEqualTo(1)
        assertThat(state.swipeActions.visible).isTrue()
    }

    @Test
    fun `swipeActionCount is 2 when both are set`() {
        val state = ImportOptionsUiState.from(
            fullPreview().copy(hasSwipeLeft = true, hasSwipeRight = true),
        )
        assertThat(state.swipeActionCount).isEqualTo(2)
        assertThat(state.swipeActions.visible).isTrue()
    }

    // ========== BOOLEAN-BASED OPTIONS ==========

    @Test
    fun `themeSettings follows hasThemeSettings`() {
        assertThat(ImportOptionsUiState.from(fullPreview().copy(hasThemeSettings = true)).themeSettings.visible).isTrue()
        assertThat(ImportOptionsUiState.from(fullPreview().copy(hasThemeSettings = false)).themeSettings.visible).isFalse()
    }

    @Test
    fun `wallpaper follows hasWallpaper (independent of theme)`() {
        assertThat(ImportOptionsUiState.from(fullPreview().copy(hasWallpaper = true)).wallpaper.visible).isTrue()
        assertThat(ImportOptionsUiState.from(fullPreview().copy(hasWallpaper = false)).wallpaper.visible).isFalse()
    }

    @Test
    fun `timeBasedEvents follows hasTimeBasedEvents`() {
        assertThat(ImportOptionsUiState.from(fullPreview().copy(hasTimeBasedEvents = true)).timeBasedEvents.visible).isTrue()
        assertThat(ImportOptionsUiState.from(fullPreview().copy(hasTimeBasedEvents = false)).timeBasedEvents.visible).isFalse()
    }

    @Test
    fun `qualityOfLife follows hasQualityOfLife`() {
        assertThat(ImportOptionsUiState.from(fullPreview().copy(hasQualityOfLife = true)).qualityOfLife.visible).isTrue()
        assertThat(ImportOptionsUiState.from(fullPreview().copy(hasQualityOfLife = false)).qualityOfLife.visible).isFalse()
    }

    @Test
    fun `powerUserSettings follows hasPowerUserSettings`() {
        assertThat(ImportOptionsUiState.from(fullPreview().copy(hasPowerUserSettings = true)).powerUserSettings.visible).isTrue()
        assertThat(ImportOptionsUiState.from(fullPreview().copy(hasPowerUserSettings = false)).powerUserSettings.visible).isFalse()
    }

    // ========== GLOBAL SWEEP ==========

    @Test
    fun `empty preview disables all options`() {
        val state = ImportOptionsUiState.from(emptyPreview())
        assertThat(state.favorites.visible).isFalse()
        assertThat(state.order.visible).isFalse()
        assertThat(state.hiddenApps.visible).isFalse()
        assertThat(state.customNames.visible).isFalse()
        assertThat(state.swipeActions.visible).isFalse()
        assertThat(state.themeSettings.visible).isFalse()
        assertThat(state.timeBasedEvents.visible).isFalse()
        assertThat(state.qualityOfLife.visible).isFalse()
        assertThat(state.powerUserSettings.visible).isFalse()
    }

    @Test
    fun `full preview enables all options`() {
        val state = ImportOptionsUiState.from(fullPreview())
        assertThat(state.favorites.visible).isTrue()
        assertThat(state.order.visible).isTrue()
        assertThat(state.hiddenApps.visible).isTrue()
        assertThat(state.customNames.visible).isTrue()
        assertThat(state.swipeActions.visible).isTrue()
        assertThat(state.themeSettings.visible).isTrue()
        assertThat(state.timeBasedEvents.visible).isTrue()
        assertThat(state.qualityOfLife.visible).isTrue()
        assertThat(state.powerUserSettings.visible).isTrue()
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
            assertWithMessage("visible != checked for $name: $spec").that(spec.checked).isEqualTo(spec.visible)
        }
    }
}
