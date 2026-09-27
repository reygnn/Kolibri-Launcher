package com.github.reygnn.launcher.common.ui

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.ComponentName
import android.content.Context
import android.content.pm.LauncherApps
import com.github.reygnn.launcher.core.ComponentKey
import io.mockk.Runs
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.slot
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Pins the shared app-launch seam, co-located with the code it guards so BOTH launchers
 * rely on one test rather than a copy pinned in a single consumer's source set:
 *
 *  - the exception→result taxonomy in [runLaunchCatching] — the four-category System-API
 *    boundary [AppLauncherImpl] feeds with the real `LauncherApps.startMainActivity` call.
 *    A regression that swapped the catch order or mis-mapped an exception (e.g.
 *    SecurityException → ComponentGone) would crash on a tap, or never map a genuinely
 *    uninstalled app to [AppLaunchResult.ComponentGone] so the orphan-reconcile never runs.
 *  - [AppLauncherImpl] itself: the null-service fallback, and that it launches the given
 *    [ComponentKey] VERBATIM. The impl does NOT normalize the class name — callers pass a
 *    normalized key (Kolibri via `AppInfo.key`, Nyx via its enumerated tile key); the
 *    `AppInfo.key` leading-dot expansion is pinned separately by AppInfoTest (normalizedClassName).
 *
 * Robolectric so the Android types are real: `ComponentName` (its captured package/class are
 * asserted) and `ActivityNotFoundException`.
 */
@RunWith(RobolectricTestRunner::class)
class AppLauncherImplTest {

    // --- runLaunchCatching taxonomy (Android-free logic) ---

    @Test
    fun `successful launch maps to Launched`() {
        assertEquals(AppLaunchResult.Launched, runLaunchCatching { /* no throw */ })
    }

    @Test
    fun `ActivityNotFoundException maps to ComponentGone`() {
        assertEquals(AppLaunchResult.ComponentGone, runLaunchCatching { throw ActivityNotFoundException("gone") })
    }

    @Test
    fun `SecurityException maps to PermissionDenied`() {
        assertEquals(AppLaunchResult.PermissionDenied, runLaunchCatching { throw SecurityException("denied") })
    }

    @Test
    fun `any other Throwable maps to Failed and preserves the cause`() {
        val boom = IllegalStateException("boom")
        val result = runLaunchCatching { throw boom }
        assertTrue(result is AppLaunchResult.Failed)
        assertEquals(boom, (result as AppLaunchResult.Failed).cause)
    }

    @Test
    fun `an Error (OutOfMemoryError) maps to Failed too - the catch is Throwable, not Exception`() {
        // Restores the guard the deleted AppLauncherTaxonomyTest carried: the catch in
        // runLaunchCatching is `Throwable`, so an Error (not an Exception) escaping the launch —
        // e.g. OutOfMemoryError from startMainActivity — still maps to Failed rather than crashing
        // the launch. This is the ONLY taxonomy test that throws an Error, so a future narrowing of
        // that catch to `Exception` (which would miss OOM) turns it red instead of slipping through.
        val oom = OutOfMemoryError("boom")
        val result = runLaunchCatching { throw oom }
        assertTrue(result is AppLaunchResult.Failed)
        assertEquals(oom, (result as AppLaunchResult.Failed).cause)
    }

    // --- AppLauncherImpl ---

    @Test
    fun `null LauncherApps service maps to Failed without touching the launch statics`() {
        val activity = mockk<Activity>()
        every { activity.getSystemService(Context.LAUNCHER_APPS_SERVICE) } returns null

        val result = AppLauncherImpl().launch(activity, ComponentKey("com.example.x", "com.example.x.Main"))

        assertTrue(result is AppLaunchResult.Failed)
        assertTrue((result as AppLaunchResult.Failed).cause is IllegalStateException)
    }

    @Test
    fun `launch feeds the ComponentKey to startMainActivity verbatim`() {
        // The impl launches key.packageName / key.className AS GIVEN — no normalization here.
        // Regression guard: a future change that rewrote the component (or dropped a segment)
        // before startMainActivity would break launching.
        val launcherApps = mockk<LauncherApps>()
        val activity = mockk<Activity>()
        every { activity.getSystemService(Context.LAUNCHER_APPS_SERVICE) } returns launcherApps

        val component = slot<ComponentName>()
        every { launcherApps.startMainActivity(capture(component), any(), any(), any()) } just Runs

        val result = AppLauncherImpl().launch(activity, ComponentKey("com.example.x", "com.example.x.Main"))

        assertEquals(AppLaunchResult.Launched, result)
        assertEquals("com.example.x", component.captured.packageName)
        assertEquals("com.example.x.Main", component.captured.className)
    }
}
