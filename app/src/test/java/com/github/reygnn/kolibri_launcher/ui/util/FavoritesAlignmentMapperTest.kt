package com.github.reygnn.kolibri_launcher.ui.util

import android.view.Gravity
import com.github.reygnn.kolibri_launcher.domain.model.FavoritesAlignment
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Pins the per-value assignment in [toHorizontalGravity]. The `when` is
 * compiler-total (no `else`, so a NEW enum value can't be forgotten), but a
 * START<->END swap — or CENTER picking up a stray vertical bit — would compile
 * and silently misalign favorites in HomeFavoritesAdapter / AppDrawerAdapter.
 * Robolectric because `android.view.Gravity` is an Android-SDK type.
 */
@RunWith(RobolectricTestRunner::class)
class FavoritesAlignmentMapperTest {

    @Test
    fun `each alignment maps to its exact horizontal gravity`() {
        assertEquals(Gravity.START, FavoritesAlignment.START.toHorizontalGravity())
        assertEquals(Gravity.CENTER_HORIZONTAL, FavoritesAlignment.CENTER.toHorizontalGravity())
        assertEquals(Gravity.END, FavoritesAlignment.END.toHorizontalGravity())
    }
}
