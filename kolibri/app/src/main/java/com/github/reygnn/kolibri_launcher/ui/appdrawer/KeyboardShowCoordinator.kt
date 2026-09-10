package com.github.reygnn.kolibri_launcher.ui.appdrawer

/**
 * Koordiniert die Entscheidung, WANN und WIE das Keyboard angezeigt werden soll.
 *
 * HINTERGRUND:
 * `View.doOnLayout()` registriert einen OnGlobalLayoutListener, der NUR feuert,
 * wenn ein Layout-Pass stattfindet. Ist der View bereits vollständig gelayoutet
 * (z.B. bei Fragment-Reuse, schneller Navigation, gecachtem Layout),
 * wird der Callback NIEMALS aufgerufen → Keyboard erscheint nicht.
 *
 * Diese Klasse kapselt die Entscheidungslogik für Unit-Tests.
 * Die tatsächliche Framework-Interaktion (InputMethodManager, View-Operationen)
 * bleibt im Fragment.
 */
class KeyboardShowCoordinator {

    /**
     * Bestimmt die Strategie zum Anzeigen des Keyboards basierend auf dem View-Zustand.
     *
     * @param isViewLaidOut true wenn der View bereits einen Layout-Pass hatte
     * @param isViewEffectivelyVisible true wenn der View tatsächlich Keyboard-Input empfangen kann
     *        (visibility == VISIBLE && isAttachedToWindow && windowVisibility == VISIBLE)
     * @param isFragmentAdded true wenn das Fragment noch am Activity hängt
     * @param isAutoShowEnabled true wenn der User "Auto-Show Keyboard" aktiviert hat
     * @return Die anzuwendende Strategie
     */
    fun determineStrategy(
        isViewLaidOut: Boolean,
        isViewEffectivelyVisible: Boolean,
        isFragmentAdded: Boolean,
        isAutoShowEnabled: Boolean
    ): ShowKeyboardStrategy {
        // Guard: Setting deaktiviert
        if (!isAutoShowEnabled) {
            return ShowKeyboardStrategy.Skip(SkipReason.SETTING_DISABLED)
        }

        // Guard: Fragment nicht mehr aktiv
        if (!isFragmentAdded) {
            return ShowKeyboardStrategy.Skip(SkipReason.FRAGMENT_DETACHED)
        }

        // Guard: View kann keinen Input empfangen (GONE, INVISIBLE, detached, etc.)
        if (!isViewEffectivelyVisible) {
            return ShowKeyboardStrategy.Skip(SkipReason.VIEW_NOT_VISIBLE)
        }

        // Kernentscheidung: View-Zustand
        return if (isViewLaidOut) {
            ShowKeyboardStrategy.ShowImmediately
        } else {
            ShowKeyboardStrategy.WaitForLayout
        }
    }

    /**
     * Sealed interface für exhaustive when-Behandlung.
     */
    sealed interface ShowKeyboardStrategy {
        /**
         * View ist bereits gelayoutet → Keyboard sofort anzeigen.
         * Kein Callback nötig, da Layout-Pass bereits erfolgt ist.
         */
        data object ShowImmediately : ShowKeyboardStrategy

        /**
         * View noch nicht gelayoutet → doOnLayout Callback registrieren.
         * Keyboard wird angezeigt, sobald der Layout-Pass abgeschlossen ist.
         */
        data object WaitForLayout : ShowKeyboardStrategy

        /**
         * Keyboard soll nicht angezeigt werden.
         * @param reason Der Grund für das Überspringen (für Logging/Debugging)
         */
        data class Skip(val reason: SkipReason) : ShowKeyboardStrategy
    }

    /**
     * Gründe, warum das Keyboard nicht angezeigt wird.
     * Nützlich für Debugging und Logging.
     */
    enum class SkipReason {
        /** User hat Auto-Show in Settings deaktiviert */
        SETTING_DISABLED,

        /** Fragment ist nicht mehr am Activity attached */
        FRAGMENT_DETACHED,

        /** View ist nicht sichtbar oder nicht attached zum Window */
        VIEW_NOT_VISIBLE
    }
}