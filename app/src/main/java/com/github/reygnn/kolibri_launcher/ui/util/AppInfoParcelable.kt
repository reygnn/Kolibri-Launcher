package com.github.reygnn.kolibri_launcher.ui.util

import android.os.Parcel
import android.os.Parcelable
import com.github.reygnn.kolibri_launcher.domain.model.AppInfo

/**
 * Parcelable wrapper around the pure-Kotlin domain type [AppInfo], used for
 * Bundle/Intent transport in the UI layer.
 *
 * [AppInfo] itself stays Android-free (it lives in `:domain`). When a Fragment
 * needs to ship one through `arguments` or a Bundle, convert via [toParcelable]
 * on the way in and [toAppInfo] on the way out.
 *
 * Hand-rolled `Parcelable` rather than `@Parcelize`: the kotlin-parcelize
 * compiler-plugin's IR pass doesn't attach to AGP 9 built-in Kotlin's
 * `compileKotlin` tasks (TODO §10 mega-bundle post-mortem, 2026-05-15).
 * Five trivial fields — manual write/read is cheaper than the
 * `android.builtInKotlin=false` escape-hatch's deferred AGP-10 bill.
 * `data class` is preserved (`equals`/`hashCode`/`toString`/`copy` are
 * orthogonal to `Parcelable`); only the three interface methods + `CREATOR`
 * are hand boilerplate.
 */
data class AppInfoParcelable(
    val originalName: String,
    val displayName: String,
    val packageName: String,
    val className: String,
    val isFavorite: Boolean
) : Parcelable {
    fun toAppInfo(): AppInfo = AppInfo(
        originalName = originalName,
        displayName = displayName,
        packageName = packageName,
        className = className,
        isFavorite = isFavorite
    )

    override fun describeContents(): Int = 0

    override fun writeToParcel(dest: Parcel, flags: Int) {
        dest.writeString(originalName)
        dest.writeString(displayName)
        dest.writeString(packageName)
        dest.writeString(className)
        dest.writeInt(if (isFavorite) 1 else 0)
    }

    companion object CREATOR : Parcelable.Creator<AppInfoParcelable> {
        override fun createFromParcel(source: Parcel): AppInfoParcelable = AppInfoParcelable(
            originalName = source.readString()!!,
            displayName = source.readString()!!,
            packageName = source.readString()!!,
            className = source.readString()!!,
            isFavorite = source.readInt() != 0
        )

        override fun newArray(size: Int): Array<AppInfoParcelable?> = arrayOfNulls(size)
    }
}

fun AppInfo.toParcelable(): AppInfoParcelable = AppInfoParcelable(
    originalName = originalName,
    displayName = displayName,
    packageName = packageName,
    className = className,
    isFavorite = isFavorite
)
