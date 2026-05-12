package com.github.reygnn.kolibri_launcher.ui.home.wallpaperfab

import android.annotation.SuppressLint
import android.content.Context
import android.util.AttributeSet
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.ViewGroup
import android.widget.LinearLayout
import androidx.core.view.doOnLayout
import com.github.reygnn.kolibri_launcher.R
import com.google.android.material.floatingactionbutton.FloatingActionButton

/**
 * Vertical cluster of the wallpaper-edit FABs. From top to bottom:
 * `Overflow ☰`, `Fit-Width`, `1:1`, `Add Layer`, `Cancel`, `Save`.
 * Save is the larger primary FAB and doubles as the cluster's drag
 * handle: touching it past `touchSlop` translates the entire cluster
 * around its parent.
 *
 * The view is Views-only (no Compose) and Android-runtime-thin —
 * touch state lives in [FabDragHandler] and fraction/pixel math in
 * [FabPositionMath], both of which are pure and unit-tested on the
 * JVM.
 *
 * Public API (used by `WallpaperEditController`):
 *   - `setOnSaveClicked / setOnCancelClicked / …` — per-action listeners
 *   - `setMiniFabEnabled` — visual disabled state via alpha + isEnabled
 *   - `applyPosition` — sets the cluster's translation from a fraction
 *   - `onPositionChanged` — drag-end callback for persistence
 */
class SpeedDialFabCluster @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
) : LinearLayout(context, attrs, defStyleAttr) {

    private val fabSave: FloatingActionButton
    private val fabCancel: FloatingActionButton
    private val fabAddLayer: FloatingActionButton
    private val fabOneToOne: FloatingActionButton
    private val fabFitWidth: FloatingActionButton
    private val fabOpenCommands: FloatingActionButton

    private val dragHandler =
        FabDragHandler(touchSlopPx = ViewConfiguration.get(context).scaledTouchSlop)

    /**
     * Cluster top-left at the moment a drag started. Used as a stable
     * baseline so MOVE events apply a cumulative delta from the down
     * point — using the live `x`/`y` instead would compound rounding
     * errors and feel laggy on fast drags.
     */
    private var dragStartX: Float = 0f
    private var dragStartY: Float = 0f

    /**
     * Reported to the consumer after every drag-end so the position
     * can be persisted. Fractions are clamped to `[0f, 1f]` already.
     */
    var onPositionChanged: ((xFraction: Float, yFraction: Float) -> Unit)? = null

    init {
        orientation = VERTICAL
        // Bottom-aligned children: the column grows upwards from the
        // last child (the main FAB) so adding/removing mini-FABs would
        // stack on top of Save rather than pushing it around.
        gravity = android.view.Gravity.BOTTOM or android.view.Gravity.END

        LayoutInflater.from(context).inflate(R.layout.view_speed_dial_fab_cluster, this, true)

        fabOpenCommands = findViewById(R.id.fabOpenCommands)
        fabFitWidth = findViewById(R.id.fabFitWidth)
        fabOneToOne = findViewById(R.id.fabOneToOne)
        fabAddLayer = findViewById(R.id.fabAddLayer)
        fabCancel = findViewById(R.id.fabCancel)
        fabSave = findViewById(R.id.fabSave)

        installDragHandle(fabSave)
    }

    // ============================================================
    // PUBLIC API — listeners
    // ============================================================

    /** Set by the controller; invoked when Save is tapped without dragging. */
    fun setOnSaveClicked(listener: () -> Unit) {
        // Save uses the drag-aware path — see installDragHandle below.
        // The dedicated field lives there so MOVE events can decide
        // whether to suppress the tap.
        saveTapListener = listener
    }

    fun setOnCancelClicked(listener: () -> Unit) {
        fabCancel.setOnClickListener { listener() }
    }

    fun setOnAddLayerClicked(listener: () -> Unit) {
        fabAddLayer.setOnClickListener { listener() }
    }

    fun setOnOneToOneClicked(listener: () -> Unit) {
        fabOneToOne.setOnClickListener { listener() }
    }

    fun setOnFitWidthClicked(listener: () -> Unit) {
        fabFitWidth.setOnClickListener { listener() }
    }

    fun setOnOpenCommandsClicked(listener: () -> Unit) {
        fabOpenCommands.setOnClickListener { listener() }
    }

    /**
     * Toggles a mini-FAB's enabled state with a half-transparent
     * appearance for the disabled case. Material's `isEnabled = false`
     * already greys out the FAB; the explicit alpha is belt-and-
     * suspenders and matches what the legacy toolbar did for the
     * layer-reorder buttons.
     */
    fun setMiniFabEnabled(id: MiniFab, enabled: Boolean) {
        val fab = fabFor(id)
        fab.isEnabled = enabled
        fab.alpha = if (enabled) 1f else DISABLED_ALPHA
    }

    fun setMiniFabVisible(id: MiniFab, visible: Boolean) {
        fabFor(id).visibility = if (visible) View.VISIBLE else View.GONE
    }

    // ============================================================
    // PUBLIC API — position
    // ============================================================

    /**
     * Applies a persisted [xFraction] / [yFraction] (center of cluster
     * relative to the parent's measured size) to the cluster's `x` /
     * `y`. Defers until the cluster has been measured at least once
     * so `width` / `height` are non-zero.
     */
    fun applyPosition(xFraction: Float, yFraction: Float) {
        doOnLayout {
            val parent = parent as? ViewGroup ?: return@doOnLayout
            val parentW = parent.width
            val parentH = parent.height
            if (parentW <= 0 || parentH <= 0) return@doOnLayout
            x = FabPositionMath.centerFractionToTopLeftPx(xFraction, width, parentW).toFloat()
            y = FabPositionMath.centerFractionToTopLeftPx(yFraction, height, parentH).toFloat()
        }
    }

    // ============================================================
    // INTERNAL — drag
    // ============================================================

    private var saveTapListener: (() -> Unit)? = null

    @SuppressLint("ClickableViewAccessibility") // The FAB is still click-accessible via setOnSaveClicked.
    private fun installDragHandle(fab: FloatingActionButton) {
        fab.setOnTouchListener { _, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    dragHandler.onDown(event.rawX, event.rawY)
                    dragStartX = x
                    dragStartY = y
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val delta = dragHandler.onMove(event.rawX, event.rawY) ?: return@setOnTouchListener true
                    val parent = parent as? ViewGroup
                    if (parent != null) {
                        x = FabPositionMath.clampTopLeft(
                            topLeftPx = dragStartX + delta.dx,
                            fabSize = width,
                            parentSize = parent.width,
                        )
                        y = FabPositionMath.clampTopLeft(
                            topLeftPx = dragStartY + delta.dy,
                            fabSize = height,
                            parentSize = parent.height,
                        )
                    }
                    true
                }
                MotionEvent.ACTION_UP -> {
                    when (dragHandler.onUp()) {
                        FabDragHandler.EndState.Drag -> {
                            val parent = parent as? ViewGroup
                            if (parent != null) {
                                val xFrac = FabPositionMath.topLeftPxToCenterFraction(
                                    topLeftPx = x,
                                    fabSize = width,
                                    parentSize = parent.width,
                                )
                                val yFrac = FabPositionMath.topLeftPxToCenterFraction(
                                    topLeftPx = y,
                                    fabSize = height,
                                    parentSize = parent.height,
                                )
                                onPositionChanged?.invoke(xFrac, yFrac)
                            }
                        }
                        FabDragHandler.EndState.Tap -> saveTapListener?.invoke()
                    }
                    true
                }
                MotionEvent.ACTION_CANCEL -> {
                    dragHandler.onUp()
                    true
                }
                else -> false
            }
        }
    }

    private fun fabFor(id: MiniFab): FloatingActionButton = when (id) {
        MiniFab.Cancel -> fabCancel
        MiniFab.AddLayer -> fabAddLayer
        MiniFab.OneToOne -> fabOneToOne
        MiniFab.FitWidth -> fabFitWidth
        MiniFab.OpenCommands -> fabOpenCommands
    }

    /** Identifier for the per-mini-FAB setters. */
    enum class MiniFab { Cancel, AddLayer, OneToOne, FitWidth, OpenCommands }

    private companion object {
        const val DISABLED_ALPHA = 0.38f
    }
}
