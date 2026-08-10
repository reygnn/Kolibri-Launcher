package com.github.reygnn.kolibri_launcher.ui.home

import android.content.res.ColorStateList
import android.graphics.Typeface
import android.text.TextUtils
import android.util.TypedValue
import android.view.Gravity
import android.view.ViewGroup
import android.widget.Button
import android.widget.FrameLayout
import androidx.annotation.VisibleForTesting
import androidx.core.graphics.ColorUtils
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.github.reygnn.kolibri_launcher.core.AppConstants
import com.github.reygnn.kolibri_launcher.core.TimberWrapper
import com.github.reygnn.kolibri_launcher.domain.model.AppInfo
import com.github.reygnn.kolibri_launcher.domain.model.FavoritesAlignment
import com.github.reygnn.kolibri_launcher.ui.util.AppInfoDiffCallback
import com.github.reygnn.kolibri_launcher.ui.util.toHorizontalGravity

/**
 * RecyclerView adapter for the home favorites list.
 *
 * Why a RecyclerView for ~20 favorites at most: NOT for view recycling
 * (the favorite list is small enough that recycling is irrelevant), but
 * for **scroll-engine parity with the AppDrawer**. ScrollView and
 * RecyclerView use different fling-friction curves, edge-effect
 * implementations, and post-touchSlop velocity handling — these
 * differences are perceptible side-by-side. Standardizing both
 * surfaces on RecyclerView removes that asymmetry.
 *
 * Each item is a programmatically constructed `Button` with
 * `WRAP_CONTENT` width — important for the wrapper's
 * [HomeGestureLayout.hasOwnTouchPipelineDescendantAt] hit-test, which
 * gives a long-press on a favorite text priority over the wrapper's
 * own customize-options dialog. `setOnLongClickListener` (set in
 * [onBindViewHolder]) implicitly toggles `isLongClickable = true`,
 * which is the signal the hit-test reads.
 *
 * Theme + layout updates flow through [setStyling]: the host fragment
 * snapshots the active theme/layout/font into a [Styling] value and
 * pushes it. Internally a `notifyItemRangeChanged` triggers a rebind
 * so every visible button picks up the new styling. App list updates
 * flow through `submitList` (inherited from [ListAdapter]) with the
 * standard [DiffUtil] callback.
 */
class HomeFavoritesAdapter(
    private val onAppClick: (AppInfo) -> Unit,
    private val onAppLongClick: (AppInfo) -> Unit,
) : ListAdapter<AppInfo, HomeFavoritesAdapter.ViewHolder>(AppInfoDiffCallback()) {

    /**
     * Snapshot of the styling state that varies with theme + layout
     * settings. Owned by the host fragment which produces a fresh
     * value whenever any contributing flow emits.
     */
    data class Styling(
        val textSizePx: Float,
        val verticalPaddingPx: Int,
        val horizPaddingPx: Int,
        val isBold: Boolean,
        val textColor: Int,
        val shadowColor: Int,
        val alignment: FavoritesAlignment,
    )

    private var styling: Styling = INITIAL_STYLING

    // Single-entry memo for the press-state ColorStateList. A styling rebind
    // (notifyItemRangeChanged) binds every visible row with the SAME
    // styling.textColor, so caching the last (color -> ColorStateList) collapses
    // N allocations per styling emit to one; a textColor change invalidates on
    // the first row (AUDIT-14 F3, part 2). Adapter binds run on the main thread
    // only, so no synchronization is needed.
    private var pressColorKey: Int? = null
    private var pressColor: ColorStateList? = null

    /**
     * Returns the press-state [ColorStateList] for [normalColor], reusing the
     * cached instance when [normalColor] matches the previous call. Single-entry
     * by design — see [pressColor].
     */
    @VisibleForTesting
    internal fun subtlePressColor(normalColor: Int): ColorStateList {
        val cached = pressColor
        if (cached != null && pressColorKey == normalColor) return cached
        val created = createSubtlePressColor(normalColor)
        pressColorKey = normalColor
        pressColor = created
        return created
    }

    fun setStyling(newStyling: Styling) {
        if (styling == newStyling) return
        styling = newStyling
        if (itemCount > 0) {
            // Payload makes DefaultItemAnimator.canReuseUpdatedViewHolder
            // return true, so existing ViewHolders are rebound in place
            // without the cross-fade change animation. Keeps the favorites
            // styling/alignment update in lockstep with the timeContainer
            // gravity flip in HomeFragment.applyLayoutToExistingViews —
            // otherwise the RV cross-fade lags ~250ms behind.
            notifyItemRangeChanged(0, itemCount, STYLING_PAYLOAD)
        }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        // The row is a MATCH_PARENT FrameLayout that hosts a WRAP_CONTENT
        // button. Alignment is encoded as `FrameLayout.LayoutParams.gravity`
        // on the button — that positions the button (start / center / end)
        // within the row WITHOUT widening the button itself.
        //
        // Why two views, not just a MATCH_PARENT button:
        // [HomeGestureLayout.hasOwnTouchPipelineDescendantAt] walks the view
        // tree at the touch point and decides whether the wrapper's own
        // long-press fires. A MATCH_PARENT button covers the whole row →
        // every long-press on a favorites row hits the favorite's
        // long-press (app-context-menu) and the wrapper's customize-options
        // dialog never gets a chance. Keeping the button WRAP_CONTENT
        // means the empty space next to a short favorite belongs to the
        // FrameLayout (which is NOT long-clickable), so the wrapper's
        // long-press path stays alive there.
        val container = FrameLayout(parent.context).apply {
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            )
        }
        val button = Button(container.context).apply {
            background = null
            includeFontPadding = false
            minHeight = 0
            minimumHeight = 0
            minWidth = 0
            minimumWidth = 0
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT,
                FrameLayout.LayoutParams.WRAP_CONTENT,
            )
        }
        container.addView(button)
        return ViewHolder(container, button)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        try {
            // Text is the only per-item property; the click/long-click listeners
            // are hoisted into the ViewHolder init (AUDIT-14 F3, part 3), so a
            // full bind sets text + styling only.
            holder.button.text = getItem(position).displayName
            applyStyling(holder)
        } catch (e: Throwable) {
            // Catch kept: view setters on a torn-down/recycled holder plus the
            // getItem race are the real failure modes (system-callback boundary,
            // Rule 11 four-category frame). no suspension point.
            TimberWrapper.silentError(e, "Error binding favorite at position $position")
        }
    }

    /**
     * Partial rebind for [STYLING_PAYLOAD] (AUDIT-14 F3, part 1): a
     * theme/alignment/layout change re-applies styling only, without re-setting
     * the text or re-wiring listeners. Empty payloads fall through to the full
     * [onBindViewHolder].
     */
    override fun onBindViewHolder(holder: ViewHolder, position: Int, payloads: MutableList<Any>) {
        if (payloads.isEmpty()) {
            super.onBindViewHolder(holder, position, payloads)
            return
        }
        try {
            // Every payload this adapter emits is STYLING_PAYLOAD; apply styling
            // once regardless of how many coalesced.
            applyStyling(holder)
        } catch (e: Throwable) {
            // Catch kept: same view-setter boundary as the full bind above.
            // no suspension point.
            TimberWrapper.silentError(e, "Error applying styling payload at position $position")
        }
    }

    /**
     * Applies the current [styling] to [holder]'s button: alignment gravity,
     * text size, padding, typeface, press-state color and shadow. Shared by the
     * full bind and the payload rebind so both stay in lockstep.
     */
    private fun applyStyling(holder: ViewHolder) {
        // Position the button within the row. Wrap-content + layout gravity keeps
        // the click/long-press hit area on the text only, leaving empty space for
        // the wrapper's own long-press handler.
        val params = holder.button.layoutParams as FrameLayout.LayoutParams
        val newGravity = styling.alignment.toHorizontalGravity() or Gravity.CENTER_VERTICAL
        if (params.gravity != newGravity) {
            params.gravity = newGravity
            holder.button.layoutParams = params
        }
        with(holder.button) {
            setTextSize(TypedValue.COMPLEX_UNIT_PX, styling.textSizePx)
            setPadding(
                styling.horizPaddingPx,
                styling.verticalPaddingPx,
                styling.horizPaddingPx,
                styling.verticalPaddingPx,
            )
            typeface = if (styling.isBold) Typeface.DEFAULT_BOLD else Typeface.DEFAULT
            setTextColor(subtlePressColor(styling.textColor))
            setShadowLayer(
                AppConstants.SHADOW_RADIUS_APPS,
                AppConstants.SHADOW_DX,
                AppConstants.SHADOW_DY,
                styling.shadowColor,
            )
        }
    }

    /**
     * Listeners are wired once here (AUDIT-14 F3, part 3) instead of per-bind,
     * so no lambda is allocated on every rebind. They live on the WRAP_CONTENT
     * [button] (NOT the container), so `isLongClickable` — the signal
     * [HomeGestureLayout.hasOwnTouchPipelineDescendantAt] reads — is set on the
     * button only, preserving the "empty row space stays the wrapper's
     * long-press area" hit-test contract (see [onCreateViewHolder]). The bound
     * item is resolved via [bindingAdapterPosition] at click time rather than
     * captured, so a list change between bind and tap cannot fire a stale item.
     */
    inner class ViewHolder(
        container: FrameLayout,
        val button: Button,
    ) : RecyclerView.ViewHolder(container) {

        init {
            button.setOnClickListener {
                val app = currentItemOrNull(bindingAdapterPosition) ?: return@setOnClickListener
                try {
                    // User callback — may throw anything (system-callback boundary).
                    onAppClick(app)
                } catch (e: Throwable) {
                    // Catch kept: callback boundary, Rule 11. no suspension point.
                    TimberWrapper.silentError(e, "Error in onAppClick for ${app.packageName}")
                }
            }
            button.setOnLongClickListener {
                val app = currentItemOrNull(bindingAdapterPosition)
                    ?: return@setOnLongClickListener false
                try {
                    onAppLongClick(app)
                } catch (e: Throwable) {
                    // Catch kept: callback boundary, Rule 11. no suspension point.
                    TimberWrapper.silentError(e, "Error in onAppLongClick for ${app.packageName}")
                }
                true
            }
        }
    }

    /**
     * Resolves the item at [position], or null when the row is unbound
     * ([RecyclerView.NO_POSITION]) or a concurrent list swap makes the read race
     * out of bounds — so a click during teardown fires nothing instead of crashing.
     */
    private fun currentItemOrNull(position: Int): AppInfo? {
        if (position == RecyclerView.NO_POSITION) return null
        return try {
            getItem(position)
        } catch (e: IndexOutOfBoundsException) {
            TimberWrapper.silentError(e, "Favorite item read out of bounds at position $position")
            null
        }
    }

    private companion object {
        // Marker payload for notifyItemRangeChanged — see setStyling KDoc.
        private val STYLING_PAYLOAD = Any()

        val INITIAL_STYLING = Styling(
            textSizePx = AppConstants.FALLBACK_TEXT_SIZE_PX,
            verticalPaddingPx = AppConstants.FALLBACK_VERTICAL_PADDING_PX,
            horizPaddingPx = AppConstants.FALLBACK_DIMEN_PX,
            isBold = AppConstants.FALLBACK_FONT_BOLD,
            textColor = AppConstants.DEFAULT_TEXT_COLOR,
            shadowColor = AppConstants.DEFAULT_TEXT_COLOR,
            alignment = AppConstants.DEFAULT_FAVORITES_ALIGNMENT,
        )

        fun createSubtlePressColor(normalColor: Int): ColorStateList {
            val pressedColor = ColorUtils.setAlphaComponent(
                normalColor,
                AppConstants.PRESSED_STATE_ALPHA,
            )
            return ColorStateList(
                arrayOf(
                    intArrayOf(android.R.attr.state_pressed),
                    intArrayOf(),
                ),
                intArrayOf(pressedColor, normalColor),
            )
        }
    }
}
