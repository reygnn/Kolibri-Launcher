package com.github.reygnn.kolibri_launcher.ui.util

import android.view.Gravity
import com.github.reygnn.kolibri_launcher.domain.model.FavoritesAlignment
import com.google.common.truth.Truth.assertThat
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
        assertThat(FavoritesAlignment.START.toHorizontalGravity()).isEqualTo(Gravity.START)
        assertThat(FavoritesAlignment.CENTER.toHorizontalGravity()).isEqualTo(Gravity.CENTER_HORIZONTAL)
        assertThat(FavoritesAlignment.END.toHorizontalGravity()).isEqualTo(Gravity.END)
    }
}
