package com.github.reygnn.kolibri_launcher.ui.home

import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.view.ViewGroup
import android.widget.LinearLayout
import com.github.reygnn.kolibri_launcher.R
import com.github.reygnn.kolibri_launcher.core.AppConstants

/**
 * Zeichnet im Split-Mode einen halbtransparenten Rahmen um eine ScrollView,
 * damit die scrollbare Region beim Entwickeln des Split-Layouts visuell von
 * der Touch-Zone getrennt ist.
 *
 * **Pure debug helper — disabled by default.** The [enabled] constructor
 * flag gates [apply] and [remove]; production constructs the decorator
 * with the default `enabled = false`, so neither method has any visible
 * effect. Tests instantiate with `enabled = true` to exercise the body
 * (see [ScrollViewBorderDecoratorTest]). To re-enable in production,
 * flip the default — the body is ready to go.
 *
 * Die Funktion war ursprünglich Bestandteil des Layouts und wurde archiviert,
 * weil das Split-Mode-Verhalten heute auch ohne visuelle Hilfe stabil ist.
 * Sie liegt hier (statt in der Git-Historie), damit sie beim nächsten
 * Split-Mode-Refactor mit einem Handgriff wieder einsetzbar ist.
 *
 * Der Decorator hält intern ein gecachtes [GradientDrawable], das zwischen
 * Aufrufen wiederverwendet wird (Allocation-Vermeidung). [clear] wird beim
 * Tear-Down der haltenden View aufgerufen.
 */
class ScrollViewBorderDecorator(
    private val enabled: Boolean = false,
) {

    private var cachedDrawable: GradientDrawable? = null

    fun apply(target: ViewGroup, textColor: Int) {
        if (!enabled) return

        // Body kept ready for reactivation. The inner Resource-catches
        // around getDimension* stay (Resources.NotFoundException is real
        // under ProGuard / themed-context edge cases). The previous outer
        // catch only fired on a ClassCastException from the LayoutParams
        // cast — that's a programmer error (wrong parent type) per
        // CLAUDE.md Rule 11; the safe-cast `as?` makes it impossible
        // and removes the need for an outer catch entirely.
        val frameColor = Color.argb(
            AppConstants.BORDER_ALPHA,
            Color.red(textColor),
            Color.green(textColor),
            Color.blue(textColor),
        )

        if (cachedDrawable == null) {
            cachedDrawable = GradientDrawable().apply {
                setColor(Color.TRANSPARENT)
            }
        }

        cachedDrawable?.apply {
            val strokeWidth = try {
                target.resources.getDimensionPixelSize(R.dimen.split_screen_border_width)
            } catch (e: Throwable) {
                AppConstants.FALLBACK_BORDER_WIDTH_PX
            }
            setStroke(strokeWidth, frameColor)

            val cornerRadius = try {
                target.resources.getDimension(R.dimen.split_screen_corner_radius)
            } catch (e: Throwable) {
                AppConstants.FALLBACK_CORNER_RADIUS_PX
            }
            setCornerRadius(cornerRadius)
        }

        if (target.background !== cachedDrawable) {
            target.background = cachedDrawable
        }

        val borderPadding = try {
            target.resources.getDimensionPixelSize(R.dimen.split_screen_border_inset)
        } catch (e: Throwable) {
            AppConstants.FALLBACK_DIMEN_PX
        }

        target.setPadding(0, borderPadding, borderPadding, borderPadding)
        target.clipToPadding = true

        // Safe cast: if the target is not a LinearLayout child, bail out
        // silently rather than ClassCastException-and-catch.
        val params = target.layoutParams as? LinearLayout.LayoutParams ?: return
        params.setMargins(0, 0, 0, 0)
        target.layoutParams = params
    }

    fun remove(target: ViewGroup) {
        if (!enabled) return

        target.background = null
        target.setPadding(0, 0, 0, 0)
        // Same safe-cast rationale as apply().
        val params = target.layoutParams as? LinearLayout.LayoutParams ?: return
        params.setMargins(0, 0, 0, 0)
        target.layoutParams = params
    }

    fun clear() {
        cachedDrawable = null
    }
}
