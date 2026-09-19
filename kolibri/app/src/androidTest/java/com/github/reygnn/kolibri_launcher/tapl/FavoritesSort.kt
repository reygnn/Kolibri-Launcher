package com.github.reygnn.kolibri_launcher.tapl

import com.github.reygnn.kolibri_launcher.R
import com.github.reygnn.launcher.testing.BasePage
import com.github.reygnn.launcher.testing.dragRecyclerItem

/**
 * The favorites-reorder screen (`FavoritesSortFragment`, R.id.recyclerView).
 * Hosted directly in HiltTestActivity via the same `newInstance(favorites)`
 * factory Settings uses — the reorder gesture is what needs a device, not the
 * Settings navigation around it.
 *
 * The list persists through `FavoritesOrderRepository.saveOrder` after each
 * drag; tests assert against that repository rather than the view state.
 */
internal class FavoritesSort : BasePage() {
    override val anchorId: Int = R.id.recyclerView
    override val name: String = "FavoritesSort"

    init { assertOnPage() }

    /** Long-press-drags the row at [from] onto [to] (real touch stream). */
    fun dragItem(from: Int, to: Int) {
        view(R.id.recyclerView).perform(dragRecyclerItem(from, to))
    }
}
