package com.github.reygnn.kolibri_launcher.ui.util

import android.os.Parcelable
import com.github.reygnn.kolibri_launcher.domain.model.LauncherShortcut
import kotlinx.parcelize.Parcelize

/**
 * Parcelable wrapper around the pure-Kotlin domain type [LauncherShortcut],
 * used for Bundle/Intent transport in the UI layer.
 *
 * [LauncherShortcut] itself stays Android-free (it lives in `:domain`). When a
 * Fragment needs to ship one through `setFragmentResult` arguments, convert
 * via [toParcelable] on the way in and [toLauncherShortcut] on the way out.
 */
@Parcelize
data class LauncherShortcutParcelable(
    val id: String,
    val packageName: String,
    val shortLabel: String?
) : Parcelable {
    fun toLauncherShortcut(): LauncherShortcut = LauncherShortcut(
        id = id,
        packageName = packageName,
        shortLabel = shortLabel
    )
}

fun LauncherShortcut.toParcelable(): LauncherShortcutParcelable = LauncherShortcutParcelable(
    id = id,
    packageName = packageName,
    shortLabel = shortLabel
)
