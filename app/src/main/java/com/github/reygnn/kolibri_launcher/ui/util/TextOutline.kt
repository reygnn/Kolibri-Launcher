package com.github.reygnn.kolibri_launcher.ui.util

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.util.AttributeSet
import android.widget.TextView
import androidx.appcompat.widget.AppCompatButton
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
