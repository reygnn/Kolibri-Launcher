package com.github.reygnn.kolibri_launcher.domain.model

/**
 * User-facing setting for what lies **behind** the Kolibri wallpaper collage —
 * i.e. what shows through the launcher window's transparent regions (and, more
 * importantly, during the drawer→home rebuild gap, see
 * `WALLPAPER_DRAWER_HOME_REBUILD_SPEC`).
 *
 * The name describes the user's *intent*, not the window mechanism: the code
 * maps it onto an Activity-owned backdrop colour that outlives the
 * `HomeFragment` view lifecycle (transparent → system wallpaper shows via
 * `FLAG_SHOW_WALLPAPER`; opaque black → covers it). Keeping the opaque base
 * above the fragment is what stops the system wallpaper from flashing on every
 * drawer→home return — a bottom black *layer* inside the collage can't, because
 * it is torn down and rebuilt with the rest of the collage.
 *
 * - [SYSTEM_WALLPAPER] leaves the window transparent so the device's own
 *   wallpaper is the backdrop. This is the historical behaviour and the safe
 *   default: a fresh install (or a legacy backup with no value) keeps what
 *   users see today.
 * - [BLACK] paints an opaque black backdrop. Removes the need for a hand-built
 *   black bottom layer and cannot flash on drawer→home.
 */
enum class WallpaperBackdrop {
    SYSTEM_WALLPAPER,
    BLACK,
}
