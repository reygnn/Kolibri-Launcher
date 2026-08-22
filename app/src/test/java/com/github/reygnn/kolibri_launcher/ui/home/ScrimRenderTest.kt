package com.github.reygnn.kolibri_launcher.ui.home

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ScrimRenderTest {

    @Test
    fun `zero alpha yields null (scrim GONE)`() {
        assertNull(ScrimRender.colorOrNull(alpha = 0f, isEditMode = false))
    }

    @Test
    fun `edit mode yields null even at max alpha`() {
        assertNull(ScrimRender.colorOrNull(alpha = 1f, isEditMode = true))
    }

    @Test
    fun `alpha rounding down to a zero byte yields null`() {
        // 0.001 * 255 = 0.255 → rounds to 0 → fully transparent → GONE.
        assertNull(ScrimRender.colorOrNull(alpha = 0.001f, isEditMode = false))
    }

    @Test
    fun `typical alpha bakes into the alpha byte over opaque black`() {
        // 0.2 * 255 = 51 → 0x33; RGB stays 0x000000.
        assertEquals(0x33000000.toInt(), ScrimRender.colorOrNull(alpha = 0.2f, isEditMode = false))
    }

    @Test
    fun `max alpha is fully opaque black`() {
        assertEquals(0xFF000000.toInt(), ScrimRender.colorOrNull(alpha = 1f, isEditMode = false))
    }

    @Test
    fun `alpha above 1 is clamped to opaque`() {
        assertEquals(0xFF000000.toInt(), ScrimRender.colorOrNull(alpha = 5f, isEditMode = false))
    }

    @Test
    fun `negative alpha is clamped to null`() {
        assertNull(ScrimRender.colorOrNull(alpha = -1f, isEditMode = false))
    }
}
