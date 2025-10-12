package com.github.reygnn.kolibri_launcher

import android.content.Context
import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.pm.LauncherApps
import android.content.pm.PackageManager
import android.content.pm.ResolveInfo
import android.content.pm.ShortcutInfo
import android.os.UserHandle
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.mockito.Mock
import org.mockito.junit.MockitoJUnit
import org.mockito.junit.MockitoRule
import org.mockito.kotlin.any
import org.mockito.kotlin.anyOrNull
import org.mockito.kotlin.doAnswer
import org.mockito.kotlin.eq
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

class ShortcutManagerTest {

    @get:Rule
    val mockitoRule: MockitoRule = MockitoJUnit.rule()

    @Mock private lateinit var context: Context
    @Mock private lateinit var launcherApps: LauncherApps
    @Mock private lateinit var packageManager: PackageManager

    private lateinit var shortcutManager: ShortcutManager

    @Before
    fun setup() {
        // Mock PackageManager
        whenever(context.packageManager).thenReturn(packageManager)
        whenever(context.packageName).thenReturn("com.github.reygnn.kolibri_launcher")

        // Mock isDefaultLauncher() to return true
        val resolveInfo = mock<ResolveInfo>()
        val activityInfo = mock<ActivityInfo>()
        activityInfo.packageName = "com.github.reygnn.kolibri_launcher"
        resolveInfo.activityInfo = activityInfo

        whenever(packageManager.resolveActivity(any<Intent>(), eq(0)))
            .thenReturn(resolveInfo)

        // Mock LauncherApps service
        whenever(context.getSystemService(Context.LAUNCHER_APPS_SERVICE)).thenReturn(launcherApps)

        shortcutManager = ShortcutManager(context)
    }

    // ========== EXISTING TESTS ==========

    @Test
    fun `getShortcutsForPackage - when successful - returns list of shortcuts`() {
        val packageName = "com.test.app"
        val fakeShortcuts = listOf(mock<ShortcutInfo>(), mock<ShortcutInfo>())

        whenever(launcherApps.getShortcuts(any(), anyOrNull<UserHandle>())).thenReturn(fakeShortcuts)

        val result = shortcutManager.getShortcutsForPackage(packageName)

        assertEquals(fakeShortcuts, result)
        assertEquals(2, result.size)
    }

    @Test
    fun `getShortcutsForPackage - when SecurityException occurs - returns empty list`() {
        val packageName = "com.test.app"
        whenever(launcherApps.getShortcuts(any(), any<UserHandle>())).thenThrow(SecurityException("Permission denied!"))

        val result = shortcutManager.getShortcutsForPackage(packageName)

        assertTrue(result.isEmpty())
    }

    @Test
    fun `getShortcutsForPackage - when other Exception occurs - returns empty list`() {
        val packageName = "com.test.app"
        whenever(launcherApps.getShortcuts(any(), any<UserHandle>())).thenThrow(RuntimeException("Something went wrong"))

        val result = shortcutManager.getShortcutsForPackage(packageName)

        assertTrue(result.isEmpty())
    }

    @Test
    fun `getShortcutsForPackage - when system returns null - returns empty list`() {
        val packageName = "com.test.app"
        whenever(launcherApps.getShortcuts(any(), any<UserHandle>())).thenReturn(null)

        val result = shortcutManager.getShortcutsForPackage(packageName)

        assertTrue(result.isEmpty())
    }

    // ========== NEW CRASH-RESISTANCE TESTS ==========

    @Test
    fun `getShortcutsForPackage - when IllegalStateException - returns empty list`() {
        val packageName = "com.test.app"
        whenever(launcherApps.getShortcuts(any(), anyOrNull<UserHandle>())).doAnswer {
            throw IllegalStateException("LauncherApps not initialized")
        }

        val result = shortcutManager.getShortcutsForPackage(packageName)

        assertTrue(result.isEmpty())
    }

    @Test
    fun `getShortcutsForPackage - when IllegalArgumentException - returns empty list`() {
        val packageName = "com.test.app"
        whenever(launcherApps.getShortcuts(any(), anyOrNull<UserHandle>())).doAnswer {
            throw IllegalArgumentException("Invalid package name")
        }

        val result = shortcutManager.getShortcutsForPackage(packageName)

        assertTrue(result.isEmpty())
    }

    @Test
    fun `getShortcutsForPackage - with empty package name - returns empty list`() {
        val result = shortcutManager.getShortcutsForPackage("")

        assertTrue(result.isEmpty())
    }

    @Test
    fun `getShortcutsForPackage - with blank package name - returns empty list`() {
        val result = shortcutManager.getShortcutsForPackage("   ")

        assertTrue(result.isEmpty())
    }

    @Test
    fun `getShortcutsForPackage - when LauncherApps service is null - returns empty list`() {
        whenever(context.getSystemService(Context.LAUNCHER_APPS_SERVICE)).thenReturn(null)

        val managerWithNullService = ShortcutManager(context)
        val result = managerWithNullService.getShortcutsForPackage("com.test.app")

        assertTrue(result.isEmpty())
    }

    @Test
    fun `getShortcutsForPackage - with malformed package name - handles gracefully`() {
        val malformedPackageName = "not.a.valid..package...name"
        whenever(launcherApps.getShortcuts(any(), anyOrNull<UserHandle>())).thenReturn(emptyList())

        val result = shortcutManager.getShortcutsForPackage(malformedPackageName)

        assertNotNull(result)
    }

    @Test
    fun `getShortcutsForPackage - called multiple times - returns consistent results`() {
        val packageName = "com.test.app"
        val fakeShortcuts = listOf(mock<ShortcutInfo>())
        whenever(launcherApps.getShortcuts(any(), anyOrNull<UserHandle>())).thenReturn(fakeShortcuts)

        val result1 = shortcutManager.getShortcutsForPackage(packageName)
        val result2 = shortcutManager.getShortcutsForPackage(packageName)
        val result3 = shortcutManager.getShortcutsForPackage(packageName)

        assertEquals(result1.size, result2.size)
        assertEquals(result2.size, result3.size)
    }

    @Test
    fun `getShortcutsForPackage - with very large shortcut list - handles correctly`() {
        val packageName = "com.test.app"
        val largeShortcutList = (1..100).map { mock<ShortcutInfo>() }
        whenever(launcherApps.getShortcuts(any(), anyOrNull<UserHandle>())).thenReturn(largeShortcutList)

        val result = shortcutManager.getShortcutsForPackage(packageName)

        assertEquals(100, result.size)
    }

    @Test
    fun `getShortcutsForPackage - when NullPointerException inside API - returns empty list`() {
        val packageName = "com.test.app"
        whenever(launcherApps.getShortcuts(any(), anyOrNull<UserHandle>())).doAnswer {
            throw NullPointerException("Internal API error")
        }

        val result = shortcutManager.getShortcutsForPackage(packageName)

        assertTrue(result.isEmpty())
    }

    @Test
    fun `getShortcutsForPackage - for different packages - returns different results`() {
        val package1 = "com.app1"
        val package2 = "com.app2"

        val shortcuts1 = listOf(mock<ShortcutInfo>())
        val shortcuts2 = listOf(mock<ShortcutInfo>(), mock<ShortcutInfo>())

        whenever(launcherApps.getShortcuts(any(), anyOrNull<UserHandle>()))
            .thenReturn(shortcuts1)
            .thenReturn(shortcuts2)

        val result1 = shortcutManager.getShortcutsForPackage(package1)
        val result2 = shortcutManager.getShortcutsForPackage(package2)

        assertEquals(1, result1.size)
        assertEquals(2, result2.size)
    }

    @Test
    fun `getShortcutsForPackage - when first call fails then succeeds - handles correctly`() {
        val packageName = "com.test.app"
        val fakeShortcuts = listOf(mock<ShortcutInfo>())

        whenever(launcherApps.getShortcuts(any(), anyOrNull<UserHandle>()))
            .thenThrow(SecurityException("First call fails"))
            .thenReturn(fakeShortcuts)

        val result1 = shortcutManager.getShortcutsForPackage(packageName)
        assertTrue(result1.isEmpty())

        val result2 = shortcutManager.getShortcutsForPackage(packageName)
        assertEquals(1, result2.size)
    }

    @Test
    fun `getShortcutsForPackage - with special characters in package name - handles correctly`() {
        val specialPackageName = "com.test-app_name.special"
        whenever(launcherApps.getShortcuts(any(), anyOrNull<UserHandle>())).thenReturn(emptyList())

        val result = shortcutManager.getShortcutsForPackage(specialPackageName)

        assertNotNull(result)
    }
}