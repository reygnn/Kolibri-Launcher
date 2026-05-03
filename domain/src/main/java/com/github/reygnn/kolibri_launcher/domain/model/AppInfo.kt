package com.github.reygnn.kolibri_launcher.domain.model

import android.os.Parcelable
import kotlinx.parcelize.Parcelize

/**
 * Eine reine, unveränderliche Datenklasse für einen textbasierten Launcher.
 * Sie enthält nur die minimal notwendigen Informationen und hat KEINE Abhängigkeiten
 * zum Android Framework wie Context oder Drawable.
 *
 * Die Parcelable-Implementierung wird automatisch vom kotlin-parcelize-Plugin generiert.
 */

@Parcelize
data class AppInfo(
    val originalName: String,
    val displayName: String,
    val packageName: String,
    val className: String,
    val isSystemApp: Boolean = false,
    val isFavorite: Boolean = false
) : Parcelable {
    /**
     * Ein eindeutiger Bezeichner für einen spezifischen Launcher-Eintrag.
     * Notwendig, da mehrere Einträge (Activities) im selben Paket existieren können
     * (z.B. "Google" und "Voice Search").
     *
     * Normalisiert automatisch Kurzform (/.Activity) zu Langform (package.Activity)
     * für konsistenten Vergleich, da Android beide Schreibweisen zulässt.
     *
     * z.B. "com.android.chrome/com.google.android.apps.chrome.Main"
     */
    val componentName: String
        get() {
            // Normalisiere className: Wenn es mit "." beginnt, ist es Kurzform
            val normalizedClassName = if (className.startsWith(".")) {
                "$packageName$className"
            } else {
                className
            }
            return "$packageName/$normalizedClassName"
        }
}