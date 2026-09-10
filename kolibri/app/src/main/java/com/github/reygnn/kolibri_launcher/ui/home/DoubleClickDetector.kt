package com.github.reygnn.kolibri_launcher.ui.home

import com.github.reygnn.kolibri_launcher.core.AppConstants

/**
 * PURE LOGIC - Double Click Detector
 *
 * Erkennt, ob zwei aufeinanderfolgende Klicks innerhalb [thresholdMillis]
 * als Double-Click gelten. Stateful: merkt sich nur den letzten Klick-Timestamp.
 *
 * Ersetzt den zuvor in `HomeFragment.DoubleClickListener` hardgecodeten
 * `System.currentTimeMillis()`-Aufruf. Tests liefern eine eigene [clock]-Funktion
 * und müssen somit kein Thread.sleep nutzen.
 *
 * Verdrahtung in DoubleClickListener:
 *
 *   abstract class DoubleClickListener(
 *       private val detector: DoubleClickDetector = DoubleClickDetector()
 *   ) : View.OnClickListener {
 *       override fun onClick(v: View?) {
 *           if (detector.registerClick()) {
 *               try { onDoubleClick() } catch (e: Throwable) {
 *                   TimberWrapper.silentError(e, "Error in onDoubleClick")
 *               }
 *           }
 *       }
 *       abstract fun onDoubleClick()
 *   }
 */
class DoubleClickDetector(
    private val thresholdMillis: Long = AppConstants.DOUBLE_CLICK_THRESHOLD,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private var lastClickTime: Long = 0L

    /** Registriert einen Klick. Liefert true, wenn dies der zweite Klick eines Double-Clicks ist. */
    fun registerClick(): Boolean {
        val now = clock()
        val isDoubleClick = (now - lastClickTime) < thresholdMillis
        // On a hit, reset the pairing so the SAME click can't also serve as
        // the first half of another pair — otherwise a fast triple-tap fires
        // twice (N rapid taps → N-1 hits). A miss starts a fresh pair from now.
        lastClickTime = if (isDoubleClick) 0L else now
        return isDoubleClick
    }
}
