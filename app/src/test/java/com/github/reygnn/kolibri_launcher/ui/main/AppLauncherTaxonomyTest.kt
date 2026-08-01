package com.github.reygnn.kolibri_launcher.ui.main

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Context
import com.github.reygnn.kolibri_launcher.domain.model.AppInfo
import io.mockk.every
import io.mockk.mockk
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Pins the app-launch exception→result taxonomy — the four-category System-API
 * boundary in [runLaunchCatching] that [AppLauncherImpl] feeds with the real
 * `LauncherApps.startMainActivity` call. Before this test, the single most
 * important system call in the launcher had ZERO coverage: a regression that
 * swapped the catch order or mis-mapped an exception (e.g. SecurityException →
 * ComponentGone) would leave every [AppLaunchResultTest] assertion green while
 * either crashing on a tap or — worse — never mapping a genuinely uninstalled
 * app to [AppLaunchResult.ComponentGone], so the orphan-reconcile sweep that
 * cleans a stale swipe/favorite assignment pointing at the dead app never runs.
 *
 * Robolectric so the Android exception types (ActivityNotFoundException) are
 * real; the taxonomy itself is Android-free logic.
 */
@RunWith(RobolectricTestRunner::class)
class AppLauncherTaxonomyTest {

    @Test
    fun `successful launch maps to Launched`() {
        assertEquals(AppLaunchResult.Launched, runLaunchCatching { /* no throw */ })
    }

    @Test
    fun `ActivityNotFoundException maps to ComponentGone`() {
        val result = runLaunchCatching { throw ActivityNotFoundException("component gone") }
        assertEquals(AppLaunchResult.ComponentGone, result)
    }

    @Test
    fun `SecurityException maps to PermissionDenied`() {
        val result = runLaunchCatching { throw SecurityException("denied") }
        assertEquals(AppLaunchResult.PermissionDenied, result)
    }

    @Test
    fun `any other Throwable maps to Failed and preserves the cause`() {
        val boom = IllegalStateException("boom")
        val result = runLaunchCatching { throw boom }
        assertTrue(result is AppLaunchResult.Failed)
        assertSame(boom, (result as AppLaunchResult.Failed).cause)
    }

    @Test
    fun `OutOfMemoryError is caught as Failed, not propagated`() {
        // Throwable umbrella intentionally covers Errors: a launch must never
        // take the process down. Pins that the catch is Throwable, not Exception.
        val oom = OutOfMemoryError("oom")
        val result = runLaunchCatching { throw oom }
        assertTrue(result is AppLaunchResult.Failed)
        assertSame(oom, (result as AppLaunchResult.Failed).cause)
    }

    @Test
    fun `only a gone component ultimately triggers an orphan reconcile`() {
        // Composite guard: trace the real user-facing guarantee end to end —
        // ActivityNotFoundException is the ONLY launch outcome that reconciles.
        assertTrue(runLaunchCatching { throw ActivityNotFoundException("x") }.shouldReconcile)
        assertFalse(runLaunchCatching { /* success */ }.shouldReconcile)
        assertFalse(runLaunchCatching { throw SecurityException("x") }.shouldReconcile)
        assertFalse(runLaunchCatching { throw RuntimeException("x") }.shouldReconcile)
    }

    @Test
    fun `null LauncherApps service maps to Failed without touching the launch statics`() {
        val activity = mockk<Activity>()
        every { activity.getSystemService(Context.LAUNCHER_APPS_SERVICE) } returns null
        val appInfo = AppInfo(
            originalName = "X",
            displayName = "X",
            packageName = "com.example.x",
            className = "com.example.x.Main",
        )

        val result = AppLauncherImpl().launch(activity, appInfo)

        assertTrue(result is AppLaunchResult.Failed)
        assertTrue((result as AppLaunchResult.Failed).cause is IllegalStateException)
    }
}
