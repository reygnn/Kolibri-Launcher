package com.github.reygnn.launcher.common.ui

import android.os.Bundle
import android.view.View
import android.view.animation.AccelerateDecelerateInterpolator
import androidx.core.view.isVisible

/**
 * Shared controller for the app-drawer OVERLAY show/hide, used by both Kolibri
 * and Nyx so the slide animation, the intended-open state, the cancel-safe
 * visibility hand-off and the config-change persistence live in ONE place
 * instead of being duplicated (and drifting) in each `MainActivity`.
 *
 * WHY [isOpen] and not live [container] visibility: the container stays visible
 * throughout the ~[slideDurationMs] hide slide, so guarding on its visibility
 * would (a) drop a reopen that arrives mid-close, and (b) — because the drawer's
 * drag core shares [container]'s ViewPropertyAnimator — let a drag-driven
 * settle-back cancel the hide, whose end action then never commits the container
 * to GONE, stranding a "closed" flag against a visible container. So [isOpen] is
 * the single source of truth: it flips synchronously, and the container is only
 * committed to GONE in the hide animation's end action, re-checked against
 * [isOpen] so a mid-hide reopen keeps it shown.
 *
 * The controller owns the generic mechanics only. App-specific work — status
 * bar, keyboard, back callback, app-list refresh, and arming/disarming
 * drag-to-dismiss on the fragment — runs in [onShown]/[onHidden]; the controller
 * has no knowledge of the fragment or its drag wrapper. [onShown] receives the
 * `animate` flag so a caller can distinguish a genuine open (reset content,
 * refresh apps) from a config-change restore.
 *
 * ARM/DISARM (the wedge guard) belongs in [onHidden]/[onShown]: disarming the
 * drag before the hide slide is what prevents a fresh pull from cancelling the
 * hide and stranding the container visible. [onHidden] runs BEFORE the slide
 * starts, [onShown] runs while the container is already visible — the correct
 * points to disarm/arm.
 *
 * @param container the overlay container the drawer fragment lives in — the view
 *   this controller slides. MUST be the view the drag core's `dragTarget` points
 *   at, so a released dismiss hands off to the hide slide from the drag offset.
 * @param slideDistancePx full off-screen travel (the overlay is full-height).
 * @param slideDurationMs slide-in / slide-out duration.
 * @param onShown app-specific open-time work; `animate` is false on a restore.
 * @param onHidden app-specific close-time work; runs before the hide slide.
 */
class DrawerOverlayController(
    private val container: View,
    private val slideDistancePx: () -> Float,
    private val slideDurationMs: Long,
    private val onShown: (animate: Boolean) -> Unit,
    private val onHidden: () -> Unit,
) {

    /** The intended open state — single source of truth (see class KDoc). */
    var isOpen = false
        private set

    /**
     * Show the drawer. [animate] = false is the restore path (after a config
     * change): show instantly at rest with no slide, but otherwise identical
     * wiring. A no-op if already open.
     */
    fun show(animate: Boolean = true) {
        if (isOpen) return
        isOpen = true
        // Cancel any in-flight hide before re-opening. cancel() fires the hide's
        // end action, but that action is guarded on isOpen (already true above),
        // so it will not hide us.
        container.animate().cancel()
        if (animate) {
            // Start off-screen only for a genuine open-from-closed. A reopen that
            // interrupts the hide keeps its current offset, so it slides back up
            // from where it was instead of jumping to the bottom first.
            if (!container.isVisible) container.translationY = slideDistancePx()
        } else {
            container.translationY = 0f
        }
        container.isVisible = true
        onShown(animate)
        if (animate) {
            container.animate()
                .translationY(0f)
                .setDuration(slideDurationMs)
                .setInterpolator(AccelerateDecelerateInterpolator())
                .start()
        }
    }

    /** Hide the drawer with a slide-down. A no-op if already closed. */
    fun hide() {
        if (!isOpen) return
        isOpen = false
        onHidden()
        // Cancel first so a reopen that already started its own animation isn't
        // clobbered; the end action re-checks isOpen before committing to GONE,
        // so a mid-hide reopen leaves it shown.
        container.animate().cancel()
        container.animate()
            .translationY(slideDistancePx())
            .setDuration(slideDurationMs)
            .setInterpolator(AccelerateDecelerateInterpolator())
            .withEndAction {
                if (!isOpen) {
                    container.isVisible = false
                    container.translationY = 0f
                }
            }
            .start()
    }

    /** Persist the intended-open state across a config change / process death. */
    fun onSaveInstanceState(outState: Bundle) {
        outState.putBoolean(STATE_OPEN, isOpen)
    }

    /**
     * Restore the intended-open state. Call from the Activity's restore path.
     * Normalises the container to hidden first — a statically-inflated
     * FragmentContainerView can restore its own view visibility, which would
     * otherwise desync from [isOpen]. Returns true if the drawer was open, so
     * the caller can re-show at rest via `show(animate = false)`.
     */
    fun restore(savedState: Bundle?): Boolean {
        container.isVisible = false
        container.translationY = 0f
        return savedState?.getBoolean(STATE_OPEN, false) == true
    }

    private companion object {
        const val STATE_OPEN = "drawer_overlay_open"
    }
}
