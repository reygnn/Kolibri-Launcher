package com.github.reygnn.launcher.common.ui.gesture

import android.view.View
import android.view.ViewConfiguration
import android.view.ViewGroup
import android.view.animation.DecelerateInterpolator
import androidx.core.view.ViewCompat

/**
 * Base-agnostic **drag-to-dismiss** controller, shared by every drawer root
 * that wants the Pixel-style "the sheet follows your finger, the list scrolls
 * first, releasing decides open vs. close" feel. Sibling to
 * [GestureDispatchCore]: same "takes its [host] ViewGroup as a parameter
 * instead of *being* it" trick, so a FrameLayout-based wrapper (Nyx's
 * GestureFrameLayout) and a ConstraintLayout-based wrapper (Kolibri's
 * SwipeDownDismissLayout) share one implementation.
 *
 * WHY NESTED SCROLLING (and NOT the dispatchTouchEvent flick detector for the
 * dismiss axis): the flick detector fires a one-shot dismiss on a fast
 * downward flick *anywhere*, because it has no idea whether the list could
 * still scroll up — the same finger motion means "scroll the list up" and
 * "dismiss". Nested scrolling removes the ambiguity at the source: the
 * RecyclerView (a NestedScrollingChild by default) reports its scroll to us
 * every frame. We only take over with [onNestedScroll]'s *unconsumed*
 * downward delta — i.e. once the list is pinned at the top and the finger
 * keeps pulling down. No interception, so none of the
 * requestDisallowInterceptTouchEvent breakage that forced [GestureDispatchCore]
 * into dispatchTouchEvent. The two cores coexist: leave the drawer's
 * `onSwipeDown` unwired and let this own the down axis.
 *
 * DIVISION OF LABOUR with the host: this core owns the *drag* (finger-follow)
 * and the *settle-back* spring. It does NOT animate the dismiss itself —
 * on a committed dismiss it leaves [dragTarget] at its current offset and
 * calls [onDismiss], so the host's existing hide animation (shared with
 * back / tap / launch) takes over from exactly that offset. That is why
 * [dragTarget] must be the very view the host animates on hide (the overlay
 * container), giving a seamless hand-off with no jump and no double slide.
 *
 * Wrapper hookup (mirror each NestedScrollingParent3 method here):
 *   private val drag = DragToDismissCore(this)
 *   override fun onStartNestedScroll(c,t,a,ty) = drag.onStartNestedScroll(a,ty)
 *   override fun onNestedScrollAccepted(c,t,a,ty){ drag.onNestedScrollAccepted() }
 *   override fun onStopNestedScroll(t,ty){ drag.onStopNestedScroll(ty) }
 *   override fun onNestedPreScroll(t,dx,dy,co,ty){ drag.onNestedPreScroll(dy,co,ty) }
 *   override fun onNestedScroll(t,dxc,dyc,dxu,dyu,ty,co){ drag.onNestedScroll(t,dyu,ty,co) }
 *   override fun onNestedScroll(t,dxc,dyc,dxu,dyu,ty){ drag.onNestedScroll(t,dyu,ty,null) }
 *   override fun onNestedPreFling(t,vx,vy) = drag.onNestedPreFling(t,vy)
 *   override fun onDetachedFromWindow(){ drag.cancel(); super.onDetachedFromWindow() }
 *
 * Sign convention (Android nested scroll): a Y-axis scroll/fling delta is
 * NEGATIVE when the finger moves DOWN. So "finger pulling down at the top
 * edge" surfaces as dyUnconsumed < 0.
 */
class DragToDismissCore(private val host: ViewGroup) {

    /**
     * The view that follows the finger — MUST be the same view the host
     * animates on hide (the overlay container), so a committed dismiss hands
     * off seamlessly. Null disables the controller entirely.
     */
    var dragTarget: View? = null

    /**
     * Fired once when a drag commits to dismiss (threshold or fling). The host
     * runs its normal hide animation from [dragTarget]'s current translationY.
     */
    var onDismiss: (() -> Unit)? = null

    /** Drag fraction 0f..1f during the active drag (for an optional scrim fade). */
    var onDragProgress: ((Float) -> Unit)? = null

    /** Fraction of host height the sheet must be pulled to dismiss on release. */
    var dismissDistanceFraction = 0.28f

    /** Downward fling speed (px/s) that dismisses regardless of distance. */
    private val flingDismissVelocity =
        ViewConfiguration.get(host.context).scaledMinimumFlingVelocity * 3f

    /** Rubber-band cap: how far past the dismiss point the finger can outrun. */
    private val maxDragOvershootFraction = 0.6f

    private var dragOffset = 0f
    private var settling = false

    private val dismissDistancePx get() = host.height * dismissDistanceFraction
    private val maxDragPx get() = host.height * maxDragOvershootFraction

    fun onStartNestedScroll(axes: Int, @Suppress("UNUSED_PARAMETER") type: Int): Boolean {
        val target = dragTarget ?: return false
        if (axes and ViewCompat.SCROLL_AXIS_VERTICAL == 0) return false
        if (settling) {
            // A new gesture interrupts an in-flight settle-back — and, more
            // importantly, clears a STALE `settling` flag. `dragTarget` is the
            // overlay container the host also animates on hide, so the host's
            // `container.animate().cancel()` can tear down our settle-back
            // animator; ViewPropertyAnimator's withEndAction is not guaranteed
            // to run on cancel, which would otherwise leave `settling` stuck
            // true and wedge every future drag (this core instance outlives the
            // reused fragment). Resyncing from the live translation and taking
            // over here makes the drag un-wedgeable regardless of cancel timing.
            target.animate().cancel()
            dragOffset = target.translationY.coerceAtLeast(0f)
            settling = false
        }
        return true
    }

    fun onNestedScrollAccepted() { /* no per-gesture state to seed */ }

    /**
     * Finger moving UP (dy > 0) while the sheet is already pulled down: spend
     * that delta on RE-COLLAPSING the sheet before the list scrolls. Makes
     * "pull down a bit, push back up" feel continuous instead of snapping to
     * the list.
     */
    fun onNestedPreScroll(dy: Int, consumed: IntArray, type: Int) {
        if (type != ViewCompat.TYPE_TOUCH) return
        if (dragOffset > 0f && dy > 0) {
            val take = minOf(dy.toFloat(), dragOffset)
            dragOffset -= take
            applyDrag()
            consumed[1] = take.toInt()
        }
    }

    /**
     * Leftover downward pull the list could not consume (pinned at the top):
     * that is our drag. [consumed] is non-null on the P3 path — report the
     * vertical remainder we ate so the fling chain stays consistent.
     */
    fun onNestedScroll(target: View, dyUnconsumed: Int, type: Int, consumed: IntArray?) {
        if (type != ViewCompat.TYPE_TOUCH) return
        if (dyUnconsumed < 0 && !target.canScrollVertically(-1)) {
            dragOffset = (dragOffset - dyUnconsumed).coerceAtMost(maxDragPx) // -neg = +
            applyDrag()
            consumed?.let { it[1] += dyUnconsumed }
        }
    }

    fun onStopNestedScroll(type: Int) {
        if (type != ViewCompat.TYPE_TOUCH) return
        if (settling || dragOffset <= 0f) return
        if (dragOffset >= dismissDistancePx) commitDismiss() else animateSettleBack()
    }

    /**
     * A decisive downward fling dismisses even below the distance threshold —
     * but only when the list is at its top (or we are already mid-drag), so a
     * hard flick to scroll the list up can never trigger it. velocityY < 0 is
     * finger-down.
     */
    fun onNestedPreFling(target: View, velocityY: Float): Boolean {
        val fingerDownFast = velocityY < -flingDismissVelocity
        val atTopOrDragging = dragOffset > 0f || !target.canScrollVertically(-1)
        if (fingerDownFast && atTopOrDragging && !settling) {
            commitDismiss()
            return true // consume: no list fling
        }
        return false
    }

    private fun applyDrag() {
        val target = dragTarget ?: return
        target.translationY = dragOffset
        onDragProgress?.invoke((dragOffset / dismissDistancePx).coerceIn(0f, 1f))
    }

    private fun animateSettleBack() {
        val target = dragTarget ?: return
        settling = true
        target.animate()
            .translationY(0f)
            .setDuration(220)
            .setInterpolator(DecelerateInterpolator())
            .setUpdateListener {
                dragOffset = target.translationY
                onDragProgress?.invoke((dragOffset / dismissDistancePx).coerceIn(0f, 1f))
            }
            .withEndAction {
                dragOffset = 0f
                settling = false
                onDragProgress?.invoke(0f)
            }
            .start()
    }

    /**
     * Commit to dismiss: hand off to the host WITHOUT animating here. The
     * target is left at its current offset so the host's hide animation slides
     * it the rest of the way (and resets translationY + hides the container in
     * its own end action). We only clear our drag state.
     */
    private fun commitDismiss() {
        if (settling) return
        dragOffset = 0f
        onDismiss?.invoke()
        settling = false
    }

    /** Abort any in-flight settle (call from the wrapper's onDetachedFromWindow). */
    fun cancel() {
        dragTarget?.animate()?.cancel()
        dragOffset = 0f
        settling = false
    }
}
