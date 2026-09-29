package com.github.reygnn.kolibri_launcher.data

import android.content.Context
import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.pm.LauncherApps
import android.content.pm.PackageManager
import android.content.pm.ResolveInfo
import android.content.pm.ShortcutInfo
import com.github.reygnn.kolibri_launcher.rule.TimberRule
import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import io.mockk.MockKAnnotations
import io.mockk.every
import io.mockk.impl.annotations.MockK
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.test.runTest
import org.junit.Before
import org.junit.Rule
import org.junit.Test

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

        assertThat(result.size).isEqualTo(2)
        assertThat(result[0].id).isEqualTo("id1")
        assertThat(result[1].id).isEqualTo("id2")
    }

    @Test
    fun `getShortcutsForPackage - when SecurityException occurs - returns empty list`() {
        every { launcherApps.getShortcuts(any(), any()) } throws SecurityException("Permission denied!")

        assertThat(shortcutManager.getShortcutsForPackage("com.test.app").isEmpty()).isTrue()
    }

    @Test
    fun `getShortcutsForPackage - when other Exception occurs - returns empty list`() {
        every { launcherApps.getShortcuts(any(), any()) } throws RuntimeException("Something went wrong")

        assertThat(shortcutManager.getShortcutsForPackage("com.test.app").isEmpty()).isTrue()
    }

    @Test
    fun `getShortcutsForPackage - when system returns null - returns empty list`() {
        every { launcherApps.getShortcuts(any(), any()) } returns null

        assertThat(shortcutManager.getShortcutsForPackage("com.test.app").isEmpty()).isTrue()
    }

    // ========== NEW CRASH-RESISTANCE TESTS ==========

    @Test
    fun `getShortcutsForPackage - when IllegalStateException - returns empty list`() {
        every { launcherApps.getShortcuts(any(), any()) } throws IllegalStateException("LauncherApps not initialized")

        assertThat(shortcutManager.getShortcutsForPackage("com.test.app").isEmpty()).isTrue()
    }

    @Test
    fun `getShortcutsForPackage - when IllegalArgumentException - returns empty list`() {
        every { launcherApps.getShortcuts(any(), any()) } throws IllegalArgumentException("Invalid package name")

        assertThat(shortcutManager.getShortcutsForPackage("com.test.app").isEmpty()).isTrue()
    }

    @Test
    fun `getShortcutsForPackage - with empty package name - returns empty list`() {
        assertThat(shortcutManager.getShortcutsForPackage("").isEmpty()).isTrue()
    }

    @Test
    fun `getShortcutsForPackage - with blank package name - returns empty list`() {
        assertThat(shortcutManager.getShortcutsForPackage("   ").isEmpty()).isTrue()
    }

    @Test
    fun `getShortcutsForPackage - when LauncherApps service is null - returns empty list`() {
        every { context.getSystemService(Context.LAUNCHER_APPS_SERVICE) } returns null

        val managerWithNullService = ShortcutRepositoryImpl(context)
        assertThat(managerWithNullService.getShortcutsForPackage("com.test.app").isEmpty()).isTrue()
    }

    @Test
    fun `getShortcutsForPackage - with malformed package name - handles gracefully`() {
        every { launcherApps.getShortcuts(any(), any()) } returns emptyList()

        assertThat(shortcutManager.getShortcutsForPackage("not.a.valid..package...name")).isNotNull()
    }

    @Test
    fun `getShortcutsForPackage - called multiple times - returns consistent results`() {
        val fakeShortcuts = listOf(mockk<ShortcutInfo>(relaxed = true))
        every { launcherApps.getShortcuts(any(), any()) } returns fakeShortcuts

        val result1 = shortcutManager.getShortcutsForPackage("com.test.app")
        val result2 = shortcutManager.getShortcutsForPackage("com.test.app")
        val result3 = shortcutManager.getShortcutsForPackage("com.test.app")

        assertThat(result2.size).isEqualTo(result1.size)
        assertThat(result3.size).isEqualTo(result2.size)
    }

    @Test
    fun `getShortcutsForPackage - with very large shortcut list - handles correctly`() {
        val largeShortcutList = (1..100).map { mockk<ShortcutInfo>(relaxed = true) }
        every { launcherApps.getShortcuts(any(), any()) } returns largeShortcutList

        assertThat(shortcutManager.getShortcutsForPackage("com.test.app").size).isEqualTo(100)
    }

    @Test
    fun `getShortcutsForPackage - when NullPointerException inside API - returns empty list`() {
        every { launcherApps.getShortcuts(any(), any()) } throws NullPointerException("Internal API error")

        assertThat(shortcutManager.getShortcutsForPackage("com.test.app").isEmpty()).isTrue()
    }

    @Test
    fun `getShortcutsForPackage - for different packages - returns different results`() {
        val shortcuts1 = listOf(mockk<ShortcutInfo>(relaxed = true))
        val shortcuts2 = listOf(mockk<ShortcutInfo>(relaxed = true), mockk<ShortcutInfo>(relaxed = true))

        every { launcherApps.getShortcuts(any(), any()) } returnsMany listOf(shortcuts1, shortcuts2)

        assertThat(shortcutManager.getShortcutsForPackage("com.app1").size).isEqualTo(1)
        assertThat(shortcutManager.getShortcutsForPackage("com.app2").size).isEqualTo(2)
    }

    @Test
    fun `getShortcutsForPackage - when first call fails then succeeds - handles correctly`() {
        val fakeShortcuts = listOf(mockk<ShortcutInfo>(relaxed = true))

        every { launcherApps.getShortcuts(any(), any()) } throws SecurityException("First call fails") andThen fakeShortcuts

        assertThat(shortcutManager.getShortcutsForPackage("com.test.app").isEmpty()).isTrue()
        assertThat(shortcutManager.getShortcutsForPackage("com.test.app").size).isEqualTo(1)
    }

    @Test
    fun `getShortcutsForPackage - with special characters in package name - handles correctly`() {
        every { launcherApps.getShortcuts(any(), any()) } returns emptyList()

        assertThat(shortcutManager.getShortcutsForPackage("com.test-app_name.special")).isNotNull()
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

        assertWithMessage("Should return empty list when not default launcher").that(result.isEmpty()).isTrue()
        verify(exactly = 0) { launcherApps.getShortcuts(any(), any()) }
    }

    // ========== PURGE TEST ==========

    @Test
    fun `purgeRepository - does nothing and does not crash`() = runTest {
        shortcutManager.purgeRepository()
    }
}