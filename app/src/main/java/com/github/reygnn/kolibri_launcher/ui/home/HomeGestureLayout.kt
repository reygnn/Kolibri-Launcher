package com.github.reygnn.kolibri_launcher.ui.home

import android.content.Context
import android.util.AttributeSet
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.ViewGroup
import androidx.constraintlayout.widget.ConstraintLayout
import com.github.reygnn.kolibri_launcher.ui.util.GestureThresholds
import com.github.reygnn.kolibri_launcher.ui.util.SwipeGestureAnalyzer

/**
 * Container that detects the five home-screen gestures (four
 * directional swipes, double-tap, long-press) anywhere within its
 * children and forwards them to per-gesture nullable callbacks.
 *
 * Same architectural pattern as [com.github.reygnn.kolibri_launcher.ui.appdrawer.SwipeDownDismissLayout]
 * (the proven AppDrawer variant). Directional swipes go through
 * [dispatchTouchEvent] because nested scrollable children — here a
 * [android.widget.ScrollView] for the favorites list — call
 * `requestDisallowInterceptTouchEvent(true)` mid-gesture, which
 * silently disables `onInterceptTouchEvent` and `OnTouchListener` on
 * the parent. `dispatchTouchEvent` is the only entry point that
 * fires unconditionally on the parent. Tap-based gestures (double-tap
 * and long-press) come from an embedded [GestureDetector] that runs
 * in parallel — taps stay below `touchSlop`, so scroll never claims
 * them and the GestureDetector path works.
 *
 * VELOCITY-BASED, NOT SCROLL-POSITION-BASED:
 *
 * The detector triggers on velocity, not on whether the list can
 * scroll. Same uniform behavior at top / middle / bottom: slow drags
 * scroll the favorites list, fast flicks always fire the gesture.
 *
 * GATING VIA NULLABLE CALLBACKS:
 *
 * Each callback is null by default. The host fragment wires the
 * gestures it cares about and can null individual callbacks at
 * runtime. The wallpaper-edit-mode case is the motivating example:
 * the four swipe callbacks plus [onDoubleTap] are nulled out while
 * the mode is active, but [onLongPress] stays wired (it's how the
 * user exits the mode). A swipe-result that maps to a null callback
 * is treated as IGNORED — the analyzer fires, the wrapper just
 * doesn't dispatch.
 */
class HomeGestureLayout @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
) : ConstraintLayout(context, attrs, defStyleAttr) {

    // ===========================================
    // PUBLIC API — per-gesture nullable callbacks
    // ===========================================

    var onSwipeUp: (() -> Unit)? = null
    var onSwipeDown: (() -> Unit)? = null
    var onSwipeLeft: (() -> Unit)? = null
    var onSwipeRight: (() -> Unit)? = null
    var onDoubleTap: (() -> Unit)? = null
    var onLongPress: (() -> Unit)? = null

    // ===========================================
    // INTERNAL ANALYZER
    // ===========================================

    /**
     * Reuses [SwipeGestureAnalyzer] so the axis-dominance + threshold
     * logic stays one source of truth across call sites. Velocity is
     * fed in **px/ms** — the unit naturally produced by `MotionEvent`
     * delta-over-elapsed-time computation. The analyzer is
     * unit-agnostic; the only requirement is that the velocity input
     * and [GestureThresholds.VELOCITY_PX_PER_MS] use the same unit.
     *
     * Calibration comes from [GestureThresholds] — see that object's
     * KDoc for the rationale; both this wrapper and
     * [com.github.reygnn.kolibri_launcher.ui.appdrawer.SwipeDownDismissLayout]
     * read from there.
     */
    private val analyzer = SwipeGestureAnalyzer(
        distanceThreshold = (
            ViewConfiguration.get(context).scaledTouchSlop *
                GestureThresholds.TOUCH_SLOP_DISTANCE_MULTIPLIER
            ).toFloat(),
        velocityThreshold = GestureThresholds.VELOCITY_PX_PER_MS,
        dominanceFactor = GestureThresholds.DOMINANCE_FACTOR,
    )

    // ===========================================
    // GESTURE STATE (per-touch)
    // ===========================================

    private var downX = 0f
    private var downY = 0f
    private var downTime = 0L
    private var triggered = false

    /**
     * Whether the touch at the current gesture's ACTION_DOWN landed
     * on (or under) a descendant view that runs its own touch
     * pipeline — either `isLongClickable = true` (favorite button →
     * app-context-menu) or `hasOnClickListeners() = true` (clock /
     * date / battery TextViews → double-click-to-launch). Used to
     * suppress the wrapper's own [tapDetector] there; otherwise both
     * detectors fire in parallel and the user sees the wrapper's
     * action (lock / customization-options dialog) layered on top of
     * the child's intended action.
     *
     * Computed via a manual hit-test in [hasOwnTouchPipelineDescendantAt],
     * NOT from `super.dispatchTouchEvent`'s consumed signal: the
     * latter is true even when the ScrollView claims DOWN itself
     * (its `onTouchEvent` always returns true when it has children),
     * which would suppress the wrapper's tap detector for empty-space
     * touches inside the favorites scroll area too — exactly where
     * the user expects the wrapper's long-press to fire.
     */
    private var childClaimedDown = false

    // ===========================================
    // EMBEDDED TAP DETECTOR (double-tap + long-press)
    // ===========================================

    private val tapDetector = GestureDetector(
        context,
        object : GestureDetector.SimpleOnGestureListener() {
            override fun onDown(e: MotionEvent): Boolean = true

            override fun onDoubleTap(e: MotionEvent): Boolean {
                onDoubleTap?.invoke()
                return true
            }

            override fun onLongPress(e: MotionEvent) {
                onLongPress?.invoke()
            }
        },
    )

    // ===========================================
    // TOUCH DISPATCH
    // ===========================================

    /**
     * Touch interception for the five gestures this class detects.
     *
     * Contract — three rules a future maintainer must keep
     * (transcribed verbatim from
     * [com.github.reygnn.kolibri_launcher.ui.appdrawer.SwipeDownDismissLayout]
     * because the same constraints apply):
     *
     *  1. **Read-only inspection until trigger.** ACTION_DOWN
     *     snapshots start coordinates and time; ACTION_MOVE evaluates
     *     the gesture against the analyzer's thresholds (distance,
     *     velocity, dominance) but does NOT consume the event yet.
     *     Children (the ScrollView) receive every event normally via
     *     `super.dispatchTouchEvent` and can scroll, click, long-press
     *     as usual. This is what makes "slow drag still scrolls the
     *     list" work — the parent stays out of the way until the
     *     gesture is unmistakable.
     *
     *  2. **One-shot trigger.** Once the analyzer returns a non-IGNORED
     *     result with a non-null callback, [triggered] flips to true,
     *     ACTION_CANCEL is sent down to children (so the ScrollView
     *     stops mid-scroll instead of keeping its fling), the callback
     *     fires, and every remaining event in the gesture is consumed.
     *     The flag resets only on the next ACTION_DOWN. Without the
     *     one-shot guard, a held finger past threshold would re-fire
     *     every frame.
     *
     *  3. **Never replace this with `onInterceptTouchEvent` or an
     *     `OnTouchListener`.** ScrollView calls
     *     `requestDisallowInterceptTouchEvent(true)` as soon as it
     *     claims the gesture (past touchSlop in the vertical axis),
     *     which silently disables both. `dispatchTouchEvent` is the
     *     only entry point that fires unconditionally on the parent.
     *     The "obvious" simplification is the bug.
     *
     * The embedded [tapDetector] is fed the event unconditionally at
     * the top of the method. It's read-only and reentrancy-safe;
     * feeding it after a directional trigger has already fired is
     * harmless (the upcoming ACTION_CANCEL also flows through it via
     * [cancelChildGesture] and aborts any in-progress tap tracking
     * cleanly). The mutual exclusion between fast swipes and
     * long-press is temporal, not contractual: a fast swipe completes
     * well before the ~500 ms long-press timer fires; a held finger
     * never produces enough velocity to trip the swipe analyzer.
     */
    override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX = ev.x
                downY = ev.y
                downTime = ev.eventTime
                triggered = false
                childClaimedDown = hasOwnTouchPipelineDescendantAt(ev.x, ev.y)
            }

            MotionEvent.ACTION_MOVE -> if (!triggered) {
                val dx = ev.x - downX
                val dy = ev.y - downY
                val dt = (ev.eventTime - downTime).coerceAtLeast(1L)
                val vx = dx / dt
                val vy = dy / dt

                val callback: (() -> Unit)? =
                    when (analyzer.analyze(dx, dy, vx, vy)) {
                        SwipeGestureAnalyzer.SwipeResult.UP -> onSwipeUp
                        SwipeGestureAnalyzer.SwipeResult.DOWN -> onSwipeDown
                        SwipeGestureAnalyzer.SwipeResult.TOWARDS_LEFT -> onSwipeLeft
                        SwipeGestureAnalyzer.SwipeResult.TOWARDS_RIGHT -> onSwipeRight
                        SwipeGestureAnalyzer.SwipeResult.IGNORED -> null
                    }

                if (callback != null) {
                    triggered = true
                    cancelChildGesture(ev)
                    callback.invoke()
                    return true
                }
            }
        }

        // Always run the children's dispatch so they get every event
        // (clicks on favorites, ScrollView's own drag handling, etc.).
        // The return value is what the *parent* (the wallpaperContainer
        // FrameLayout) sees; once we own the gesture the children's
        // result is irrelevant to dispatch routing.
        val consumedBySuper = super.dispatchTouchEvent(ev)

        // The tap detector only sees events when no descendant with
        // its own touch pipeline lives at the touch position. Two
        // pipelines are recognised: long-clickable (favorite button
        // → app-context-menu) and clickable (clock / date / battery
        // TextViews → double-click-to-launch). Running the wrapper's
        // tapDetector in parallel with either would double-fire (the
        // child's action plus the wrapper's lock / customization
        // dialog). For every other surface — empty space beside a
        // short favorite, the empty area below the favorites list,
        // the wallpaper background — there is no own-pipeline
        // descendant at the touch point and the wrapper's tap
        // detector is the sole long-press / double-tap source.
        if (!childClaimedDown) {
            tapDetector.onTouchEvent(ev)
        }

        // ACTION_DOWN claim — even if no descendant returned true,
        // we MUST tell the grandparent "yes, this branch wants the
        // gesture". Without that claim, the FrameLayout treats this
        // branch as rejecting the touch and routes every later
        // ACTION_MOVE / ACTION_UP to a different child or swallows
        // them — `dispatchTouchEvent` then never fires again for
        // the rest of the gesture and our analyzer cannot decide.
        // The empty space below a short favorites list has no
        // clickable descendant — without this claim, the wrapper
        // would only ever see one event per gesture there.
        // SwipeDownDismissLayout in the AppDrawer does NOT need the
        // same workaround because its child is a RecyclerView that
        // claims DOWN unconditionally; that's an accident of its
        // contents, not a property of the dispatchTouchEvent pattern.
        return when {
            triggered -> true
            ev.actionMasked == MotionEvent.ACTION_DOWN -> true
            else -> consumedBySuper
        }
    }

    /**
     * Walks the visible view tree from this layout downward, mirroring
     * `ViewGroup.dispatchTouchEvent`'s hit-testing for axis-aligned,
     * untransformed children, and returns `true` if any view on the
     * path to the deepest descendant at (`rootX`, `rootY`) runs its
     * own touch pipeline — either `isLongClickable = true` (favorite
     * button → app-context-menu) or `hasOnClickListeners() = true`
     * (clock / date / battery TextViews → double-click-to-launch).
     *
     * Why a manual walk and not `super.dispatchTouchEvent`'s consumed
     * signal: ScrollView's `onTouchEvent` always returns true on
     * ACTION_DOWN when it has children (its custom logic claims the
     * gesture for scroll-handling), so the consumed signal is true
     * even when the touch landed in empty scroll-space with no
     * own-pipeline descendant. We need the finer-grained answer:
     * "is there a view here that will fire its OWN long-press or
     * click?". If yes, suppress ours; if no, fire ours.
     *
     * Accuracy gap: this hit-test does NOT account for runtime
     * matrix transforms (rotation, scale, translation via
     * `View.setRotation` / `setScale*` / `setTranslation*`). The
     * descendants of `HomeGestureLayout` in this app are
     * untransformed — if you add an animated/rotated view inside,
     * audit this method.
     */
    private fun hasOwnTouchPipelineDescendantAt(rootX: Float, rootY: Float): Boolean {
        var view: View = this
        var x = rootX
        var y = rootY
        while (view is ViewGroup) {
            if (view !== this && view.hasOwnTouchPipeline()) return true
            var hitChild: View? = null
            var hitX = 0f
            var hitY = 0f
            for (i in view.childCount - 1 downTo 0) {
                val child = view.getChildAt(i)
                if (child.visibility != View.VISIBLE) continue
                val cx = x - child.left + view.scrollX
                val cy = y - child.top + view.scrollY
                if (cx >= 0f && cx < child.width.toFloat() &&
                    cy >= 0f && cy < child.height.toFloat()) {
                    hitChild = child
                    hitX = cx
                    hitY = cy
                    break
                }
            }
            if (hitChild == null) return false
            view = hitChild
            x = hitX
            y = hitY
        }
        return view.hasOwnTouchPipeline()
    }

    private fun View.hasOwnTouchPipeline(): Boolean =
        isLongClickable || hasOnClickListeners()

    /**
     * Synthesize ACTION_CANCEL so any child currently consuming the
     * gesture (typically the ScrollView mid-scroll) releases it
     * cleanly. Without this the list would keep its scroll inertia
     * and the home-screen gesture would fire while a scroll animation
     * still plays underneath. The cancel is also fed to [tapDetector]
     * so any in-progress long-press / double-tap tracking aborts in
     * the same step.
     */
    private fun cancelChildGesture(source: MotionEvent) {
        val cancel = MotionEvent.obtain(source).apply { action = MotionEvent.ACTION_CANCEL }
        super.dispatchTouchEvent(cancel)
        tapDetector.onTouchEvent(cancel)
        cancel.recycle()
    }
}
