package com.github.reygnn.nyx_launcher.home

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/** Pure JVM tests for [contextMenuAnchor]. */
class HomeContextMenuGeometryTest {

    private fun anchor(
        iconX: Int, iconY: Int,
        sourceWidth: Int = 100, sourceHeight: Int = 100,
        cardWidth: Int = 200, cardHeight: Int = 300,
        rootWidth: Int = 1000, rootHeight: Int = 2000,
        margin: Int = 12,
    ) = contextMenuAnchor(
        iconX, iconY, sourceWidth, sourceHeight,
        cardWidth, cardHeight, rootWidth, rootHeight, margin,
    )

    @Test fun sits_above_the_icon_when_there_is_room() {
        // iconY 800, card 300, margin 12 → above y = 800 - 300 - 12 = 488.
        // x centers card on icon: 400 + 100/2 - 200/2 = 350.
        assertThat(anchor(iconX = 400, iconY = 800)).isEqualTo(MenuAnchor(350, 488))
    }

    @Test fun flips_below_the_icon_when_no_room_above() {
        // iconY 10 → above (10 - 300 - 12 = -302) has no room, flip below: 10 + 100 + 12 = 122.
        assertThat(anchor(iconX = 400, iconY = 10).y).isEqualTo(122)
    }

    @Test fun clamps_x_to_the_left_margin() {
        // icon far left → centered x would be negative, clamp to margin.
        assertThat(anchor(iconX = 0, iconY = 800).x).isEqualTo(12)
    }

    @Test fun clamps_x_to_the_right_margin() {
        // icon far right → clamp to rootWidth - cardWidth - margin = 1000 - 200 - 12 = 788.
        assertThat(anchor(iconX = 980, iconY = 800).x).isEqualTo(788)
    }

    @Test fun card_as_wide_as_root_clamps_to_margin_without_crashing() {
        // maxX = 1000 - 1000 - 12 = -12, coerceAtLeast(margin) → 12; x coerceIn(12, 12) = 12.
        assertThat(anchor(iconX = 400, iconY = 800, cardWidth = 1000).x).isEqualTo(12)
    }

    @Test fun card_as_tall_as_root_clamps_to_margin_without_crashing() {
        assertThat(anchor(iconX = 400, iconY = 800, cardHeight = 2000).y).isEqualTo(12)
    }
}
