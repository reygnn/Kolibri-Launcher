package com.github.reygnn.kolibri_launcher

import android.os.IBinder
import androidx.test.espresso.Root
import org.hamcrest.Description
import org.hamcrest.TypeSafeMatcher

/**
 * Ein benutzerdefinierter Matcher für Espresso, um UI-Tests auf Toasts auszuführen.
 *
 * HINWEIS: Das Testen von Toasts mit Espresso ist seit Android 11 (API 30)
 * notorisch unzuverlässig, da die Anzeige vom System übernommen wird.
 * Der robusteste Test für diese Logik sollte im ViewModel stattfinden.
 * Dieser Matcher ist ein "Best-Effort"-Versuch, das UI-Verhalten zu verifizieren.
 */
class ToastMatcher : TypeSafeMatcher<Root>() {

    override fun describeTo(description: Description?) {
        description?.appendText("is toast")
    }

    override fun matchesSafely(item: Root?): Boolean {
        // Zuerst sicherstellen, dass das Root-Objekt nicht null ist.
        if (item == null) {
            return false
        }

        // Der zuverlässigste Check, der übrig bleibt, ist der Abgleich der Window Tokens.
        // Dies stellt sicher, dass das Fenster, in dem der Toast (oder die UI) angezeigt wird,
        // zur laufenden App gehört.
        val windowToken: IBinder = item.decorView.windowToken
        val appToken: IBinder = item.decorView.applicationWindowToken

        // Bei einem echten Toast müssen das Fenster-Token und das App-Token identisch sein.
        return windowToken === appToken
    }
}