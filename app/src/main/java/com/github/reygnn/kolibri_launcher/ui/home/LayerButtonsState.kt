package com.github.reygnn.kolibri_launcher.ui.home

/**
 * PURE LOGIC - Layer Buttons State
 *
 * Berechnet Sichtbarkeit, Enabled-Status und Alpha-Werte der Layer-Edit-Buttons
 * (Add / Delete / Up / Down / Indicator) basierend auf dem aktuellen Zustand
 * der WallpaperView.
 *
 * Zuvor war diese Logik über zwei Fragment-Methoden (updateLayerButtonsVisibility,
 * updateLayerButtonStates) mit viel binding.* Boilerplate verteilt. Als reine
 * Datenklasse ist sie isoliert per JUnit testbar.
 *
 * Regeln:
 *  - Add: immer sichtbar im Edit-Mode
 *  - Delete/Up/Down/Indicator: nur sichtbar wenn isMultiLayerMode && layerCount > 0
 *  - Up enabled: activeLayerIndex < layerCount - 1
 *  - Down enabled: activeLayerIndex > 0
 *  - Delete enabled: activeLayerIndex >= 0 && layerCount > 0
 *  - Alpha: ENABLED_ALPHA wenn enabled, DISABLED_ALPHA sonst (nur für Up/Down -
 *    die Originalimplementierung setzte Alpha nicht für Delete)
 */
data class LayerButtonsState(
    val addVisible: Boolean,
    val deleteVisible: Boolean,
    val upVisible: Boolean,
    val downVisible: Boolean,
    val indicatorVisible: Boolean,
    val upEnabled: Boolean,
    val downEnabled: Boolean,
    val deleteEnabled: Boolean,
    val upAlpha: Float,
    val downAlpha: Float,
) {
    companion object {
        const val ENABLED_ALPHA = 1.0f
        const val DISABLED_ALPHA = 0.3f

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
                indicatorVisible = hasLayers,
                upEnabled = upEnabled,
                downEnabled = downEnabled,
                deleteEnabled = deleteEnabled,
                upAlpha = if (upEnabled) ENABLED_ALPHA else DISABLED_ALPHA,
                downAlpha = if (downEnabled) ENABLED_ALPHA else DISABLED_ALPHA,
            )
        }
    }
}
