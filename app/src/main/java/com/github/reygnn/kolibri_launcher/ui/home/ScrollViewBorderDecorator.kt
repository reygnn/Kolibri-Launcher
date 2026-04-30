package com.github.reygnn.kolibri_launcher.ui.home

import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.view.ViewGroup
import android.widget.LinearLayout
import com.github.reygnn.kolibri_launcher.R
import com.github.reygnn.kolibri_launcher.core.AppConstants
import com.github.reygnn.kolibri_launcher.core.TimberWrapper

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
 */
class ScrollViewBorderDecorator {

    private var cachedDrawable: GradientDrawable? = null

    @Suppress("UNREACHABLE_CODE")
    fun apply(target: ViewGroup, textColor: Int) {
        return // Debug-Visualisierung deaktiviert. Siehe Klassen-KDoc.

        try {
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

            val params = target.layoutParams as LinearLayout.LayoutParams
            params.setMargins(0, 0, 0, 0)
            target.layoutParams = params
        } catch (e: Throwable) {
            TimberWrapper.silentError(e, "Error applying border")
        }
    }

    @Suppress("UNREACHABLE_CODE")
    fun remove(target: ViewGroup) {
        return // Debug-Visualisierung deaktiviert. Siehe Klassen-KDoc.

        try {
            target.background = null
            target.setPadding(0, 0, 0, 0)
            val params = target.layoutParams as LinearLayout.LayoutParams
            params.setMargins(0, 0, 0, 0)
            target.layoutParams = params
        } catch (e: Throwable) {
            TimberWrapper.silentError(e, "Error removing border")
        }
    }

    fun clear() {
        cachedDrawable = null
    }
}
