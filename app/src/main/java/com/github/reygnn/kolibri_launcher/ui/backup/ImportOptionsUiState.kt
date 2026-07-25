package com.github.reygnn.kolibri_launcher.ui.backup

import com.github.reygnn.kolibri_launcher.domain.model.BackupPreview

/**
 * PURE LOGIC - Import Options UI State
 *
 * Bildet ein [BackupPreview] auf den reinen UI-Zustand des Import-Options-Dialogs
 * ab. Keine Android-Abhängigkeiten - alle getString-Aufrufe und das Binding passieren
 * im Fragment.
 *
 * Bisher lag diese Logik in einem großen `dialogBinding.apply { ... }` Block in
 * BackupFragment. Herausgelöst fangen Unit-Tests Regressionen wie „neue Option
 * vergessen sichtbar zu prüfen" deterministisch ab.
 *
 * Konvention: Jede Option ist genau dann sichtbar UND initial checked, wenn die
 * Preview etwas zu importieren hat.
 *
 * Verwendung im Fragment:
 *
 *   val uiState = ImportOptionsUiState.from(preview)
 *   dialogBinding.apply {
 *       checkboxImportFavorites.isVisible = uiState.favorites.visible
 *       checkboxImportFavorites.isChecked = uiState.favorites.checked
 *       checkboxImportFavorites.text = getString(R.string.import_option_favorites, preview.favoriteCount)
 *       ...
 *   }
 */
data class ImportOptionsUiState(
    val dateHasTimestamp: Boolean,
    val favorites: CheckboxSpec,
    val order: CheckboxSpec,
    val hiddenApps: CheckboxSpec,
    val customNames: CheckboxSpec,
    val swipeActions: CheckboxSpec,
    /** Anzahl belegter Swipe-Slots (0, 1 oder 2) - für den Checkbox-Label-Placeholder. */
    val swipeActionCount: Int,
    val themeSettings: CheckboxSpec,
    val timeBasedEvents: CheckboxSpec,
    val qualityOfLife: CheckboxSpec,
    val powerUserSettings: CheckboxSpec,
) {
    companion object {
        fun from(preview: BackupPreview): ImportOptionsUiState {
            val swipeCount = listOf(preview.hasSwipeLeft, preview.hasSwipeRight).count { it }

            return ImportOptionsUiState(
                dateHasTimestamp = preview.timestamp > 0L,
                favorites = CheckboxSpec.visibleIf(preview.favoriteCount > 0),
                order = CheckboxSpec.visibleIf(preview.orderCount > 0),
                hiddenApps = CheckboxSpec.visibleIf(preview.hiddenCount > 0),
                customNames = CheckboxSpec.visibleIf(preview.customNamesCount > 0),
                swipeActions = CheckboxSpec.visibleIf(swipeCount > 0),
                swipeActionCount = swipeCount,
                themeSettings = CheckboxSpec.visibleIf(preview.hasThemeSettings),
                timeBasedEvents = CheckboxSpec.visibleIf(preview.hasTimeBasedEvents),
                qualityOfLife = CheckboxSpec.visibleIf(preview.hasQualityOfLife),
                powerUserSettings = CheckboxSpec.visibleIf(preview.hasPowerUserSettings),
            )
        }
    }
}

/**
 * Zustand einer einzelnen Checkbox im Import-Dialog.
 * Konvention im Projekt: sichtbar <=> initial checked.
 */
data class CheckboxSpec(
    val visible: Boolean,
    val checked: Boolean,
) {
    companion object {
        /** Baut einen Spec, der gleichzeitig visible UND checked ist, wenn condition wahr ist. */
        fun visibleIf(condition: Boolean) = CheckboxSpec(visible = condition, checked = condition)
    }
}
