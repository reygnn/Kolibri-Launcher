package com.github.reygnn.nyx_launcher.home.model

/**
 * Position-independent, stable identity of a placed thing (app or folder).
 *
 * Re-sorting mutates a [PlacedItem]'s [CellPos], never its [ItemId]
 * (IHM-INV-4). An [ItemId] appears at most once across items ∪ dock.
 */
@JvmInline
value class ItemId(val raw: String)

/** A thing that can sit on the home grid or in the dock. */
sealed interface HomeItem {
    val id: ItemId

    /** A single launchable app. */
    data class App(override val id: ItemId, val key: ComponentKey) : HomeItem

    /**
     * A folder of apps, [members] in display order.
     *
     * == INVARIANT ==
     * A folder ALWAYS has >= 2 members: it is born with exactly two
     * (`FolderCreated`, MOVE_ITEM_SPEC §3) and auto-dissolves the moment a removal or
     * prune drops it to one (REMOVE_FROM_FOLDER_SPEC RFF-INV-1/-2). A one-member
     * folder never persists between operations. This is enforced by the
     * transitions, not by this type.
     *
     * A blank [title] means "show the localized default": the label is UI, the
     * domain stays string- / `@StringRes`-free (MOVE_ITEM_SPEC MIU-INV-6). The
     * folder icon is derived from [members] and never stored (IHM-INV-2).
     *
     * Field order follows ICON_HOME_MODEL_SPEC §2.3.
     */
    data class Folder(
        override val id: ItemId,
        val title: String,
        val members: List<ComponentKey>,
    ) : HomeItem
}

/** A [HomeItem] pinned to a grid cell with a span. */
data class PlacedItem(
    val item: HomeItem,
    val pos: CellPos,
    val span: Span = Span(),
)

/**
 * The whole home screen: a paged, 2D-positioned grid plus a seatless dock.
 *
 * This replaces the flat favourites *list* of the text launchers with a real
 * positioned graph (ICON_HOME_MODEL_SPEC §0.3). The structural invariants —
 * no collision / on-grid (IHM-INV-3), unique stable ids (IHM-INV-4), and app
 * uniqueness across `items` ∪ all folder members ∪ `dock` (IHM-INV-7) — are
 * upheld by the use-case transitions (`MIU-*`, `RFF-*`, `RHL-*`, `HEU-*`), NOT
 * by this datatype. A violation constructed here is a programmer error, not a
 * user outcome (CLAUDE.md rule 21).
 *
 * Persisted as one versioned JSON blob in DataStore; the `@Serializable` DTOs
 * and mappers live in `:data`, so these classes stay annotation-free
 * (ICON_HOME_MODEL_SPEC §7-E1, CLAUDE.md rule 22).
 */
data class HomeLayout(
    val grid: GridSpec,
    val pages: Int,
    val items: List<PlacedItem>,
    val dock: List<HomeItem>,
)
