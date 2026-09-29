package com.github.reygnn.launcher.common.ui

import android.content.ActivityNotFoundException
import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * Pinning the pure launch-failure decision behind [startActivitySafely]: only the
 * two optional-app failures collapse to a toast; everything else (programmer
 * errors, OOM) must be reported as "unexpected" so the caller rethrows it.
 */
class SystemLaunchTest {

    @Test
    fun `ActivityNotFoundException is an expected optional-app failure`() {
        // The primary case: no clock / calendar / battery app on this ROM.
        assertThat(isExpectedSystemLaunchFailure(ActivityNotFoundException("no handler"))).isTrue()
    }

    @Test
    fun `SecurityException is an expected optional-app failure`() {
        assertThat(isExpectedSystemLaunchFailure(SecurityException("guarded"))).isTrue()
    }

    @Test
    fun `IllegalStateException is not expected — it must propagate`() {
        assertThat(isExpectedSystemLaunchFailure(IllegalStateException("bug"))).isFalse()
    }

    @Test
    fun `IllegalArgumentException is not expected`() {
        assertThat(isExpectedSystemLaunchFailure(IllegalArgumentException("bug"))).isFalse()
    }

    @Test
    fun `OutOfMemoryError is not expected — a Throwable catch must not swallow it`() {
        assertThat(isExpectedSystemLaunchFailure(OutOfMemoryError())).isFalse()
    }

    @Test
    fun `a bare RuntimeException is not expected`() {
        assertThat(isExpectedSystemLaunchFailure(RuntimeException("bug"))).isFalse()
    }
}
