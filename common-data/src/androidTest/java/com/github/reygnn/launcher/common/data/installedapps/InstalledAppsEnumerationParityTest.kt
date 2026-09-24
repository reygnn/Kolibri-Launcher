package com.github.reygnn.launcher.common.data.installedapps

import android.content.Context
import android.content.Intent
import android.content.pm.LauncherApps
import android.content.pm.PackageManager
import android.os.Process
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * ON-DEVICE parity guard for the enumeration swap
 * (docs/EXECUTION_SHARED_INSTALLED_APPS.md, delta §3).
 *
 * The one delta that CANNOT be pinned in the JVM suite: whether
 * `LauncherApps.getActivityList(null, myUserHandle())` returns the SAME set of
 * launchable components as the retired `PackageManager.queryIntentActivities(
 * ACTION_MAIN + CATEGORY_LAUNCHER)`. Mocking both APIs in a unit test proves
 * nothing — the divergence lives in the platform (Android 11+ package visibility,
 * suspended apps, profiles). So this is an instrumented test, run on the real
 * device/emulator matrix, matching the repo's "enumerator seam verified by
 * androidTest, not a JVM contract" posture (ADR marker on the loader).
 *
 * It is a SKELETON: the comparison is exact by default, which will legitimately
 * fail on some images (see the allow-list note). Before enabling in CI, run once
 * on each target image, inspect the diff, and either (a) confirm parity or
 * (b) capture the known, explained differences in [KNOWN_DIVERGENCE] with a reason
 * per package. An unexplained diff is a real behaviour change to investigate, not
 * a test to loosen.
 *
 * NOTE: `getActivityList` requires the caller to be able to query launcher
 * activities. On a device where the test app is NOT the default launcher this
 * still returns the primary user's launchable set; if an image restricts it,
 * document that here rather than deleting the assertion.
 *
 * VISIBILITY: the androidTest manifest (`src/androidTest/AndroidManifest.xml`)
 * grants `QUERY_ALL_PACKAGES`. Without it the PackageManager side would be
 * package-visibility filtered (Android 11+) while `getActivityList` is not, so
 * the two APIs would enumerate different universes and the diff below would be a
 * visibility artefact rather than a real behaviour change. With the grant, both
 * sides see the same launchable set, so a non-empty diff is a genuine divergence.
 */
@RunWith(AndroidJUnit4::class)
class InstalledAppsEnumerationParityTest {

    private lateinit var context: Context
    private lateinit var packageManager: PackageManager
    private lateinit var launcherApps: LauncherApps

    @Before
    fun setup() {
        context = ApplicationProvider.getApplicationContext()
        packageManager = context.packageManager
        launcherApps = context.getSystemService(Context.LAUNCHER_APPS_SERVICE) as LauncherApps
    }

    /** Old path: MAIN + LAUNCHER intent query, flattened to "pkg/class". */
    private fun packageManagerComponents(): Set<String> {
        val intent = Intent(Intent.ACTION_MAIN, null).apply {
            addCategory(Intent.CATEGORY_LAUNCHER)
        }
        return packageManager
            .queryIntentActivities(intent, PackageManager.ResolveInfoFlags.of(0))
            .mapNotNull { it.activityInfo }
            .map { "${it.packageName}/${it.name}" }
            .toSet()
    }

    /** New path: LauncherApps for the primary user, flattened to "pkg/class". */
    private fun launcherAppsComponents(): Set<String> =
        launcherApps.getActivityList(null, Process.myUserHandle())
            .map { "${it.componentName.packageName}/${it.componentName.className}" }
            .toSet()

    @Test
    fun launcherApps_enumeration_matches_packageManager() {
        val pm = packageManagerComponents()
        val la = launcherAppsComponents()

        // Neither API should be empty on a real image (there is always >= 1
        // launchable app); an empty side means the query itself is wrong.
        assertTrue("PackageManager returned no launchers", pm.isNotEmpty())
        assertTrue("LauncherApps returned no launchers", la.isNotEmpty())

        val onlyInPm = (pm - la) - KNOWN_DIVERGENCE
        val onlyInLa = (la - pm) - KNOWN_DIVERGENCE

        assertTrue(
            buildString {
                appendLine("Enumeration parity mismatch (investigate before shipping):")
                appendLine("  only via PackageManager: $onlyInPm")
                appendLine("  only via LauncherApps:   $onlyInLa")
                appendLine("If a difference is expected on this image, add it to")
                appendLine("KNOWN_DIVERGENCE with a per-package reason.")
            },
            onlyInPm.isEmpty() && onlyInLa.isEmpty(),
        )
    }

    companion object {
        /**
         * Components whose presence in only one API is understood and accepted on
         * the target image(s). EACH entry needs a comment with the reason (e.g. a
         * suspended stub, a synthetic settings alias). Empty by default: prove
         * parity first, document exceptions second.
         */
        private val KNOWN_DIVERGENCE: Set<String> = emptySet()
    }
}
