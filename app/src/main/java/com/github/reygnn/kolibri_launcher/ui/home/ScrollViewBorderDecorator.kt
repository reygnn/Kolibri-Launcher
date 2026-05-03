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
 * **Reine Debug-Hilfe — standardmäßig deaktiviert.** [apply] und [remove]
 * returnen sofort am Methoden-Anfang. Zum Aktivieren das `return` in der
 * jeweiligen Methode entfernen; der gesamte Body ist intakt.
 *
 * Die Funktion war ursprünglich Bestandteil des Layouts und wurde archiviert,
 * weil das Split-Mode-Verhalten heute auch ohne visuelle Hilfe stabil ist.
 * Sie liegt hier (statt in der Git-Historie), damit sie beim nächsten
 * Split-Mode-Refactor mit einem Handgriff wieder einsetzbar ist.
 *
 * Der Decorator hält intern ein gecachtes [GradientDrawable], das zwischen
 * Aufrufen wiederverwendet wird (Allocation-Vermeidung). [clear] wird beim
 * Tear-Down der haltenden View aufgerufen.
 *
 * No tests — rationale (audit §3.1, ADR-style):
 * - The bodies of [apply] and [remove] are unreachable under the current
 *   dormant config (`return` at line 1, `@Suppress("UNREACHABLE_CODE")`
 *   on the rest). Testing a no-op is meaningless: it would only verify
 *   that the early-return is in place, which the source already states.
 * - The body, when reactivated, is pure view manipulation
 *   (`GradientDrawable.setStroke`, `setCornerRadius`, `target.background =
 *   …`, `target.setPadding`, `layoutParams =`). That all needs Robolectric
 *   or instrumented tests to exercise honestly. Per CLAUDE.md Rule 10 —
 *   "JVM is the default test target" — JVM mocking would only verify mock
 *   interactions, not real behavior.
 * - [clear] sets [cachedDrawable] to `null`. Under the dormant config the
 *   field is *always* `null` (because [apply] never assigns it), so
 *   `clear()` is a null-to-null no-op with no observable side effect — a
 *   test would assert nothing.
 *
 * If/when the dormant body is reactivated, replace this paragraph with a
 * Robolectric test (drawable manipulation needs a real `Resources` and
 * a real `View.background` setter). Until then, this paragraph stands in
 * for the missing test file.
 */
class ScrollViewBorderDecorator {

    private var cachedDrawable: GradientDrawable? = null

    @Suppress("UNREACHABLE_CODE")
    fun apply(target: ViewGroup, textColor: Int) {
        return // Debug-Visualisierung deaktiviert. Siehe Klassen-KDoc.

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

    @Suppress("UNREACHABLE_CODE")
    fun remove(target: ViewGroup) {
        return // Debug-Visualisierung deaktiviert. Siehe Klassen-KDoc.

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
