package com.github.reygnn.kolibri_launcher.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pins the defensive NaN / Infinity branches of the safe-coerce helpers.
 *
 * The `Float.coerceInSafe` branches are already covered end-to-end by
 * BackupSerializerNamingAndInfinityTest. The gap this file closes: the `Double`
 * overloads and the `coerceAtLeastSafe` NaN branch (Float and Double) had no direct
 * test — yet [timeWeightedUsageScore] (UsageScore.kt) leans on exactly these guards
 * for its "result is always finite" guarantee, so an unguarded branch here would be
 * a hole in that safety net that nothing else catches.
 */
class CoerceExtensionsTest {

    // ---- Double.coerceInSafe -------------------------------------------------

    @Test fun `Double coerceInSafe maps NaN to min`() {
        assertEquals(0.0, Double.NaN.coerceInSafe(0.0, 1.0), 0.0)
    }

    @Test fun `Double coerceInSafe maps positive infinity to max`() {
        assertEquals(1.0, Double.POSITIVE_INFINITY.coerceInSafe(0.0, 1.0), 0.0)
    }

    @Test fun `Double coerceInSafe maps negative infinity to min`() {
        assertEquals(0.0, Double.NEGATIVE_INFINITY.coerceInSafe(0.0, 1.0), 0.0)
    }

    @Test fun `Double coerceInSafe clamps a finite out-of-range value`() {
        assertEquals(1.0, 9.0.coerceInSafe(0.0, 1.0), 0.0)
        assertEquals(0.0, (-9.0).coerceInSafe(0.0, 1.0), 0.0)
        assertEquals(0.5, 0.5.coerceInSafe(0.0, 1.0), 0.0)
    }

    // ---- Double.coerceAtLeastSafe -------------------------------------------

    @Test fun `Double coerceAtLeastSafe maps NaN to min`() {
        assertEquals(0.0, Double.NaN.coerceAtLeastSafe(0.0), 0.0)
    }

    @Test fun `Double coerceAtLeastSafe raises a below-min value and keeps an above-min one`() {
        assertEquals(0.0, (-3.0).coerceAtLeastSafe(0.0), 0.0)
        assertEquals(5.0, 5.0.coerceAtLeastSafe(0.0), 0.0)
    }

    @Test fun `Double coerceAtLeastSafe passes positive infinity through (not NaN, guard does not fire)`() {
        assertTrue(Double.POSITIVE_INFINITY.coerceAtLeastSafe(0.0).isInfinite())
    }

    // ---- Float.coerceAtLeastSafe --------------------------------------------

    @Test fun `Float coerceAtLeastSafe maps NaN to min`() {
        assertEquals(0.0f, Float.NaN.coerceAtLeastSafe(0.0f), 0.0f)
    }

    @Test fun `Float coerceAtLeastSafe raises a below-min value`() {
        assertEquals(0.0f, (-3.0f).coerceAtLeastSafe(0.0f), 0.0f)
    }

    // ---- Int overloads (total; no NaN/Infinity possible, but pin the contract) --

    @Test fun `Int coerce helpers behave as plain coerce`() {
        assertEquals(5, 9.coerceInSafe(0, 5))
        assertEquals(0, (-2).coerceAtLeastSafe(0))
        assertEquals(5, 9.coerceAtMostSafe(5))
    }
}
