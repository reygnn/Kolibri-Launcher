package com.github.reygnn.kolibri_launcher.core

/*
 * Copyright (C) 2025 reygnn (Ulrich Kaufmann)
 */

/**
 * Erweiterungen für Zahlentypen zur Erhöhung der Anwendungssicherheit.
 */

fun Float.coerceInSafe(min: Float, max: Float): Float {
    return when {
        this.isNaN() -> min
        this.isInfinite() -> if (this > 0) max else min
        else -> this.coerceIn(min, max)
    }
}

fun Double.coerceInSafe(min: Double, max: Double): Double {
    return when {
        this.isNaN() -> min
        this.isInfinite() -> if (this > 0) max else min
        else -> this.coerceIn(min, max)
    }
}

fun Int.coerceInSafe(min: Int, max: Int): Int {
    return this.coerceIn(min, max)
}

fun Float.coerceAtLeastSafe(min: Float): Float {
    return if (this.isNaN()) min else this.coerceAtLeast(min)
}

fun Int.coerceAtLeastSafe(min: Int): Int {
    return this.coerceAtLeast(min)
}

fun Int.coerceAtMostSafe(max: Int): Int {
    return this.coerceAtMost(max)
}

fun Double.coerceAtLeastSafe(min: Double): Double {
    return if (this.isNaN()) min else this.coerceAtLeast(min)
}