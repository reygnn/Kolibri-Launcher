package com.github.reygnn.kolibri_launcher.ui.util

import android.os.Parcelable
import com.github.reygnn.kolibri_launcher.domain.model.AppInfo
import kotlinx.parcelize.Parcelize

/**
 * Parcelable wrapper around the pure-Kotlin domain type [AppInfo], used for
 * Bundle/Intent transport in the UI layer.
 *
 * [AppInfo] itself stays Android-free (it lives in `:domain`). When a Fragment
 * needs to ship one through `arguments` or a Bundle, convert via [toParcelable]
 * on the way in and [toAppInfo] on the way out.
 */
@Parcelize
data class AppInfoParcelable(
    val originalName: String,
    val displayName: String,
    val packageName: String,
    val className: String,
    val isSystemApp: Boolean,
    val isFavorite: Boolean
) : Parcelable {
    fun toAppInfo(): AppInfo = AppInfo(
        originalName = originalName,
        displayName = displayName,
        packageName = packageName,
        className = className,
        isSystemApp = isSystemApp,
        isFavorite = isFavorite
    )
}

fun AppInfo.toParcelable(): AppInfoParcelable = AppInfoParcelable(
    originalName = originalName,
    displayName = displayName,
    packageName = packageName,
    className = className,
    isSystemApp = isSystemApp,
    isFavorite = isFavorite
)
