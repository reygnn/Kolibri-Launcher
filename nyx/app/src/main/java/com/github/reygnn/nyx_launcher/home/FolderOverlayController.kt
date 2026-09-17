package com.github.reygnn.nyx_launcher.home

import android.view.View
import android.widget.EditText
import androidx.core.view.doOnLayout
import androidx.core.view.isVisible
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView

/**
 * Drives the shared folder overlay (`folder_overlay`) for BOTH home folders and drawer
 * folders (DRAWER_FOLDERS_SPEC §10). Container-neutral: it owns the show/hide, the title
 * field and the member grid; each caller supplies the member adapter (with its own
 * launch / drag behaviour) and an [open]-time `onClose` hook (e.g. commit a rename). The
 * overlay lives in the DragLayer ABOVE the drawer, so it shows over an open drawer too —
 * and a home member finger-drag still hands straight off to the DragController.
 */
class FolderOverlayController(
    private val overlay: View,
    private val titleField: EditText,
    private val members: RecyclerView,
    private val addAppsButton: View,
    private val columns: () -> Int,
) {
    private var onClose: (() -> Unit)? = null

    val isVisible: Boolean get() = overlay.isVisible

    /** The current (possibly edited) title text, for a caller's rename-on-close. */
    val title: String get() = titleField.text.toString()

    /**
     * Pre-fill the title field (e.g. auto-name a still-unnamed folder after the maker just
     * bulk-added). Committed like any manual edit by the caller's rename-on-close hook.
     */
    fun setTitle(value: String) = titleField.setText(value)

    /**
     * Show a folder: [initialTitle] in the title field ([titleEditable] toggles editing),
     * [memberAdapter] in the member grid. [onClose] runs exactly once when the overlay is
     * closed via [close]. [onAddApps], when non-null, reveals the "add apps by maker" button
     * and runs on tap (drawer folders only); null hides it (home folders).
     */
    fun open(
        initialTitle: String,
        titleEditable: Boolean,
        memberAdapter: RecyclerView.Adapter<*>,
        onAddApps: (() -> Unit)? = null,
        onClose: () -> Unit,
    ) {
        this.onClose = onClose
        titleField.setText(initialTitle)
        titleField.isEnabled = titleEditable
        members.layoutManager = GridLayoutManager(members.context, columns())
        members.adapter = memberAdapter
        addAppsButton.isVisible = onAddApps != null
        addAppsButton.setOnClickListener { onAddApps?.invoke() }
        overlay.isVisible = true
        capCardHeight()
    }

    /**
     * Cap the card at [CARD_MAX_HEIGHT_FRACTION] of the overlay height so a folder with
     * many members can't fill the screen — a scrim margin always stays tappable to
     * dismiss it. Only the scrollable member grid absorbs the cap; a small folder stays
     * wrap_content. Runs after layout (the overlay height is only known then); the card's
     * non-grid chrome (title + button + padding) is grid-content-invariant, so
     * `card - grid` yields it on any pass.
     */
    private fun capCardHeight() {
        val grid = members as? MaxHeightRecyclerView ?: return
        val card = members.parent as? View ?: return
        overlay.doOnLayout {
            val cap = (overlay.height * CARD_MAX_HEIGHT_FRACTION).toInt()
            if (cap <= 0) return@doOnLayout
            val chrome = (card.height - members.height).coerceAtLeast(0)
            grid.maxHeightPx = (cap - chrome).coerceAtLeast(0)
        }
    }

    /** Close (tap-outside / launch): run [onClose] (commit), then hide + detach the adapter. */
    fun close() {
        if (!overlay.isVisible) return
        onClose?.invoke()
        detach()
    }

    /** Hide WITHOUT running [onClose] — the caller already committed (e.g. a member drag-out). */
    fun dismiss() {
        if (!overlay.isVisible) return
        detach()
    }

    private fun detach() {
        onClose = null
        members.adapter = null
        // Clear the add-apps lambda too: for a drawer folder it captures the just-closed
        // folder's member adapter, so leaving it wired would defeat the adapter null-out
        // above (the button would keep the adapter reachable until the next open()).
        addAppsButton.setOnClickListener(null)
        addAppsButton.isVisible = false
        overlay.isVisible = false
    }

    private companion object {
        // Folder card fills at most 60% of the overlay height; the rest stays as a
        // tappable scrim so the folder is easy to dismiss.
        const val CARD_MAX_HEIGHT_FRACTION = 0.60f
    }
}
