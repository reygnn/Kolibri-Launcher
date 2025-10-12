/*
    * Copyright (C) 2025 reygnn (Ulrich Kaufmann)
    *
    * This program is free software: you can redistribute it and/or modify
    * it under the terms of the GNU General Public License as published by
    * the Free Software Foundation, either version 3 of the License, or
    * (at your option) any later version.
    */

package com.github.reygnn.kolibri_launcher

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.drawable.Drawable
import androidx.core.graphics.createBitmap
import androidx.core.graphics.get
import kotlin.math.pow

object TextColorCalculator {

    fun getOptimalTextColor(color: Int): Int {
        val luminance = calculateRelativeLuminance(color)
        // High luminance (bright) = use black text
        // Low luminance (dark) = use white text
        return if (luminance > 0.5) {
            0xFF000000.toInt()  // Black: -16777216
        } else {
            0xFFFFFFFF.toInt()  // White: -1
        }
    }

    fun getOptimalTextColorForWallpaper(wallpaper: Drawable): Int {
        return try {
            val bitmap = createBitmap(50, 50, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(bitmap)
            wallpaper.setBounds(0, 0, 50, 50)
            wallpaper.draw(canvas)
            val averageColor = calculateAverageColor(bitmap)
            bitmap.recycle()
            getOptimalTextColor(averageColor)
        } catch (e: Exception) {
            TimberWrapper.silentError(e, "Error analyzing wallpaper")
            0xFFFFFFFF.toInt()  // White
        }
    }

    private fun calculateRelativeLuminance(color: Int): Double {
        // Extract RGB using bit shifts (platform-independent, works in unit tests)
        val redChannel = (color shr 16) and 0xFF
        val greenChannel = (color shr 8) and 0xFF
        val blueChannel = color and 0xFF

        fun srgbToLinear(channel: Int): Double {
            val v = channel / 255.0
            return if (v <= 0.04045) {
                v / 12.92
            } else {
                ((v + 0.055) / 1.055).pow(2.4)
            }
        }

        val r = srgbToLinear(redChannel)
        val g = srgbToLinear(greenChannel)
        val b = srgbToLinear(blueChannel)

        // Relative luminance formula (ITU-R BT.709)
        return 0.2126 * r + 0.7152 * g + 0.0722 * b
    }

    private fun calculateAverageColor(bitmap: Bitmap): Int {
        var redSum = 0L
        var greenSum = 0L
        var blueSum = 0L
        var pixelCount = 0
        val width = bitmap.width
        val height = bitmap.height

        for (x in 0 until width) {
            for (y in 0 until height) {
                val pixel = bitmap[x, y]
                redSum += Color.red(pixel)
                greenSum += Color.green(pixel)
                blueSum += Color.blue(pixel)
                pixelCount++
            }
        }

        return Color.rgb(
            (redSum / pixelCount).toInt(),
            (greenSum / pixelCount).toInt(),
            (blueSum / pixelCount).toInt()
        )
    }
}