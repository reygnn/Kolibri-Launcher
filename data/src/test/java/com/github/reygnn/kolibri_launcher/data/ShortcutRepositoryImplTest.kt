package com.github.reygnn.kolibri_launcher.data

import android.content.Context
import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.pm.LauncherApps
import android.content.pm.PackageManager
import android.content.pm.ResolveInfo
import android.content.pm.ShortcutInfo
import com.github.reygnn.kolibri_launcher.rule.TimberRule
import io.mockk.MockKAnnotations
import io.mockk.every
import io.mockk.impl.annotations.MockK
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.test.runTest
import org.junit.Assert
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

class ShortcutRepositoryImplTest {

    @get:Rule
    val timberRule = TimberRule()

    @MockK
    private lateinit var context: Context
    @MockK
    private lateinit var launcherApps: LauncherApps
    @MockK
    private lateinit var packageManager: PackageManager

    private lateinit var shortcutManager: ShortcutRepositoryImpl

    @Before
    fun setup() {
        MockKAnnotations.init(this)

        every { context.packageManager } returns packageManager
        every { context.packageName } returns "com.github.reygnn.kolibri_launcher"

        // Mock isDefaultLauncher() — reale Objekte für ActivityInfo (direkte Java-Felder)
        val resolveInfo = ResolveInfo()
        val activityInfo = ActivityInfo()
        activityInfo.packageName = "com.github.reygnn.kolibri_launcher"
        resolveInfo.activityInfo = activityInfo

        every { packageManager.resolveActivity(any<Intent>(), eq(0)) } returns resolveInfo
        every { context.getSystemService(Context.LAUNCHER_APPS_SERVICE) } returns launcherApps

        shortcutManager = ShortcutRepositoryImpl(context)
    }

    // ========== EXISTING TESTS ==========

    @Test
    fun `getShortcutsForPackage - when successful - returns list of shortcuts`() {
        val fakeShortcut1 = mockk<ShortcutInfo> {
            every { id } returns "id1"
            every { `package` } returns "com.test.app"
            every { shortLabel } returns "Label 1"
        }
        val fakeShortcut2 = mockk<ShortcutInfo> {
            every { id } returns "id2"
            every { `package` } returns "com.test.app"
            every { shortLabel } returns "Label 2"
        }
        every { launcherApps.getShortcuts(any(), any()) } returns listOf(fakeShortcut1, fakeShortcut2)

        val result = shortcutManager.getShortcutsForPackage("com.test.app")

        assertEquals(2, result.size)
        assertEquals("id1", result[0].id)
        assertEquals("id2", result[1].id)
    }

    @Test
    fun `getShortcutsForPackage - when SecurityException occurs - returns empty list`() {
        every { launcherApps.getShortcuts(any(), any()) } throws SecurityException("Permission denied!")

        Assert.assertTrue(shortcutManager.getShortcutsForPackage("com.test.app").isEmpty())
    }

    @Test
    fun `getShortcutsForPackage - when other Exception occurs - returns empty list`() {
        every { launcherApps.getShortcuts(any(), any()) } throws RuntimeException("Something went wrong")

        Assert.assertTrue(shortcutManager.getShortcutsForPackage("com.test.app").isEmpty())
    }

    @Test
    fun `getShortcutsForPackage - when system returns null - returns empty list`() {
        every { launcherApps.getShortcuts(any(), any()) } returns null

        Assert.assertTrue(shortcutManager.getShortcutsForPackage("com.test.app").isEmpty())
    }

    // ========== NEW CRASH-RESISTANCE TESTS ==========

    @Test
    fun `getShortcutsForPackage - when IllegalStateException - returns empty list`() {
        every { launcherApps.getShortcuts(any(), any()) } throws IllegalStateException("LauncherApps not initialized")

        Assert.assertTrue(shortcutManager.getShortcutsForPackage("com.test.app").isEmpty())
    }

    @Test
    fun `getShortcutsForPackage - when IllegalArgumentException - returns empty list`() {
        every { launcherApps.getShortcuts(any(), any()) } throws IllegalArgumentException("Invalid package name")

        Assert.assertTrue(shortcutManager.getShortcutsForPackage("com.test.app").isEmpty())
    }

    @Test
    fun `getShortcutsForPackage - with empty package name - returns empty list`() {
        Assert.assertTrue(shortcutManager.getShortcutsForPackage("").isEmpty())
    }

    @Test
    fun `getShortcutsForPackage - with blank package name - returns empty list`() {
        Assert.assertTrue(shortcutManager.getShortcutsForPackage("   ").isEmpty())
    }

    @Test
    fun `getShortcutsForPackage - when LauncherApps service is null - returns empty list`() {
        every { context.getSystemService(Context.LAUNCHER_APPS_SERVICE) } returns null

        val managerWithNullService = ShortcutRepositoryImpl(context)
        Assert.assertTrue(managerWithNullService.getShortcutsForPackage("com.test.app").isEmpty())
    }

    @Test
    fun `getShortcutsForPackage - with malformed package name - handles gracefully`() {
        every { launcherApps.getShortcuts(any(), any()) } returns emptyList()

        assertNotNull(shortcutManager.getShortcutsForPackage("not.a.valid..package...name"))
    }

    @Test
    fun `getShortcutsForPackage - called multiple times - returns consistent results`() {
        val fakeShortcuts = listOf(mockk<ShortcutInfo>(relaxed = true))
        every { launcherApps.getShortcuts(any(), any()) } returns fakeShortcuts

        val result1 = shortcutManager.getShortcutsForPackage("com.test.app")
        val result2 = shortcutManager.getShortcutsForPackage("com.test.app")
        val result3 = shortcutManager.getShortcutsForPackage("com.test.app")

        assertEquals(result1.size, result2.size)
        assertEquals(result2.size, result3.size)
    }

    @Test
    fun `getShortcutsForPackage - with very large shortcut list - handles correctly`() {
        val largeShortcutList = (1..100).map { mockk<ShortcutInfo>(relaxed = true) }
        every { launcherApps.getShortcuts(any(), any()) } returns largeShortcutList

        assertEquals(100, shortcutManager.getShortcutsForPackage("com.test.app").size)
    }

    @Test
    fun `getShortcutsForPackage - when NullPointerException inside API - returns empty list`() {
        every { launcherApps.getShortcuts(any(), any()) } throws NullPointerException("Internal API error")

        Assert.assertTrue(shortcutManager.getShortcutsForPackage("com.test.app").isEmpty())
    }

    @Test
    fun `getShortcutsForPackage - for different packages - returns different results`() {
        val shortcuts1 = listOf(mockk<ShortcutInfo>(relaxed = true))
        val shortcuts2 = listOf(mockk<ShortcutInfo>(relaxed = true), mockk<ShortcutInfo>(relaxed = true))

        every { launcherApps.getShortcuts(any(), any()) } returnsMany listOf(shortcuts1, shortcuts2)

        assertEquals(1, shortcutManager.getShortcutsForPackage("com.app1").size)
        assertEquals(2, shortcutManager.getShortcutsForPackage("com.app2").size)
    }

    @Test
    fun `getShortcutsForPackage - when first call fails then succeeds - handles correctly`() {
        val fakeShortcuts = listOf(mockk<ShortcutInfo>(relaxed = true))

        every { launcherApps.getShortcuts(any(), any()) } throws SecurityException("First call fails") andThen fakeShortcuts

        Assert.assertTrue(shortcutManager.getShortcutsForPackage("com.test.app").isEmpty())
        assertEquals(1, shortcutManager.getShortcutsForPackage("com.test.app").size)
    }

    @Test
    fun `getShortcutsForPackage - with special characters in package name - handles correctly`() {
        every { launcherApps.getShortcuts(any(), any()) } returns emptyList()

        assertNotNull(shortcutManager.getShortcutsForPackage("com.test-app_name.special"))
    }

    // ========== DEFAULT LAUNCHER CHECK ==========

    @Test
    fun `getShortcutsForPackage - when NOT default launcher - returns empty list without calling service`() {
        // Reale Objekte für die ActivityInfo — direkte Java-Feldkonfiguration
        val otherResolveInfo = ResolveInfo()
        val otherActivityInfo = ActivityInfo()
        otherActivityInfo.packageName = "com.other.launcher"
        otherResolveInfo.activityInfo = otherActivityInfo

        every { packageManager.resolveActivity(any<Intent>(), eq(0)) } returns otherResolveInfo

        val result = shortcutManager.getShortcutsForPackage("com.test.app")

        Assert.assertTrue("Should return empty list when not default launcher", result.isEmpty())
        verify(exactly = 0) { launcherApps.getShortcuts(any(), any()) }
    }

    // ========== PURGE TEST ==========

    @Test
    fun `purgeRepository - does nothing and does not crash`() = runTest {
        shortcutManager.purgeRepository()
    }
}