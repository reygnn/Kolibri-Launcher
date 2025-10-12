package com.github.reygnn.kolibri_launcher

import android.view.View
import androidx.test.espresso.UiController
import androidx.test.espresso.ViewAction
import androidx.test.espresso.matcher.ViewMatchers.isAssignableFrom
import com.google.android.material.chip.Chip
import org.hamcrest.Matcher

/**

Eine benutzerdefinierte ViewAction, die den Klick auf das Schließen-Icon eines
com.google.android.material.chip.Chip simuliert.
Dies ist der robusteste Weg, diese Interaktion zu testen, da er unabhängig von
Content Description, internen IDs oder der genauen Klick-Position ist.
 */
fun clickOnChipCloseIcon(): ViewAction {
    return object : ViewAction {
        override fun getConstraints(): Matcher<View> {
            // Stellt sicher, dass diese Aktion nur auf Chip-Widgets angewendet wird.
            return isAssignableFrom(Chip::class.java)
        }

        override fun getDescription(): String {
            return "Click on the close icon of a Chip."
        }

        override fun perform(uiController: UiController, view: View) {
            val chip = view as Chip
            // Ruft die interne Methode auf, die der OnClickListener des Icons auslösen würde.
            chip.performCloseIconClick()
        }

    }
}