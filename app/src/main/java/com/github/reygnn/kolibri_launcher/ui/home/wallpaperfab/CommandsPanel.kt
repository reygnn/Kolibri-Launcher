package com.github.reygnn.kolibri_launcher.ui.home.wallpaperfab

import android.content.Context
import android.util.AttributeSet
import android.view.LayoutInflater
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.github.reygnn.kolibri_launcher.R
import com.google.android.material.button.MaterialButton

/**
 * Overflow panel that the [SpeedDialFabCluster]'s `☰` mini-FAB
 * toggles. Hosts the controls that don't earn a slot on the
 * speed-dial column itself: the four snap controls, the layer-
 * reorder controls (Delete, Down, Up), the "Layer x/N" indicator,
 * and a close button.
 *
 * The panel slides into view with a short fade + translation
 * animation so the user gets a clear visual link between the
 * `☰` tap and the appearing controls. Hidden state uses
 * `visibility = GONE` so the panel does not consume touches while
 * the speed-dial alone is in use.
 *
 * Click listeners + icon setters mirror the legacy toolbar's API so
 * `WallpaperEditController` can adopt this view with minimal call-
 * site change.
 */
class CommandsPanel @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
) : LinearLayout(context, attrs, defStyleAttr) {

    /**
     * Slide-in distance for the show / hide animation in pixels.
     * Resolved at construction from `R.dimen.wallpaper_panel_slide_distance`
     * (24dp) so the offset is density-aware — the previous raw-`24f`
     * was visually invisible on high-dpi displays.
     */
    private val slideInDistancePx: Float =
        context.resources.getDimension(R.dimen.wallpaper_panel_slide_distance)

    private val btnSnapToggle: MaterialButton
    private val btnSnapMode: MaterialButton
    private val btnHSnap: MaterialButton
    private val btnVSnap: MaterialButton
    private val btnLayerDelete: MaterialButton
    private val btnLayerUp: MaterialButton
    private val btnLayerDown: MaterialButton
    private val btnClose: MaterialButton
    private val txtLayerIndicator: TextView

    init {
        orientation = VERTICAL
        background = androidx.core.content.ContextCompat.getDrawable(
            context,
            R.drawable.bg_wallpaper_edit_toolbar,
        )
        visibility = View.GONE

        LayoutInflater.from(context).inflate(R.layout.view_commands_panel, this, true)

        btnSnapToggle = findViewById(R.id.btnSnapToggle)
        btnSnapMode = findViewById(R.id.btnSnapMode)
        btnHSnap = findViewById(R.id.btnHSnap)
        btnVSnap = findViewById(R.id.btnVSnap)
        btnLayerDelete = findViewById(R.id.btnLayerDelete)
        btnLayerUp = findViewById(R.id.btnLayerUp)
        btnLayerDown = findViewById(R.id.btnLayerDown)
        btnClose = findViewById(R.id.btnCloseCommands)
        txtLayerIndicator = findViewById(R.id.txtLayerIndicator)
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        // The panel sits at the screen's bottom edge via
        // layout_gravity="bottom"; without an inset adjustment it
        // would render behind the 3-button navigation bar (and behind
        // the gesture-nav pill). Translate up by exactly the nav-bar
        // inset so the rounded card stays fully visible.
        //
        // translationY (rather than layout-params margin tweaking) is
        // used so the existing show/hide animation in [showPanel] /
        // [hidePanel] still composes additively — those animate
        // translationY too, so the resting Y is just the inset offset
        // and the slide-in goes from `restingY + slideInDistancePx`
        // back to `restingY`.
        ViewCompat.setOnApplyWindowInsetsListener(this) { view, insets ->
            val navBars = insets.getInsets(WindowInsetsCompat.Type.navigationBars())
            restingTranslationY = -navBars.bottom.toFloat()
            if (!isInAnimation) {
                view.translationY = restingTranslationY
            }
            insets
        }
    }

    // ============================================================
    // VISIBILITY
    // ============================================================

    val isPanelShown: Boolean get() = visibility == View.VISIBLE

    /**
     * Resting `translationY` value — the nav-bar inset offset applied
     * by [onAttachedToWindow]. Show / hide animations interpolate
     * between this and `restingTranslationY + slideInDistancePx`
     * instead of `0` and `slideInDistancePx`, so the panel never
     * jumps back below the nav bar mid-animation.
     */
    private var restingTranslationY: Float = 0f
    private var isInAnimation: Boolean = false

    fun showPanel() {
        if (isPanelShown) return
        visibility = View.VISIBLE
        alpha = 0f
        translationY = restingTranslationY + slideInDistancePx
        isInAnimation = true
        animate()
            .alpha(1f)
            .translationY(restingTranslationY)
            .setDuration(ANIM_DURATION_MS)
            .withEndAction { isInAnimation = false }
            .start()
    }

    fun hidePanel() {
        if (!isPanelShown) return
        isInAnimation = true
        animate()
            .alpha(0f)
            .translationY(restingTranslationY + slideInDistancePx)
            .setDuration(ANIM_DURATION_MS)
            .withEndAction {
                visibility = View.GONE
                isInAnimation = false
            }
            .start()
    }

    fun togglePanel() {
        if (isPanelShown) hidePanel() else showPanel()
    }

    // ============================================================
    // LISTENERS
    // ============================================================

    fun setOnSnapToggleClicked(listener: () -> Unit) =
        btnSnapToggle.setOnClickListener { listener() }

    fun setOnSnapModeClicked(listener: () -> Unit) =
        btnSnapMode.setOnClickListener { listener() }

    fun setOnHorizontalSnapClicked(listener: () -> Unit) =
        btnHSnap.setOnClickListener { listener() }

    fun setOnVerticalSnapClicked(listener: () -> Unit) =
        btnVSnap.setOnClickListener { listener() }

    fun setOnLayerDeleteClicked(listener: () -> Unit) =
        btnLayerDelete.setOnClickListener { listener() }

    fun setOnLayerUpClicked(listener: () -> Unit) =
        btnLayerUp.setOnClickListener { listener() }

    fun setOnLayerDownClicked(listener: () -> Unit) =
        btnLayerDown.setOnClickListener { listener() }

    fun setOnCloseClicked(listener: () -> Unit) =
        btnClose.setOnClickListener { listener() }

    // ============================================================
    // ICON + STATE updates
    // ============================================================

    fun setSnapToggleIcon(resId: Int) = btnSnapToggle.setIconResource(resId)
    fun setSnapModeIcon(resId: Int) = btnSnapMode.setIconResource(resId)
    fun setHorizontalSnapIcon(resId: Int) = btnHSnap.setIconResource(resId)
    fun setVerticalSnapIcon(resId: Int) = btnVSnap.setIconResource(resId)

    fun setLayerIndicator(text: CharSequence?, visible: Boolean) {
        txtLayerIndicator.text = text
        txtLayerIndicator.visibility = if (visible) View.VISIBLE else View.GONE
    }

    fun setLayerButtonsState(
        deleteVisible: Boolean,
        deleteEnabled: Boolean,
        upVisible: Boolean,
        upEnabled: Boolean,
        downVisible: Boolean,
        downEnabled: Boolean,
    ) {
        btnLayerDelete.visibility = if (deleteVisible) View.VISIBLE else View.GONE
        btnLayerDelete.isEnabled = deleteEnabled
        btnLayerDelete.alpha = if (deleteEnabled) 1f else DISABLED_ALPHA

        btnLayerUp.visibility = if (upVisible) View.VISIBLE else View.GONE
        btnLayerUp.isEnabled = upEnabled
        btnLayerUp.alpha = if (upEnabled) 1f else DISABLED_ALPHA

        btnLayerDown.visibility = if (downVisible) View.VISIBLE else View.GONE
        btnLayerDown.isEnabled = downEnabled
        btnLayerDown.alpha = if (downEnabled) 1f else DISABLED_ALPHA
    }

    private companion object {
        const val ANIM_DURATION_MS = 180L
    }
}
