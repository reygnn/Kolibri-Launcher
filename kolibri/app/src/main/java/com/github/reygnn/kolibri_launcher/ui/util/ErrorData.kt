package com.github.reygnn.kolibri_launcher.ui.util

/**
 * Ein einfacher, anwendungsweiter Event-Bus, der auf einem SharedFlow basiert.
 * Er wird verwendet, um Timber.e-Fehlermeldungen von überall in der App
 * an die aktuell sichtbare Activity zu senden, um sie als Toast anzuzeigen.
 *
 * Mit dem optionalen 'tag' können Fehler als "silent" markiert werden,
 * sodass sie nur im Logcat erscheinen, aber keinen Toast auslösen.
 */

data class ErrorData(
    val message: String,
    val tag: String? = null
)