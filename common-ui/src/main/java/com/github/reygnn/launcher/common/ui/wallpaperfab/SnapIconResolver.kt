package com.github.reygnn.launcher.common.ui.wallpaperfab

import androidx.annotation.DrawableRes
import com.github.reygnn.launcher.common.ui.R

/**
 * Pure Kotlin enum, free of Android-View dependencies.
 *
 * `ZoomableImageView.SnapMode` is a separate nested enum on the View itself;
 * each app's wallpaper-edit controller bridges between the two via a small
 * `toIconMode()` helper. Kept separate so [SnapIconResolver] stays testable
 * without Android class loading.
 */
enum class SnapMode { EDGE, CENTER }

/**
 * PURE LOGIC - Snap Icon Resolver
 *
 * Bildet den Snap-Button-Zustand (enabled + Mode) auf die korrekte
 * Drawable-Resource-ID ab. Zuvor waren vier fast-identische
 * when/if-Kaskaden in HomeFragment verteilt, die R.drawable.*-Konstanten
 * direkt zurückgaben.
 *
 * Hinweis: R.drawable.*-IDs sind zum Testzeitpunkt über die generierte
 * R-Klasse verfügbar (siehe bestehende Tests, die R.string.* verwenden).
 */
object SnapIconResolver {

    @DrawableRes
    fun resolveMagnet(enabled: Boolean): Int =
        if (enabled) R.drawable.ic_magnet_on else R.drawable.ic_magnet_off

    @DrawableRes
    fun resolveSnapMode(mode: SnapMode): Int = when (mode) {
        SnapMode.EDGE -> R.drawable.ic_rectangle_on
        SnapMode.CENTER -> R.drawable.ic_center_on
    }

    @DrawableRes
    fun resolveHorizontal(enabled: Boolean, mode: SnapMode): Int = when (mode) {
        SnapMode.EDGE ->
            if (enabled) R.drawable.ic_horizontal_edge_on
            else R.drawable.ic_horizontal_edge_off
        SnapMode.CENTER ->
            if (enabled) R.drawable.ic_horizontal_center_on
            else R.drawable.ic_horizontal_center_off
    }

    @DrawableRes
    fun resolveVertical(enabled: Boolean, mode: SnapMode): Int = when (mode) {
        SnapMode.EDGE ->
            if (enabled) R.drawable.ic_vertical_edge_on
            else R.drawable.ic_vertical_edge_off
        SnapMode.CENTER ->
            if (enabled) R.drawable.ic_vertical_center_on
            else R.drawable.ic_vertical_center_off
    }
}
