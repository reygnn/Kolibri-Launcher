package com.github.reygnn.nyx_launcher.data.icon

import android.content.ComponentName
import android.content.Context
import android.content.pm.LauncherActivityInfo
import android.content.pm.LauncherApps
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.Drawable
import com.github.reygnn.launcher.core.ComponentKey
import com.github.reygnn.nyx_launcher.home.model.IconRef
import com.github.reygnn.nyx_launcher.home.model.IconStyle
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.test.runTest
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Robolectric (only for [android.os.Process.myUserHandle]): resolve + variant
 * dispatch of [LauncherAppsIconSource] over a mocked [LauncherApps]/[Context].
 * Pins the activity-icon-vs-application-icon fallback and the monochrome branch;
 * the real system resolve is androidTest.
 */
@RunWith(RobolectricTestRunner::class)
class LauncherAppsIconSourceTest {

    private val launcherApps = mockk<LauncherApps>()
    private val packageManager = mockk<PackageManager>()
    private val rasterizer = mockk<IconRasterizer>()
    private val context = mockk<Context> {
        every { getSystemService(Context.LAUNCHER_APPS_SERVICE) } returns launcherApps
        every { packageManager } returns this@LauncherAppsIconSourceTest.packageManager
    }

    private val output = Bitmap.createBitmap(1, 1, Bitmap.Config.ARGB_8888)
    private val source = LauncherAppsIconSource(context, rasterizer)

    private fun activityInfo(pkg: String, cls: String, icon: Drawable) =
        mockk<LauncherActivityInfo> {
            every { componentName } returns ComponentName(pkg, cls)
            every { getIcon(0) } returns icon
        }

    @Test
    fun resolves_the_activity_icon_and_rasterizes_it_normal() = runTest {
        val activityIcon = ColorDrawable(Color.RED)
        every { launcherApps.getActivityList("com.foo", any()) } returns
            listOf(activityInfo("com.foo", "com.foo.Main", activityIcon))
        every { rasterizer.rasterize(activityIcon, 64) } returns output

        source.load(IconRef.System(ComponentKey("com.foo", "com.foo.Main")), sizePx = 64, style = IconStyle.COLOR)

        verify(exactly = 1) { rasterizer.rasterize(activityIcon, 64) }
        verify(exactly = 0) { rasterizer.rasterizeMonochrome(any(), any(), any(), any()) }
        verify(exactly = 0) { rasterizer.rasterizeGrayscale(any(), any()) }
    }

    @Test
    fun monochrome_request_rasterizes_with_the_nyx_night_palette() = runTest {
        val activityIcon = ColorDrawable(Color.RED)
        every { launcherApps.getActivityList("com.foo", any()) } returns
            listOf(activityInfo("com.foo", "com.foo.Main", activityIcon))
        every {
            rasterizer.rasterizeMonochrome(activityIcon, 64, 0xFF1C1B22.toInt(), 0xFFE6E1E5.toInt())
        } returns output

        source.load(IconRef.System(ComponentKey("com.foo", "com.foo.Main")), sizePx = 64, style = IconStyle.MONOCHROME)

        verify(exactly = 1) {
            rasterizer.rasterizeMonochrome(activityIcon, 64, 0xFF1C1B22.toInt(), 0xFFE6E1E5.toInt())
        }
        verify(exactly = 0) { rasterizer.rasterize(any(), any()) }
    }

    @Test
    fun grayscale_request_rasterizes_grayscale() = runTest {
        val activityIcon = ColorDrawable(Color.RED)
        every { launcherApps.getActivityList("com.foo", any()) } returns
            listOf(activityInfo("com.foo", "com.foo.Main", activityIcon))
        every { rasterizer.rasterizeGrayscale(activityIcon, 64) } returns output

        source.load(IconRef.System(ComponentKey("com.foo", "com.foo.Main")), sizePx = 64, style = IconStyle.GRAYSCALE)

        verify(exactly = 1) { rasterizer.rasterizeGrayscale(activityIcon, 64) }
        verify(exactly = 0) { rasterizer.rasterize(any(), any()) }
        verify(exactly = 0) { rasterizer.rasterizeMonochrome(any(), any(), any(), any()) }
    }

    @Test
    fun falls_back_to_the_application_icon_when_no_matching_activity() = runTest {
        val appIcon = ColorDrawable(Color.BLUE)
        // No activity whose className matches the key ⇒ info == null ⇒ fallback path.
        every { launcherApps.getActivityList("com.foo", any()) } returns emptyList()
        every { packageManager.getApplicationIcon("com.foo") } returns appIcon
        every { rasterizer.rasterize(appIcon, 64) } returns output

        source.load(IconRef.System(ComponentKey("com.foo", "com.foo.Main")), sizePx = 64, style = IconStyle.COLOR)

        verify(exactly = 1) { packageManager.getApplicationIcon("com.foo") }
        verify(exactly = 1) { rasterizer.rasterize(appIcon, 64) }
    }

    @Test
    fun falls_back_when_an_activity_exists_but_class_name_differs() = runTest {
        val appIcon = ColorDrawable(Color.GREEN)
        every { launcherApps.getActivityList("com.foo", any()) } returns
            listOf(activityInfo("com.foo", "com.foo.Other", ColorDrawable(Color.RED)))
        every { packageManager.getApplicationIcon("com.foo") } returns appIcon
        every { rasterizer.rasterize(appIcon, 64) } returns output

        source.load(IconRef.System(ComponentKey("com.foo", "com.foo.Main")), sizePx = 64, style = IconStyle.COLOR)

        verify(exactly = 1) { rasterizer.rasterize(appIcon, 64) }
    }

    @Test
    fun pack_ref_resolves_through_the_same_activity_lookup() = runTest {
        val activityIcon = ColorDrawable(Color.RED)
        every { launcherApps.getActivityList("com.pack", any()) } returns
            listOf(activityInfo("com.pack", "com.pack.Main", activityIcon))
        every { rasterizer.rasterize(activityIcon, 96) } returns output

        source.load(
            IconRef.Pack(ComponentKey("com.pack", "com.pack.Main"), packId = "some.pack"),
            sizePx = 96,
            style = IconStyle.COLOR,
        )

        verify(exactly = 1) { rasterizer.rasterize(activityIcon, 96) }
    }
}
