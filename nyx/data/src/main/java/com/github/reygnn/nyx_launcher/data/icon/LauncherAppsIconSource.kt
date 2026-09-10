package com.github.reygnn.nyx_launcher.data.icon

import android.content.Context
import android.content.pm.LauncherApps
import android.graphics.Bitmap
import android.graphics.drawable.Drawable
import android.os.Process
import com.github.reygnn.nyx_launcher.home.model.IconRef
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject

/**
 * Production [IconSource]: resolves the activity icon via [LauncherApps] (v1:
 * primary user; packs fall back to the system icon) and rasterizes it, themed or
 * normal. Runs on the caller's IO dispatcher.
 */
class LauncherAppsIconSource @Inject constructor(
    @ApplicationContext private val context: Context,
    private val rasterizer: IconRasterizer,
) : IconSource {

    private val launcherApps =
        context.getSystemService(Context.LAUNCHER_APPS_SERVICE) as LauncherApps

    override suspend fun load(ref: IconRef, sizePx: Int, monochrome: Boolean): Bitmap {
        val drawable = resolveDrawable(ref)
        return if (monochrome) {
            rasterizer.rasterizeMonochrome(drawable, sizePx, MONO_BACKGROUND, MONO_FOREGROUND)
        } else {
            rasterizer.rasterize(drawable, sizePx)
        }
    }

    private fun resolveDrawable(ref: IconRef): Drawable {
        val key = when (ref) {
            is IconRef.System -> ref.key
            is IconRef.Pack -> ref.key
        }
        val info = launcherApps
            .getActivityList(key.packageName, Process.myUserHandle())
            .firstOrNull { it.componentName.className == key.className }
        return info?.getIcon(0) ?: context.packageManager.getApplicationIcon(key.packageName)
    }

    private companion object {
        // Nyx night palette: dark disc, light glyph.
        const val MONO_BACKGROUND = 0xFF1C1B22.toInt()
        const val MONO_FOREGROUND = 0xFFE6E1E5.toInt()
    }
}
