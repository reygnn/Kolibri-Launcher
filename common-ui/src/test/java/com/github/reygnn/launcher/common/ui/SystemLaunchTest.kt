package com.github.reygnn.launcher.common.ui

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pinning the pure launch-failure decision behind [startActivitySafely]: only the
 * two optional-app failures collapse to a toast; everything else (programmer
 * errors, OOM) must be reported as "unexpected" so the caller rethrows it.
 */
class SystemLaunchTest {

    @Test
    fun `SecurityException is an expected optional-app failure`() {
        assertTrue(isExpectedSystemLaunchFailure(SecurityException("guarded")))
    }

    @Test
    fun `IllegalStateException is not expected — it must propagate`() {
        assertFalse(isExpectedSystemLaunchFailure(IllegalStateException("bug")))
    }

    @Test
    fun `IllegalArgumentException is not expected`() {
        assertFalse(isExpectedSystemLaunchFailure(IllegalArgumentException("bug")))
    }

    @Test
    fun `OutOfMemoryError is not expected — a Throwable catch must not swallow it`() {
        assertFalse(isExpectedSystemLaunchFailure(OutOfMemoryError()))
    }

    @Test
    fun `a bare RuntimeException is not expected`() {
        assertFalse(isExpectedSystemLaunchFailure(RuntimeException("bug")))
    }
}
