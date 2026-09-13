package com.github.reygnn.nyx_launcher.home

import com.github.reygnn.launcher.core.ComponentKey
import com.github.reygnn.nyx_launcher.home.model.CellPos
import com.github.reygnn.nyx_launcher.home.model.GridSpec
import com.github.reygnn.nyx_launcher.home.model.HomeItem
import com.github.reygnn.nyx_launcher.home.model.HomeLayout
import com.github.reygnn.nyx_launcher.home.model.ItemId
import com.github.reygnn.nyx_launcher.home.model.PlacedItem
import com.google.common.truth.Truth.assertThat
import org.junit.Test

/** [HomeLayout.renderedPageCount]: occupied pages + one empty landing page. */
class HomeRenderedPagesTest {

    private val grid = GridSpec(columns = 5, rows = 4)
    private fun app(id: String) = HomeItem.App(ItemId(id), ComponentKey(id, "$id.Main"))
    private fun at(page: Int) = PlacedItem(app("a$page"), CellPos(page, 0, 0))
    private fun layout(items: List<PlacedItem>, pages: Int) = HomeLayout(grid, pages, items, emptyList())

    @Test fun empty_grid_renders_one_page() {
        assertThat(layout(emptyList(), pages = 1).renderedPageCount()).isEqualTo(1)
    }

    @Test fun items_on_page_0_render_a_landing_page() {
        assertThat(layout(listOf(at(0)), pages = 1).renderedPageCount()).isEqualTo(2)
    }

    @Test fun landing_page_is_one_past_the_highest_occupied() {
        assertThat(layout(listOf(at(0), at(2)), pages = 3).renderedPageCount()).isEqualTo(4)
    }

    @Test fun stale_persisted_trailing_pages_do_not_inflate_the_count() {
        // Persisted pages say 6 but only page 0 is occupied → still just page 0 + landing.
        assertThat(layout(listOf(at(0)), pages = 6).renderedPageCount()).isEqualTo(2)
    }

    @Test fun the_landing_page_is_capped_at_max_pages() {
        // Highest occupied page is the last allowed one → no landing page beyond the cap,
        // so the count is MAX_PAGES (not highest + 2), keeping the dot indicator bounded.
        val max = HomeLayout.MAX_PAGES
        val full = (0 until max).map { at(it) }
        assertThat(layout(full, pages = max).renderedPageCount()).isEqualTo(max)
    }

    @Test fun the_page_just_below_the_cap_still_offers_its_landing_page() {
        // Occupied up to page MAX_PAGES - 2 → highest + 2 == MAX_PAGES, still offered.
        val max = HomeLayout.MAX_PAGES
        val items = (0 until max - 1).map { at(it) } // highest occupied = max - 2
        assertThat(layout(items, pages = max - 1).renderedPageCount()).isEqualTo(max)
    }
}
