package com.github.reygnn.launcher.common.ui.wallpaper

import javax.inject.Qualifier

/**
 * Qualifies the `@style` resource id ([Int]) each app supplies for the off-screen
 * flatten view. [WallpaperFlattener] builds a detached [ZoomableImageView]
 * (an AppCompat widget) which must run under a Theme.AppCompat descendant, else it
 * logs "can only be used with a Theme.AppCompat theme" + tint-resolution spam. Each
 * app provides its own theme (Kolibri: AppTheme; Nyx: Theme.Nyx) — the theme is
 * cosmetic here (the view only draws bitmaps via its matrix), so any AppCompat
 * descendant works.
 */
@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class WallpaperFlattenTheme
