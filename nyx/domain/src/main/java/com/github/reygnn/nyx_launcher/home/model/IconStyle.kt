package com.github.reygnn.nyx_launcher.home.model

/**
 * How app icons are rendered launcher-wide (user preference, tri-state).
 *
 * - [COLOR]: the app's original icon, untouched.
 * - [MONOCHROME]: the adaptive icon's themed (monochrome) layer, tinted on the
 *   Nyx night disc. Apps that ship no monochrome layer fall back to [GRAYSCALE]
 *   (not colour), so the home stays visually uniform even for those apps.
 * - [GRAYSCALE]: the original icon desaturated (saturation 0) — works for every
 *   icon regardless of whether it provides a monochrome layer.
 */
enum class IconStyle { COLOR, MONOCHROME, GRAYSCALE }
