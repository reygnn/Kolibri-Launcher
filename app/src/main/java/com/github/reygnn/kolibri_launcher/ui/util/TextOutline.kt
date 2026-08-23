package com.github.reygnn.kolibri_launcher.ui.util

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.BlendMode
import android.graphics.BlendModeColorFilter
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.drawable.LayerDrawable
import android.util.AttributeSet
import android.widget.TextView
import androidx.appcompat.widget.AppCompatButton
import androidx.appcompat.widget.AppCompatImageView
import androidx.appcompat.widget.AppCompatTextView

/**
 * Draws a thin, crisp outline (stroke) around the glyphs of a [TextView] as a
 * background-independent replacement for `setShadowLayer`.
 *
 * Why a stroke and not a scrim or a blurred halo: a scrim dims the wallpaper
 * globally, which over-darkens an already-dark wallpaper (the 93 %-black case);
 * a centred blur (`setShadowLayer` with zero offset + large radius) always
 * reads as a fat glow. A hard stroke instead sits right on the glyph edge, so
 * it stays visually minimal yet wins contrast locally against BOTH black and
 * white pixels at once — the only mechanism that survives a high-frequency
 * black/white wallpaper without touching the wallpaper itself.
 *
 * Mechanism: the glyphs are painted twice — first with [Paint.Style.STROKE] in
 * the outline colour, then with the normal fill on top. The fill covers the
 * inner half of the centred stroke, leaving only its outer half visible as a
 * contour. `TextView.onDraw` sets the paint colour from the view's current text
 * colour (not from `paint.color`), so the colour swap MUST go through
 * [TextView.setTextColor]; each swap fires an `invalidate()`, which is why the
 * host view suppresses `invalidate()` while it is mid-draw — otherwise the two
 * swaps schedule an endless redraw loop. The suppression flag lives on the host
 * view (a primitive Boolean, JVM-default `false`), NOT here, because the View
 * super-constructor calls `invalidate()` before this helper's field is
 * initialised — reading a nullable helper there would NPE.
 *
 * Host contract (see [OutlinedTextView] / [OutlinedButton]): hold one instance,
 * bracket the two `super.onDraw` passes with [beginStroke]/[endStroke] when
 * [isActive], and no-op `invalidate()` while its own draw flag is set.
 */
class TextOutline {

    private var widthPx: Float = 0f
    private var strokeColors: ColorStateList? = null
    private var savedFill: ColorStateList? = null

    /** Outline is painted only with a positive width and a non-transparent colour. */
    val isActive: Boolean
        get() = widthPx > 0f && strokeColors != null

    /** Sets the outline width (px) and colour; [Color.TRANSPARENT] disables it. */
    fun set(widthPx: Float, color: Int) {
        this.widthPx = widthPx
        strokeColors = if (color == Color.TRANSPARENT) null else ColorStateList.valueOf(color)
    }

    /** Stroke pass: switch the shared TextPaint to the stroked outline colour. */
    fun beginStroke(view: TextView) {
        savedFill = view.textColors
        view.paint.apply {
            style = Paint.Style.STROKE
            strokeJoin = Paint.Join.ROUND
            strokeWidth = widthPx
        }
        strokeColors?.let(view::setTextColor)
    }

    /** Restore the real text colour and solid fill for the second (fill) pass. */
    fun endStroke(view: TextView) {
        view.paint.style = Paint.Style.FILL
        savedFill?.let(view::setTextColor)
    }
}

/**
 * [AppCompatTextView] that renders a thin outline via [TextOutline] instead of a
 * drop shadow. Used for the home-screen clock / date / battery lines.
 */
class OutlinedTextView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
) : AppCompatTextView(context, attrs, defStyleAttr) {

    private val outline = TextOutline()

    // Plain Boolean (JVM-default false), so the invalidate() the View
    // super-constructor fires before field init reads a safe value.
    private var isDrawingOutline = false

    /** Sets the outline width (px) and colour; [Color.TRANSPARENT] disables it. */
    fun setOutline(widthPx: Float, color: Int) {
        outline.set(widthPx, color)
        invalidate()
    }

    override fun invalidate() {
        if (isDrawingOutline) return
        super.invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        if (!outline.isActive) {
            super.onDraw(canvas)
            return
        }
        isDrawingOutline = true
        outline.beginStroke(this)
        super.onDraw(canvas)
        outline.endStroke(this)
        isDrawingOutline = false
        super.onDraw(canvas)
    }
}

/**
 * [AppCompatButton] twin of [OutlinedTextView] for the programmatically built
 * home-favorites buttons (kept a Button for the `HomeGestureLayout` hit-test
 * contract in [com.github.reygnn.kolibri_launcher.ui.home.HomeFavoritesAdapter]).
 */
class OutlinedButton @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
) : AppCompatButton(context, attrs, defStyleAttr) {

    private val outline = TextOutline()

    // See OutlinedTextView.isDrawingOutline for why this is a plain Boolean.
    private var isDrawingOutline = false

    /** Sets the outline width (px) and colour; [Color.TRANSPARENT] disables it. */
    fun setOutline(widthPx: Float, color: Int) {
        outline.set(widthPx, color)
        invalidate()
    }

    override fun invalidate() {
        if (isDrawingOutline) return
        super.invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        if (!outline.isActive) {
            super.onDraw(canvas)
            return
        }
        isDrawingOutline = true
        outline.beginStroke(this)
        super.onDraw(canvas)
        outline.endStroke(this)
        isDrawingOutline = false
        super.onDraw(canvas)
    }
}

/**
 * [AppCompatImageView] twin of [OutlinedTextView] for the home-screen event
 * icons (alarm / calendar). It reproduces the same adaptive, wallpaper-
 * independent legibility the clock/date/battery get from [OutlinedTextView],
 * but for a vector drawable instead of a text glyph — so the two event
 * indicators tint and outline exactly like the text next to them.
 *
 * How the crisp contour is achieved: the `src` is a two-layer [LayerDrawable]
 * (see `ic_*_outlined.xml`) whose BOTTOM layer is a fatter-stroke copy of the
 * icon and whose TOP layer is the normal fill. This view tints the top layer
 * with [setIconColor] (the text colour) and every layer beneath it with
 * [setOutline]'s colour (the tonal contrast). Because the outline is a REAL
 * wider vector stroke drawn once — not a stack of translated silhouettes — its
 * edge is as crisp as [TextOutline]'s centered stroke; the fatter stroke peeks a
 * fixed amount past the fill on every side, which is the drawable analogue of
 * the text's outward rim. (The earlier multi-copy "shadow" approach was replaced
 * because 8 translated, sub-pixel-shifted copies read as a soft grey wash next
 * to the crisp text.) The peek width is baked into the outline drawable's stroke
 * (tuned to TEXT_OUTLINE_WIDTH_DP/2), so [setOutline]'s `widthPx` is accepted
 * only to mirror [OutlinedTextView.setOutline] and is otherwise unused here.
 *
 * Both colours come from the same `UiColorsState` the text views use
 * (`HomeFragment.updateAllColors`). When the outline colour is
 * [Color.TRANSPARENT] (user disabled the text shadow) the outline layer is
 * simply hidden, leaving the plain fill. A non-layered `src` falls back to a
 * single icon-coloured tint with no outline.
 *
 * Tint mode is [BlendMode.SRC_IN], NOT the `ImageView.setColorFilter(int)`
 * default of `SRC_ATOP`. The tonal shadow/outline colour is SEMI-TRANSPARENT
 * (see `ObserveUiColorsUseCase.calculateTonalShadowColor`, e.g. 60 % black), and
 * `SRC_ATOP` composites a translucent tint OVER the drawable's own opaque white,
 * letting that white bleed through — the icon outline then rendered lighter than
 * the text's stroke, which draws the shadow colour directly. `SRC_IN` replaces
 * the colour and keeps the TINT's alpha (masked by the shape), so the rim
 * matches the text outline exactly.
 */
class OutlinedImageView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
) : AppCompatImageView(context, attrs, defStyleAttr) {

    private var outlineColor: Int = Color.TRANSPARENT
    private var iconColor: Int = Color.WHITE

    /** Outline is painted only with a non-transparent colour. */
    private val isOutlineActive: Boolean
        get() = outlineColor != Color.TRANSPARENT

    init {
        // Rest state: fill in its own colour, outline hidden, until the first
        // updateAllColors emit swaps in the adaptive colours.
        applyLayerColors()
    }

    /** Sets the icon (fill) colour — the adaptive text colour of the home screen. */
    fun setIconColor(color: Int) {
        iconColor = color
        applyLayerColors()
    }

    /**
     * Sets the outline colour; [Color.TRANSPARENT] hides the outline layer. The
     * outline WIDTH is baked into the `*_outline` drawable's stroke (see the
     * vector headers), so [widthPx] is accepted only to mirror
     * [OutlinedTextView.setOutline] and is not used here.
     */
    @Suppress("UNUSED_PARAMETER")
    fun setOutline(widthPx: Float, color: Int) {
        outlineColor = color
        applyLayerColors()
    }

    private fun applyLayerColors() {
        val current = drawable
        if (current is LayerDrawable && current.numberOfLayers >= 2) {
            // Convention: the TOP layer is the fill, every layer below it is outline.
            val fillIndex = current.numberOfLayers - 1
            current.getDrawable(fillIndex).colorFilter =
                BlendModeColorFilter(iconColor, BlendMode.SRC_IN)
            for (i in 0 until fillIndex) {
                val outlineLayer = current.getDrawable(i)
                if (isOutlineActive) {
                    outlineLayer.alpha = 255
                    outlineLayer.colorFilter = BlendModeColorFilter(outlineColor, BlendMode.SRC_IN)
                } else {
                    outlineLayer.alpha = 0
                }
            }
        } else {
            // Non-layered src: tint the whole drawable, no outline available.
            current?.colorFilter = BlendModeColorFilter(iconColor, BlendMode.SRC_IN)
        }
        invalidate()
    }
}
