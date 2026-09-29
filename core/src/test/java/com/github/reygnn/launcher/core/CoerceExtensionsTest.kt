package com.github.reygnn.launcher.core

import com.google.common.truth.Truth.assertThat
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
        assertThat(Double.NaN.coerceInSafe(0.0, 1.0)).isWithin(0.0).of(0.0)
    }

    @Test fun `Double coerceInSafe maps positive infinity to max`() {
        assertThat(Double.POSITIVE_INFINITY.coerceInSafe(0.0, 1.0)).isWithin(0.0).of(1.0)
    }

    @Test fun `Double coerceInSafe maps negative infinity to min`() {
        assertThat(Double.NEGATIVE_INFINITY.coerceInSafe(0.0, 1.0)).isWithin(0.0).of(0.0)
    }

    @Test fun `Double coerceInSafe clamps a finite out-of-range value`() {
        assertThat(9.0.coerceInSafe(0.0, 1.0)).isWithin(0.0).of(1.0)
        assertThat((-9.0).coerceInSafe(0.0, 1.0)).isWithin(0.0).of(0.0)
        assertThat(0.5.coerceInSafe(0.0, 1.0)).isWithin(0.0).of(0.5)
    }

    // ---- Double.coerceAtLeastSafe -------------------------------------------

    @Test fun `Double coerceAtLeastSafe maps NaN to min`() {
        assertThat(Double.NaN.coerceAtLeastSafe(0.0)).isWithin(0.0).of(0.0)
    }

    @Test fun `Double coerceAtLeastSafe raises a below-min value and keeps an above-min one`() {
        assertThat((-3.0).coerceAtLeastSafe(0.0)).isWithin(0.0).of(0.0)
        assertThat(5.0.coerceAtLeastSafe(0.0)).isWithin(0.0).of(5.0)
    }

    @Test fun `Double coerceAtLeastSafe passes positive infinity through (not NaN, guard does not fire)`() {
        assertThat(Double.POSITIVE_INFINITY.coerceAtLeastSafe(0.0).isInfinite()).isTrue()
    }

    // ---- Float.coerceAtLeastSafe --------------------------------------------

    @Test fun `Float coerceAtLeastSafe maps NaN to min`() {
        assertThat(Float.NaN.coerceAtLeastSafe(0.0f)).isWithin(0.0f).of(0.0f)
    }

    @Test fun `Float coerceAtLeastSafe raises a below-min value`() {
        assertThat((-3.0f).coerceAtLeastSafe(0.0f)).isWithin(0.0f).of(0.0f)
    }

    // ---- Int overloads (total; no NaN/Infinity possible, but pin the contract) --

    @Test fun `Int coerce helpers behave as plain coerce`() {
        assertThat(9.coerceInSafe(0, 5)).isEqualTo(5)
        assertThat((-2).coerceAtLeastSafe(0)).isEqualTo(0)
        assertThat(9.coerceAtMostSafe(5)).isEqualTo(5)
    }
}
