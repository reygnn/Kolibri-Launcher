// NonInterceptingScrollView.kt
package com.github.reygnn.kolibri_launcher.ui.home

import android.content.Context
import android.util.AttributeSet
import android.view.MotionEvent
import android.widget.ScrollView

class NonInterceptingScrollView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : ScrollView(context, attrs, defStyleAttr) {

    // Standardmäßig erlauben wir dem ScrollView, Events abzufangen (Normalbetrieb)
    var allowIntercept = true

    /**
     * Kontrolliert, ob das ScrollView Touch-Events abfangen (intercept) soll.
     * Wenn wir den Full Mode haben und nicht gescrollt werden kann, setzen wir dies auf false.
     * Ein false bedeutet: Events direkt an das Kind weiterleiten, nicht selbst verarbeiten.
     */
    override fun onInterceptTouchEvent(ev: MotionEvent?): Boolean {
        if (!allowIntercept) {
            // Im Full Mode: Immer false zurückgeben, um Touches an Kinder/Hintergrund durchzureichen.
            return false
        }

        // Im Split Mode (oder wenn scrollbar): Standardverhalten beibehalten.
        return super.onInterceptTouchEvent(ev)
    }

    /**
     * Stellt sicher, dass das ScrollView im Full Mode nicht versucht,
     * selbst Touches zu verarbeiten, falls onInterceptTouchEvent umgangen wurde.
     */
    override fun onTouchEvent(ev: MotionEvent?): Boolean {
        if (!allowIntercept) {
            // Im Full Mode: False zurückgeben, um den Event an den Parent (rootLayout) weiterzuleiten.
            return false
        }
        return super.onTouchEvent(ev)
    }
}