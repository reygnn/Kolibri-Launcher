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
 * Whether [this] cell should show a notification dot given [dotPackages] (the set of
 * packages with a dot-worthy notification): an app matches its own package, a folder
 * matches if any member does. Pure — unit-tested (see HomeCellDotTest).
 */
fun HomeCell.hasNotificationDot(dotPackages: Set<String>): Boolean = when (this) {
    HomeCell.Empty -> false
    is HomeCell.App -> key.packageName in dotPackages
    is HomeCell.Folder -> members.any { it.packageName in dotPackages }
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
) {
    dot.visibility = if (cell.hasNotificationDot(dotPackages)) View.VISIBLE else View.GONE
    when (cell) {
        HomeCell.Empty -> Unit
        is HomeCell.App -> {
            itemView.setOnClickListener { onLaunch(cell.key) }
            itemView.setOnLongClickListener { onIconLongPress(itemView, cell.id); true }
            icon.loadIconGated(scope, tokenAtBind, currentToken) {
                iconLoader.bitmap(IconRef.System(cell.key), iconSizePx)
            }
        }
        is HomeCell.Folder -> {
            itemView.setOnClickListener { onOpenFolder(cell.id) }
            itemView.setOnLongClickListener { onIconLongPress(itemView, cell.id); true }
            icon.loadIconGated(scope, tokenAtBind, currentToken) {
                folderRenderer.render(cell.members, iconSizePx)
            }
        }
    }
}
