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
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
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

    /**
     * Cached system-bars + display-cutout insets in the parent's local
     * frame. Refreshed on every `setOnApplyWindowInsetsListener` call
     * and consulted by the drag / apply paths so the cluster never
     * lands behind the status-bar, nav-bar, or a cutout where touches
     * are eaten by the system.
     */
    private var insetLeft: Int = 0
    private var insetTop: Int = 0
    private var insetRight: Int = 0
    private var insetBottom: Int = 0

    /**
     * Last persisted fractions applied via [applyPosition] (or set by
     * a drag end). Kept so the on-layout-change listener can re-apply
     * after the parent's geometry shifts — without re-asking the
     * ViewModel — because `setX`/`setY` store a translation relative
     * to the live `left`/`top`, and those change on rotation, inset
     * dispatch, and fold-state transitions.
     */
    private var lastXFraction: Float? = null
    private var lastYFraction: Float? = null

    init {
        orientation = VERTICAL
        // No `setGravity` here on purpose: this LinearLayout is
        // `wrap_content`, so there is no extra space for gravity to
        // distribute. The "Save at the bottom, mini-FABs stacked
        // above" effect comes from the <merge> children order in
        // view_speed_dial_fab_cluster.xml plus the host's
        // `layout_gravity="bottom|end"` in fragment_home.xml — both
        // of which already do the right thing.
        LayoutInflater.from(context).inflate(R.layout.view_speed_dial_fab_cluster, this, true)

        fabOpenCommands = findViewById(R.id.fabOpenCommands)
        fabFitWidth = findViewById(R.id.fabFitWidth)
        fabOneToOne = findViewById(R.id.fabOneToOne)
        fabAddLayer = findViewById(R.id.fabAddLayer)
        fabCancel = findViewById(R.id.fabCancel)
        fabSave = findViewById(R.id.fabSave)

        // OnClickListener is the canonical accessibility entry point:
        // TalkBack and Switch Access dispatch via View.performClick(),
        // not via the OnTouchListener. The touch listener calls
        // performClick() in its tap branch so both paths converge here.
        fabSave.setOnClickListener { saveTapListener?.invoke() }
        installDragHandle(fabSave)

        // System-UI insets — refreshed by the platform on rotation,
        // IME-toggle, and folding-state changes. systemBars covers
        // status + navigation, displayCutout covers the camera notch /
        // pinhole. Returning the insets unchanged keeps siblings'
        // existing inset wiring intact (e.g. CommandsPanel).
        ViewCompat.setOnApplyWindowInsetsListener(this) { _, insets ->
            val bars = insets.getInsets(
                WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout()
            )
            insetLeft = bars.left
            insetTop = bars.top
            insetRight = bars.right
            insetBottom = bars.bottom
            // Insets changed mid-edit-session? Re-apply against the
            // new safe-area so the cluster doesn't sit behind a bar.
            applyPositionImmediate()
            insets
        }

        // Re-apply on every layout-pass where the cluster's slot moved.
        // `setX`/`setY` write a translation off the live `left`/`top`,
        // so a parent-size change would otherwise leave the cluster
        // displaced by exactly the delta. Comparing old vs. new corners
        // avoids redundant re-applies for layout passes that don't
        // actually relocate the view. Skipping mid-drag avoids the
        // (rare) flicker where a rotation-driven layout pass would
        // briefly snap the cluster back to the pre-drag fraction
        // before the next MOVE event re-positions it.
        addOnLayoutChangeListener { _, left, top, right, bottom, oldLeft, oldTop, oldRight, oldBottom ->
            if (dragHandler.isDragging) return@addOnLayoutChangeListener
            if (left != oldLeft || top != oldTop || right != oldRight || bottom != oldBottom) {
                applyPositionImmediate()
            }
        }
    }

    // ============================================================
    // PUBLIC API — listeners
    // ============================================================

    /** Set by the controller; invoked when Save is tapped without dragging. */
    fun setOnSaveClicked(listener: () -> Unit) {
        // The drag-aware tap path routes through View.performClick() —
        // see init for the OnClickListener wiring. Updating the field
        // is enough; both touch-driven taps and accessibility-driven
        // performClick()s read it.
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
        lastXFraction = xFraction
        lastYFraction = yFraction
        doOnLayout { applyPositionImmediate() }
    }

    /**
     * Apply the cached [lastXFraction] / [lastYFraction] using the
     * current parent geometry and insets. No-op if either is null,
     * the view hasn't laid out, or the parent isn't a ViewGroup.
     * Driven by [applyPosition], the inset listener, and the layout-
     * change listener — see init for the call sites.
     */
    private fun applyPositionImmediate() {
        val parent = parent as? ViewGroup ?: return
        val parentW = parent.width
        val parentH = parent.height
        if (parentW <= 0 || parentH <= 0) return
        val fx = lastXFraction ?: return
        val fy = lastYFraction ?: return
        x = FabPositionMath.centerFractionToTopLeftPx(
            centerFraction = fx,
            fabSize = width,
            parentSize = parentW,
            insetStart = insetLeft,
            insetEnd = insetRight,
        ).toFloat()
        y = FabPositionMath.centerFractionToTopLeftPx(
            centerFraction = fy,
            fabSize = height,
            parentSize = parentH,
            insetStart = insetTop,
            insetEnd = insetBottom,
        ).toFloat()
    }

    // ============================================================
    // INTERNAL — drag
    // ============================================================

    private var saveTapListener: (() -> Unit)? = null

    @SuppressLint("ClickableViewAccessibility") // Tap path routes through performClick() — see init.
    private fun installDragHandle(fab: FloatingActionButton) {
        fab.setOnTouchListener { v, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    dragHandler.onDown(event.rawX, event.rawY)
                    dragStartX = x
                    dragStartY = y
                    // Manual pressed-state for ripple feedback: we
                    // consume ACTION_DOWN here, so the FAB's own
                    // onTouchEvent never sees it and never sets the
                    // pressed state itself.
                    v.isPressed = true
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
                            insetStart = insetLeft,
                            insetEnd = insetRight,
                        )
                        y = FabPositionMath.clampTopLeft(
                            topLeftPx = dragStartY + delta.dy,
                            fabSize = height,
                            parentSize = parent.height,
                            insetStart = insetTop,
                            insetEnd = insetBottom,
                        )
                    }
                    // Past slop — this is a drag, not a tap. Drop the
                    // ripple so the user gets a clean drag feel.
                    if (v.isPressed) v.isPressed = false
                    true
                }
                MotionEvent.ACTION_UP -> {
                    v.isPressed = false
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
                                // Update the cache synchronously: the
                                // ViewModel round-trip is async, and a
                                // layout-pass arriving in the meantime
                                // would otherwise re-apply the pre-drag
                                // fraction.
                                lastXFraction = xFrac
                                lastYFraction = yFrac
                                onPositionChanged?.invoke(xFrac, yFrac)
                            }
                        }
                        FabDragHandler.EndState.Tap -> v.performClick()
                    }
                    true
                }
                MotionEvent.ACTION_CANCEL -> {
                    v.isPressed = false
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

}
