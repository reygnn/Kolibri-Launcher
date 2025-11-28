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

fun Long.coerceInSafe(min: Long, max: Long): Long {
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

fun Float.coerceAtMostSafe(max: Float): Float {
    // Bei NaN nehmen wir max, da wir "höchstens max" wollen.
    // Man könnte auch 0f oder min nehmen, aber max ist oft sicherer für Layout-Begrenzungen.
    // Eine übliche Strategie bei UI ist: Wenn NaN, nimm den Grenzwert.
    return if (this.isNaN()) max else this.coerceAtMost(max)
}

fun Double.coerceAtLeastSafe(min: Double): Double {
    return if (this.isNaN()) min else this.coerceAtLeast(min)
}

fun Long.coerceAtLeastSafe(min: Long): Long {
    return this.coerceAtLeast(min)
}

fun Long.coerceAtMostSafe(max: Long): Long {
    return this.coerceAtMost(max)
}

fun Double.coerceAtMostSafe(max: Double): Double {
    return if (this.isNaN()) max else this.coerceAtMost(max)
}