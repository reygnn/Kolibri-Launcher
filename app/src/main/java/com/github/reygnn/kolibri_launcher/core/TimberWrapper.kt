package com.github.reygnn.kolibri_launcher.core

import timber.log.Timber

object TimberWrapper {
    const val SILENT_LOG_TAG = "SILENT_ERROR"

    /**
     * Loggt einen Fehler, der nur im Logcat erscheinen soll, ohne Toast.
     */
    fun silentError(throwable: Throwable, message: String) {
        Timber.Forest.tag(SILENT_LOG_TAG).e(throwable, message)
    }

    fun silentError(message: String) {
        Timber.Forest.tag(SILENT_LOG_TAG).e(message)
    }

    fun silentError(throwable: Throwable) {
        Timber.Forest.tag(SILENT_LOG_TAG).e(throwable)
    }
}