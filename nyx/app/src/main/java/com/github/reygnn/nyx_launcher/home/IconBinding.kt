package com.github.reygnn.nyx_launcher.home

import android.graphics.Bitmap
import android.view.View
import android.widget.ImageView
import com.github.reygnn.launcher.core.ComponentKey
import com.github.reygnn.nyx_launcher.data.icon.FolderIconRenderer
import com.github.reygnn.nyx_launcher.data.icon.IconLoader
import com.github.reygnn.nyx_launcher.home.model.IconRef
import com.github.reygnn.nyx_launcher.home.model.ItemId
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/**
 * Token-gated async icon load (ICL-INV-9), shared by the home/dock/drawer/folder
 * adapters (AUDIT-1 A1-09). Clears the view, then asynchronously sets the bitmap
 * from [produce] — but only if the holder hasn't been rebound or recycled since
 * (its token still equals [tokenAtBind]), so a fast scroll never flashes the
 * wrong icon. A failed load leaves the view cleared. The caller bumps the
 * holder's token on bind + recycle; [currentToken] reads the live value.
 */
fun ImageView.loadIconGated(
    scope: CoroutineScope,
    tokenAtBind: Int,
    currentToken: () -> Int,
    produce: suspend () -> Bitmap?,
) {
    setImageDrawable(null)
    scope.launch {
        val bitmap = runCatching { produce() }.getOrNull() ?: return@launch
        if (currentToken() == tokenAtBind) setImageBitmap(bitmap)
    }
}

/**
 * Binds a launchable [HomeCell] — an [HomeCell.App] or [HomeCell.Folder] — to a
 * holder's [itemView] + [icon]: tap/long-press wiring plus the token-gated icon
 * ([loadIconGated]). The grid and dock adapters had this App/Folder branch
 * duplicated verbatim (AUDIT-1 A1-06); both now route through here.
 *
 * [HomeCell.Empty] is a no-op — the dock never has empties, and the grid clears
 * its own listeners for empty cells before calling this.
 */
/**
 * RecyclerView change payload meaning "only the notification dot changed" — an adapter
 * rebinding with this must update the dot View only, never reload the icon (which would
 * blank + re-decode it, causing visible flicker on every notification change).
 */
val NOTIFICATION_DOT_PAYLOAD = Any()

/** True if a payload list is exactly a notification-dot-only refresh. */
fun List<Any>.isDotOnlyPayload(): Boolean = isNotEmpty() && all { it === NOTIFICATION_DOT_PAYLOAD }

/**
 * Payload marking an icon-style change: the cell DATA is unchanged (so the grid's
 * positional DiffUtil would rebind nothing), but every icon must re-decode under the
 * new [com.github.reygnn.launcher.core.IconStyle]. Carried per-page by the pager so a
 * style switch repaints the grid — the dock ([DockAdapter]) and drawer already repaint
 * via their own full rebind.
 */
val ICON_STYLE_PAYLOAD = Any()

/**
 * True if a payload list CONTAINS an icon-style refresh (possibly coalesced with a dot
 * payload). The pager treats this as a full icon re-decode, which also covers dots — so a
 * mixed [NOTIFICATION_DOT_PAYLOAD, ICON_STYLE_PAYLOAD] batch isn't dropped on the floor.
 */
fun List<Any>.hasIconStylePayload(): Boolean = any { it === ICON_STYLE_PAYLOAD }

/**
 * Whether [this] cell should show a notification dot given [dotPackages] (the set of
 * packages with a dot-worthy notification): an app matches its own package, a folder
 * matches if any member does. Pure — unit-tested (see HomeCellDotTest).
 */
fun HomeCell.hasNotificationDot(dotPackages: Set<String>): Boolean = when (this) {
    HomeCell.Empty -> false
    is HomeCell.App -> key.packageName in dotPackages
    is HomeCell.Folder -> members.any { it.packageName in dotPackages }
}

/** Alpha for a "missing" tile (app no longer installed) — a greyed broken-shortcut look.
 *  Shared by the grid/dock (here) and the folder-member adapter. */
internal const val MISSING_ICON_ALPHA = 0.35f

/**
 * Reset a recycled icon view: clear the drawable and undo any greyed missing-tile alpha
 * ([MISSING_ICON_ALPHA]) so a reused holder never inherits it. Shared by the grid/dock/
 * folder-member adapters' onViewRecycled (the bind-token bump stays per-holder).
 */
internal fun ImageView.resetForRecycle() {
    setImageDrawable(null)
    alpha = 1f
}

fun bindLaunchableCell(
    itemView: View,
    icon: ImageView,
    dot: View,
    cell: HomeCell,
    dotPackages: Set<String>,
    scope: CoroutineScope,
    tokenAtBind: Int,
    currentToken: () -> Int,
    iconLoader: IconLoader,
    folderRenderer: FolderIconRenderer,
    iconSizePx: Int,
    onLaunch: (ComponentKey) -> Unit,
    onOpenFolder: (id: ItemId) -> Unit,
    onIconLongPress: (view: View, id: ItemId) -> Unit,
    onMissingApp: (id: ItemId, key: ComponentKey) -> Unit = { _, _ -> },
) {
    dot.visibility = if (cell.hasNotificationDot(dotPackages)) View.VISIBLE else View.GONE
    when (cell) {
        HomeCell.Empty -> Unit
        is HomeCell.App -> if (cell.missing) {
            // Dead reference (Windows-shortcut model): a launch is impossible, so a tap
            // offers to remove it; long-press still arms the drag/context menu (reposition
            // or remove). Greyed placeholder icon instead of the (unavailable) app icon.
            itemView.setOnClickListener { onMissingApp(cell.id, cell.key) }
            itemView.setOnLongClickListener { onIconLongPress(itemView, cell.id); true }
            icon.alpha = MISSING_ICON_ALPHA
            icon.setImageResource(android.R.drawable.sym_def_app_icon)
        } else {
            itemView.setOnClickListener { onLaunch(cell.key) }
            itemView.setOnLongClickListener { onIconLongPress(itemView, cell.id); true }
            icon.alpha = 1f
            icon.loadIconGated(scope, tokenAtBind, currentToken) {
                iconLoader.bitmap(IconRef.System(cell.key), iconSizePx)
            }
        }
        is HomeCell.Folder -> {
            itemView.setOnClickListener { onOpenFolder(cell.id) }
            itemView.setOnLongClickListener { onIconLongPress(itemView, cell.id); true }
            if (cell.presentMembers.isEmpty()) {
                // Every member is uninstalled: there is nothing to composite (an empty render
                // is just a faint background box that reads as broken). Show the same greyed
                // missing-icon look as a dead app tile so it's a recognizable "dead" affordance;
                // the folder still opens (its members are removable inside the overlay).
                icon.alpha = MISSING_ICON_ALPHA
                icon.setImageResource(android.R.drawable.sym_def_app_icon)
            } else {
                // Reset alpha in case this holder was recycled from a greyed missing tile.
                icon.alpha = 1f
                icon.loadIconGated(scope, tokenAtBind, currentToken) {
                    // presentMembers (installed-only), NOT members: an uninstalled member must
                    // drop out of the 2×2 composite so the icons compact, instead of leaving a
                    // blank quadrant (FolderIconRenderer can't decode the gone app). presentMembers
                    // is also what drives the DiffUtil re-render on un/reinstall (see [HomeCell]).
                    folderRenderer.render(cell.presentMembers, iconSizePx)
                }
            }
        }
    }
}
