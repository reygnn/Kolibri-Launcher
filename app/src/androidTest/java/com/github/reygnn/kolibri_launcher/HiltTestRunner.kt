package com.github.reygnn.kolibri_launcher

import android.app.Application
import android.content.Context
import androidx.test.runner.AndroidJUnitRunner
import dagger.hilt.android.testing.HiltTestApplication

/**
 * Ein benutzerdefinierter Test-Runner, der für die Einrichtung von Hilt in instrumentierten
 * UI-Tests erforderlich ist. Er ersetzt die normale Anwendungs-Klasse
 * zur Laufzeit des Tests durch die HiltTestApplication.
 *
 * Dieser Runner wird in der build.gradle.kts-Datei konfiguriert.
 */
class HiltTestRunner : AndroidJUnitRunner() {

    override fun newApplication(
        cl: ClassLoader?,
        className: String?,
        context: Context?
    ): Application {
        return super.newApplication(cl, HiltTestApplication::class.java.name, context)
    }
}