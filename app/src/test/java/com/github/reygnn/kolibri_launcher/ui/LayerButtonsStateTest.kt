package com.github.reygnn.kolibri_launcher.ui

import com.github.reygnn.kolibri_launcher.rule.TimberRule
import com.github.reygnn.kolibri_launcher.ui.home.LayerButtonsState
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class LayerButtonsStateTest {

    @get:Rule
    val timberRule = TimberRule()

    // ========== VISIBILITY ==========

    @Test
    fun `add button is always visible in edit mode`() {
        val state = LayerButtonsState.from(
            isMultiLayerMode = false,
            layerCount = 0,
            activeLayerIndex = -1,
        )
        assertTrue(state.addVisible)
    }

    @Test
    fun `other buttons are hidden when not in multi-layer mode`() {
        val state = LayerButtonsState.from(
            isMultiLayerMode = false,
            layerCount = 3,
            activeLayerIndex = 1,
        )
        assertFalse(state.deleteVisible)
        assertFalse(state.upVisible)
        assertFalse(state.downVisible)
    }

    @Test
    fun `other buttons are hidden when layerCount is zero`() {
        val state = LayerButtonsState.from(
            isMultiLayerMode = true,
            layerCount = 0,
            activeLayerIndex = -1,
        )
        assertFalse(state.deleteVisible)
        assertFalse(state.upVisible)
        assertFalse(state.downVisible)
    }

    @Test
    fun `other buttons are visible when in multi-layer mode with layers`() {
        val state = LayerButtonsState.from(
            isMultiLayerMode = true,
            layerCount = 2,
            activeLayerIndex = 0,
        )
        assertTrue(state.deleteVisible)
        assertTrue(state.upVisible)
        assertTrue(state.downVisible)
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
        assertFalse(state.upEnabled)
    }

    @Test
    fun `up button enabled when active layer is not topmost`() {
        val state = LayerButtonsState.from(
            isMultiLayerMode = true,
            layerCount = 3,
            activeLayerIndex = 1,
        )
        assertTrue(state.upEnabled)
    }

    // ========== ENABLED STATE - DOWN ==========

    @Test
    fun `down button disabled when active layer is bottommost`() {
        val state = LayerButtonsState.from(
            isMultiLayerMode = true,
            layerCount = 3,
            activeLayerIndex = 0,
        )
        assertFalse(state.downEnabled)
    }

    @Test
    fun `down button enabled when active layer is not bottommost`() {
        val state = LayerButtonsState.from(
            isMultiLayerMode = true,
            layerCount = 3,
            activeLayerIndex = 1,
        )
        assertTrue(state.downEnabled)
    }

    // ========== ENABLED STATE - DELETE ==========

    @Test
    fun `delete disabled when no layer selected (activeIndex negative)`() {
        val state = LayerButtonsState.from(
            isMultiLayerMode = true,
            layerCount = 3,
            activeLayerIndex = -1,
        )
        assertFalse(state.deleteEnabled)
    }

    @Test
    fun `delete disabled when there are no layers even if activeIndex is 0 (defensive)`() {
        val state = LayerButtonsState.from(
            isMultiLayerMode = true,
            layerCount = 0,
            activeLayerIndex = 0,
        )
        assertFalse(state.deleteEnabled)
    }

    @Test
    fun `delete enabled when a layer is selected`() {
        val state = LayerButtonsState.from(
            isMultiLayerMode = true,
            layerCount = 2,
            activeLayerIndex = 0,
        )
        assertTrue(state.deleteEnabled)
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
        assertTrue(state.deleteEnabled)
        assertFalse(state.upEnabled)
        assertFalse(state.downEnabled)
    }
}
