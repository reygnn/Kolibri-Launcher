package com.github.reygnn.launcher.common.ui

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pins the reconcile decision carried by [AppLaunchResult.shouldReconcile]: only a
 * genuinely-gone component triggers the orphan-reconcile reload. Guards against a
 * future change that reconciles on a permission denial or an unknown failure —
 * which don't imply an uninstall — or drops it entirely.
 */
class AppLaunchResultTest {

    @Test
    fun `only ComponentGone triggers a reconcile`() {
        assertTrue(AppLaunchResult.ComponentGone.shouldReconcile)

        assertFalse(AppLaunchResult.Launched.shouldReconcile)
        assertFalse(AppLaunchResult.PermissionDenied.shouldReconcile)
        assertFalse(AppLaunchResult.Failed(RuntimeException("boom")).shouldReconcile)
    }
}
