package com.github.reygnn.kolibri_launcher

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
    val originalName: String,   // z.B. "Chrome"
    val displayName: String,    // Der Name, der tatsächlich angezeigt wird. Kann der benutzerdefinierte Name sein.
    val packageName: String,    // z.B. "com.android.chrome"
    val className: String,      // z.B. "com.google.android.apps.chrome.Main"
    val isSystemApp: Boolean = false,
    val isFavorite: Boolean = false
) : Parcelable {
    /**
     * Ein eindeutiger Bezeichner für einen spezifischen Launcher-Eintrag.
     * Notwendig, da mehrere Einträge (Activities) im selben Paket existieren können
     * (z.B. "Google" und "Voice Search").
     *
     * z.B. "com.android.chrome/com.google.android.apps.chrome.Main"
     */
    val componentName: String get() = "$packageName/$className"
}