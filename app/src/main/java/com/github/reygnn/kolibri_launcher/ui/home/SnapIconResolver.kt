package com.github.reygnn.kolibri_launcher.ui.home

import androidx.annotation.DrawableRes
import com.github.reygnn.kolibri_launcher.R

/**
 * Pure Kotlin enum, frei von Android-View-Abhängigkeiten.
 *
 * ZoomableImageView.SnapMode ist weiterhin als nested Enum im View definiert.
 * HomeFragment konvertiert zwischen den beiden in einer kleinen when-Funktion
 * (siehe HomeFragment.toIconSnapMode). Längerfristig sollte ZoomableImageView
 * auf dieses Top-Level-Enum migriert werden.
 *
 * Separat gehalten, damit SnapIconResolver ohne Android-Class-Loading testbar ist.
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
