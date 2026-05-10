package com.github.reygnn.kolibri_launcher.domain.model

/**
 * User-facing setting for the AppDrawer surface style.
 *
 * - [AUTO] follows a wallpaper-luminance classification (set up by
 *   `ResolveAppDrawerSurfaceUseCase`). The classifier itself ships in
 *   the follow-up commit; until then, [AUTO] resolves to [DARK] —
 *   regression-safe, matches the pre-feature behaviour.
 * - [LIGHT] / [DARK] are explicit overrides that ignore the wallpaper
 *   signal entirely.
 */
enum class AppDrawerMode {
    AUTO,
    LIGHT,
    DARK,
}
