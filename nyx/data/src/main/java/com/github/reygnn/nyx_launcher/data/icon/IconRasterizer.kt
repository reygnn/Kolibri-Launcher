package com.github.reygnn.nyx_launcher.data.icon

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.drawable.AdaptiveIconDrawable
import android.graphics.drawable.Drawable
import javax.inject.Inject

/**
 * Turns a resolved [Drawable] into a square [Bitmap]. `AdaptiveIconDrawable.draw()`
 * composites its layers, so one path handles adaptive AND legacy icons. Never
 * recycles (ICL-INV-8).
 */
class IconRasterizer @Inject constructor() {

    fun rasterize(drawable: Drawable, sizePx: Int): Bitmap {
        val bitmap = Bitmap.createBitmap(sizePx, sizePx, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        drawable.setBounds(0, 0, sizePx, sizePx)
        drawable.draw(canvas)
        return bitmap
    }

    /**
     * Themed/monochrome rendering: the adaptive icon's monochrome layer tinted
     * [foreground] on a [background] disc. Apps without a monochrome layer fall
     * back to the normal icon. `getMonochrome()` is API 33+ (minSdk 36 → always).
     */
    fun rasterizeMonochrome(drawable: Drawable, sizePx: Int, background: Int, foreground: Int): Bitmap {
        val mono = (drawable as? AdaptiveIconDrawable)?.monochrome ?: return rasterize(drawable, sizePx)
        val bitmap = Bitmap.createBitmap(sizePx, sizePx, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        canvas.drawCircle(sizePx / 2f, sizePx / 2f, sizePx / 2f, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = background })
        mono.mutate()
        mono.setTint(foreground)
        mono.setBounds(0, 0, sizePx, sizePx)
        mono.draw(canvas)
        return bitmap
    }
}
