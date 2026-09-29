package com.github.reygnn.launcher.common.ui.wallpaperfab

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class LayerButtonsStateTest {

    // ========== VISIBILITY ==========

    @Test
    fun `add button is always visible in edit mode`() {
        val state = LayerButtonsState.from(
            isMultiLayerMode = false,
            layerCount = 0,
            activeLayerIndex = -1,
        )
        assertThat(state.addVisible).isTrue()
    }

    @Test
    fun `other buttons are hidden when not in multi-layer mode`() {
        val state = LayerButtonsState.from(
            isMultiLayerMode = false,
            layerCount = 3,
            activeLayerIndex = 1,
        )
        assertThat(state.deleteVisible).isFalse()
        assertThat(state.upVisible).isFalse()
        assertThat(state.downVisible).isFalse()
    }

    @Test
    fun `other buttons are hidden when layerCount is zero`() {
        val state = LayerButtonsState.from(
            isMultiLayerMode = true,
            layerCount = 0,
            activeLayerIndex = -1,
        )
        assertThat(state.deleteVisible).isFalse()
        assertThat(state.upVisible).isFalse()
        assertThat(state.downVisible).isFalse()
    }

    @Test
    fun `other buttons are visible when in multi-layer mode with layers`() {
        val state = LayerButtonsState.from(
            isMultiLayerMode = true,
            layerCount = 2,
            activeLayerIndex = 0,
        )
        assertThat(state.deleteVisible).isTrue()
        assertThat(state.upVisible).isTrue()
        assertThat(state.downVisible).isTrue()
    }

    // ========== ENABLED STATE - UP ==========

    @Test
    fun `up button disabled when active layer is topmost`() {
        // activeIndex == layerCount - 1 -> kein Layer darüber
        val state = LayerButtonsState.from(
            isMultiLayerMode = true,
            layerCount = 3,
            activeLayerIndex = 2,
        )
        assertThat(state.upEnabled).isFalse()
    }

    @Test
    fun `up button enabled when active layer is not topmost`() {
        val state = LayerButtonsState.from(
            isMultiLayerMode = true,
            layerCount = 3,
            activeLayerIndex = 1,
        )
        assertThat(state.upEnabled).isTrue()
    }

    // ========== ENABLED STATE - DOWN ==========

    @Test
    fun `down button disabled when active layer is bottommost`() {
        val state = LayerButtonsState.from(
            isMultiLayerMode = true,
            layerCount = 3,
            activeLayerIndex = 0,
        )
        assertThat(state.downEnabled).isFalse()
    }

    @Test
    fun `down button enabled when active layer is not bottommost`() {
        val state = LayerButtonsState.from(
            isMultiLayerMode = true,
            layerCount = 3,
            activeLayerIndex = 1,
        )
        assertThat(state.downEnabled).isTrue()
    }

    // ========== ENABLED STATE - DELETE ==========

    @Test
    fun `delete disabled when no layer selected (activeIndex negative)`() {
        val state = LayerButtonsState.from(
            isMultiLayerMode = true,
            layerCount = 3,
            activeLayerIndex = -1,
        )
        assertThat(state.deleteEnabled).isFalse()
    }

    @Test
    fun `delete disabled when there are no layers even if activeIndex is 0 (defensive)`() {
        val state = LayerButtonsState.from(
            isMultiLayerMode = true,
            layerCount = 0,
            activeLayerIndex = 0,
        )
        assertThat(state.deleteEnabled).isFalse()
    }

    @Test
    fun `delete enabled when a layer is selected`() {
        val state = LayerButtonsState.from(
            isMultiLayerMode = true,
            layerCount = 2,
            activeLayerIndex = 0,
        )
        assertThat(state.deleteEnabled).isTrue()
    }

    // ========== EDGE CASE: SINGLE LAYER ==========

    @Test
    fun `single layer - delete enabled but up and down both disabled`() {
        // Nur 1 Layer, selektiert -> weder hoch noch runter möglich, aber löschbar
        val state = LayerButtonsState.from(
            isMultiLayerMode = true,
            layerCount = 1,
            activeLayerIndex = 0,
        )
        assertThat(state.deleteEnabled).isTrue()
        assertThat(state.upEnabled).isFalse()
        assertThat(state.downEnabled).isFalse()
    }
}
