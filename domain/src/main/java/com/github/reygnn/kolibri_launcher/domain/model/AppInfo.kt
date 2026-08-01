package com.github.reygnn.kolibri_launcher.domain.model

/**
 * Pure-Kotlin immutable data class for a text-based launcher entry.
 *
 * Holds the minimum information about an installed app and has no Android-framework
 * dependencies — neither Context/Drawable nor Parcelable. The UI layer wraps this
 * type via `AppInfoParcelable` for Bundle/Intent transport.
 */
data class AppInfo(
    val originalName: String,
    val displayName: String,
    val packageName: String,
    val className: String,
    val isFavorite: Boolean = false
) {
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