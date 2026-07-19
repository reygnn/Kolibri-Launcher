package com.github.reygnn.kolibri_launcher.ui.home

/**
 * PURE LOGIC - Layer Buttons State
 *
 * Berechnet Sichtbarkeit, Enabled-Status und Alpha-Werte der Layer-Edit-Buttons
 * (Add / Delete / Up / Down / Indicator) basierend auf dem aktuellen Zustand
 * der WallpaperView.
 *
 * Konsumiert wird das Ergebnis vom Fragment in `applyLayerButtonsState`, das die
 * Boolean-Felder auf Visibility/Enabled/Alpha der echten Buttons abbildet. Als
 * reine Datenklasse ist diese Berechnung isoliert per JUnit testbar.
 *
 * Regeln:
 *  - Add: immer sichtbar im Edit-Mode
 *  - Delete/Up/Down/Indicator: nur sichtbar wenn isMultiLayerMode && layerCount > 0
 *  - Up enabled: activeLayerIndex < layerCount - 1
 *  - Down enabled: activeLayerIndex > 0
 *  - Delete enabled: activeLayerIndex >= 0 && layerCount > 0
 */
data class LayerButtonsState(
    val addVisible: Boolean,
    val deleteVisible: Boolean,
    val upVisible: Boolean,
    val downVisible: Boolean,
    val upEnabled: Boolean,
    val downEnabled: Boolean,
    val deleteEnabled: Boolean,
) {
    companion object {
        fun from(
            isMultiLayerMode: Boolean,
            layerCount: Int,
            activeLayerIndex: Int,
        ): LayerButtonsState {
            val hasLayers = isMultiLayerMode && layerCount > 0
            val upEnabled = activeLayerIndex < layerCount - 1
            val downEnabled = activeLayerIndex > 0
            val deleteEnabled = activeLayerIndex >= 0 && layerCount > 0

            return LayerButtonsState(
                addVisible = true,
                deleteVisible = hasLayers,
                upVisible = hasLayers,
                downVisible = hasLayers,
                upEnabled = upEnabled,
                downEnabled = downEnabled,
                deleteEnabled = deleteEnabled,
            )
        }
    }
}
