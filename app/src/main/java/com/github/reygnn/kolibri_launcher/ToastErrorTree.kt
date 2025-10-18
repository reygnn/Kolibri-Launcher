package com.github.reygnn.kolibri_launcher

import android.util.Log
import timber.log.Timber

/**
 * Ein spezieller Timber.Tree, der nur Fehler (Log.ERROR) verarbeitet.
 * Er formatiert die Fehlermeldung und sendet sie an den globalen ErrorEventBus,
 * damit die UI (z.B. eine Activity) darauf reagieren und einen Toast anzeigen kann.
 *
 * Unterstützt Tags, um "silent" Errors zu markieren, die nur im Logcat erscheinen sollen.
 */
class ToastErrorTree : Timber.Tree() {
    override fun log(priority: Int, tag: String?, message: String, t: Throwable?) {
        // Wir interessieren uns nur für Fehler.
        if (priority != Log.ERROR) {
            return
        }

        // Formatiere die Nachricht, um auch die Exception-Details einzubeziehen, falls vorhanden.
        val formattedMessage = if (t != null) {
            "$message\n${t.localizedMessage ?: t.toString()}"
        } else {
            message
        }

        // Sende die formatierte Nachricht mit optionalem Tag an unseren Event-Bus.
        ErrorEventBus.post(formattedMessage, tag)
    }
}