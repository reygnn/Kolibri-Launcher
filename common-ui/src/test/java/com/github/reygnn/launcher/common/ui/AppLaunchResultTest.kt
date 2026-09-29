package com.github.reygnn.launcher.common.ui

import com.google.common.truth.Truth.assertThat
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
        assertThat(AppLaunchResult.ComponentGone.shouldReconcile).isTrue()

        assertThat(AppLaunchResult.Launched.shouldReconcile).isFalse()
        assertThat(AppLaunchResult.PermissionDenied.shouldReconcile).isFalse()
        assertThat(AppLaunchResult.Failed(RuntimeException("boom")).shouldReconcile).isFalse()
    }
}
