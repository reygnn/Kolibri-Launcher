package com.github.reygnn.kolibri_launcher.ui.main

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * Pins the §25 reconcile decision carried by [AppLaunchResult.shouldReconcile]:
 * only a genuinely-gone component triggers the orphan-reconcile reload.
 *
 * This is the guard the review asked for. It cannot live in a Robolectric test
 * (Robolectric's `ShadowLauncherApps` doesn't implement `startMainActivity`, so
 * the real launch can't be made to throw `ActivityNotFoundException`), so the
 * decision was lifted onto the typed result and is pinned here on the JVM.
 * Guards against a future change that reconciles on a permission denial or an
 * unknown failure — which don't imply an uninstall — or drops it entirely.
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
