package com.github.reygnn.kolibri_launcher.ui.home.wallpaper

import com.github.reygnn.kolibri_launcher.domain.model.WallpaperLayerState
import com.github.reygnn.kolibri_launcher.domain.model.WallpaperState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.lang.reflect.Modifier

/**
 * The single most important guard in WALLPAPER_COMPOSITE_LIFECYCLE_SPEC v4 (§2/§7 test 1).
 *
 * The composite lives only in RAM, keyed by [WallpaperCompositeKey]. A miss re-flattens, so the
 * key IS the correctness condition: it must cover EVERY pixel-affecting input, or two
 * visually-different composites collide on one key and the stale one is served indefinitely.
 * These tests pin per-field sensitivity AND — via a schema canary — fail the day a new
 * `WallpaperLayerState` field is added without a decision about whether it belongs in the key.
 */
class WallpaperCompositeKeyTest {

    private val w = 1080
    private val h = 2340

    private fun layer(
        imageUri: String? = "file:///l.jpg",
        scale: Float = 1.0f,
        translateX: Float = 0f,
        translateY: Float = 0f,
        alpha: Float = 1.0f,
        blendModeName: String? = null,
        isVisible: Boolean = true,
        captureSampleSize: Int? = null,
        id: String = "fixed-id",
        label: String? = null,
    ) = WallpaperLayerState(
        id = id,
        imageUri = imageUri,
        scale = scale,
        translateX = translateX,
        translateY = translateY,
        alpha = alpha,
        blendModeName = blendModeName,
        isVisible = isVisible,
        label = label,
        captureSampleSize = captureSampleSize,
    )

    private fun state(vararg layers: WallpaperLayerState) = WallpaperState(layers = layers.toList())

    private fun key(state: WallpaperState, width: Int = w, height: Int = h) =
        WallpaperCompositeKey.of(state, width, height)

    @Test
    fun `identical content and dimensions produce an identical key`() {
        val a = state(layer(), layer(imageUri = "file:///2.jpg"))
        val b = state(layer(), layer(imageUri = "file:///2.jpg"))
        assertEquals(key(a), key(b))
    }

    @Test
    fun `the key carries the composite scheme`() {
        assertTrue(key(state(layer())).startsWith(WallpaperCompositeKey.SCHEME))
    }

    @Test
    fun `every pixel-affecting layer field changes the key`() {
        val base = state(layer())
        val baseKey = key(base)
        // Each single-field mutation must move the key.
        assertNotEquals("imageUri", baseKey, key(state(layer(imageUri = "file:///other.jpg"))))
        assertNotEquals("scale", baseKey, key(state(layer(scale = 1.5f))))
        assertNotEquals("translateX", baseKey, key(state(layer(translateX = 10f))))
        assertNotEquals("translateY", baseKey, key(state(layer(translateY = 10f))))
        assertNotEquals("alpha", baseKey, key(state(layer(alpha = 0.5f))))
        assertNotEquals("blendModeName", baseKey, key(state(layer(blendModeName = "MULTIPLY"))))
        assertNotEquals("isVisible", baseKey, key(state(layer(isVisible = false))))
        assertNotEquals("captureSampleSize", baseKey, key(state(layer(captureSampleSize = 2))))
    }

    @Test
    fun `layer order is significant (z-order changes the composite)`() {
        val a = state(layer(imageUri = "file:///a.jpg"), layer(imageUri = "file:///b.jpg"))
        val b = state(layer(imageUri = "file:///b.jpg"), layer(imageUri = "file:///a.jpg"))
        assertNotEquals(key(a), key(b))
    }

    @Test
    fun `render dimensions are part of the key (rotate or fold must miss)`() {
        val s = state(layer())
        assertNotEquals("width", key(s, width = w), key(s, width = w + 1))
        assertNotEquals("height", key(s, height = h), key(s, height = h + 1))
    }

    @Test
    fun `non-pixel fields (id, label) do NOT change the key`() {
        val base = state(layer(id = "id-1", label = null))
        assertEquals("id", key(base), key(state(layer(id = "id-2", label = null))))
        assertEquals("label", key(base), key(state(layer(id = "id-1", label = "Top"))))
    }

    /**
     * Completeness canary (§2 "completeness contract, enforced"). If a `WallpaperLayerState`
     * field is added or removed, this fails — forcing the author to decide whether it affects
     * pixels and, if so, add it BOTH to [WallpaperCompositeKey.of] and to
     * [every pixel-affecting layer field changes the key] above. A silent new pixel field is
     * exactly the collision this whole design's correctness rests on preventing.
     */
    @Test
    fun `WallpaperLayerState schema is unchanged - review the composite key if this fails`() {
        val actual = WallpaperLayerState::class.java.declaredFields
            .filterNot { Modifier.isStatic(it.modifiers) }
            .map { it.name }
            .toSet()
        val expected = setOf(
            // In the key (pixel-affecting):
            "imageUri", "scale", "translateX", "translateY",
            "alpha", "blendModeName", "isVisible", "captureSampleSize",
            // NOT in the key (identity / UI only):
            "id", "label",
        )
        assertEquals(
            "WallpaperLayerState fields changed. If the new/removed field affects rendered " +
                "pixels, update WallpaperCompositeKey.of AND this test; else add it to the " +
                "non-pixel allow-list.",
            expected, actual,
        )
    }
}
