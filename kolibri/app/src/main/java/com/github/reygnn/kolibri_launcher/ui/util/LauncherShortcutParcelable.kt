package com.github.reygnn.kolibri_launcher.ui.util

import android.os.Parcel
import android.os.Parcelable
import com.github.reygnn.kolibri_launcher.domain.model.LauncherShortcut

/**
 * Parcelable wrapper around the pure-Kotlin domain type [LauncherShortcut],
 * used for Bundle/Intent transport in the UI layer.
 *
 * [LauncherShortcut] itself stays Android-free (it lives in `:domain`). When a
 * Fragment needs to ship one through `setFragmentResult` arguments, convert
 * via [toParcelable] on the way in and [toLauncherShortcut] on the way out.
 *
 * Hand-rolled `Parcelable` rather than `@Parcelize` for the same reason as
 * [AppInfoParcelable] — see that file's KDoc and TODO §10.
 */
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

    override fun describeContents(): Int = 0

    override fun writeToParcel(dest: Parcel, flags: Int) {
        dest.writeString(id)
        dest.writeString(packageName)
        // writeString accepts and round-trips null, no manual sentinel needed.
        dest.writeString(shortLabel)
    }

    companion object CREATOR : Parcelable.Creator<LauncherShortcutParcelable> {
        override fun createFromParcel(source: Parcel): LauncherShortcutParcelable = LauncherShortcutParcelable(
            id = source.readString()!!,
            packageName = source.readString()!!,
            shortLabel = source.readString()
        )

        override fun newArray(size: Int): Array<LauncherShortcutParcelable?> = arrayOfNulls(size)
    }
}

fun LauncherShortcut.toParcelable(): LauncherShortcutParcelable = LauncherShortcutParcelable(
    id = id,
    packageName = packageName,
    shortLabel = shortLabel
)
