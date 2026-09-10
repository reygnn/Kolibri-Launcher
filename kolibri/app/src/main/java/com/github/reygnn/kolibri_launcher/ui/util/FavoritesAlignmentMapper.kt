package com.github.reygnn.kolibri_launcher.ui.util

import android.view.Gravity
import com.github.reygnn.kolibri_launcher.domain.model.FavoritesAlignment

/**
 * Maps the domain [FavoritesAlignment] to the platform horizontal-gravity
 * constant used by [android.widget.Button.setGravity].
 *
 * The mapping is total (each domain value has exactly one platform
 * counterpart). Lives in `:app` so the domain layer can stay free of
 * `android.view.Gravity`. Pattern matches [WallpaperBlendModeMapper].
 *
 * The vertical component (`Gravity.CENTER_VERTICAL`) is added at the
 * call site in `HomeFavoritesAdapter` — this mapper only owns the
 * horizontal axis, which is what the user actually picks.
 */
fun FavoritesAlignment.toHorizontalGravity(): Int = when (this) {
    FavoritesAlignment.START -> Gravity.START
    FavoritesAlignment.CENTER -> Gravity.CENTER_HORIZONTAL
    FavoritesAlignment.END -> Gravity.END
}
